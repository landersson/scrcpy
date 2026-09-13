package com.genymobile.scrcpy.still;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.util.HandlerExecutor;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * One opened camera for still shots. A small YUV stream keeps 3A running with the requested focus (and, once locked, holds the AE/AWB
 * lock); each shot is a single STILL_CAPTURE request to a JPEG stream at the largest size, written to a file on the phone.
 * <p>
 * Commands run on the caller's thread and block; camera callbacks run on the session's own handler thread.
 */
@TargetApi(AndroidVersions.API_29_ANDROID_10)
final class StillSession implements AutoCloseable {

    private static final String TAG_CONVERGE = "converge";
    private static final String TAG_LOCK = "lock";
    private static final int OPEN_TIMEOUT_MS = 8000;
    private static final int OPEN_RETRY_MS = 300;
    private static final int CONVERGE_TIMEOUT_MS = 3000;
    private static final int CONVERGE_SKIP_FRAMES = 2;
    private static final int CONVERGED_FRAMES = 3;
    private static final int LOCK_TIMEOUT_MS = 1500;
    private static final int LOCKED_FRAMES = 2;
    private static final int SHOT_TIMEOUT_MS = 10000;

    static final class Options {
        String cameraId = "0";
        float focusDistance = Float.NaN; // diopters; NaN: none requested
        boolean afOff; // autofocus off even without a distance
        boolean lock = true; // lock AE/AWB once converged
        int warmupMs;
        String dir = "/data/local/tmp/phonecap-stills";
        String fpsRange = "lowest"; // "lowest" or "fixed:N" (Selection.fpsRange)

        static Options fromJson(JSONObject j) {
            Options o = new Options();
            o.cameraId = j.optString("camera_id", o.cameraId);
            if (j.has("focus_distance") && !j.isNull("focus_distance")) {
                o.focusDistance = (float) j.optDouble("focus_distance", Double.NaN);
            }
            o.afOff = j.optBoolean("af_off", o.afOff);
            o.lock = j.optBoolean("lock", o.lock);
            o.warmupMs = j.optInt("warmup_ms", o.warmupMs);
            o.dir = j.optString("dir", o.dir);
            o.fpsRange = j.optString("fps_range", o.fpsRange);
            return o;
        }
    }

    /** How a settle wait ended: the last result of the awaited request (null if none came) and whether the condition held. */
    private static final class Settled {
        final TotalCaptureResult result;
        final boolean reached;

        Settled(TotalCaptureResult result, boolean reached) {
            this.result = result;
            this.reached = reached;
        }
    }

    private final Options options;
    private final BlockingQueue<TotalCaptureResult> results = new ArrayBlockingQueue<>(64);
    private final BlockingQueue<Image> images = new LinkedBlockingQueue<>();
    private volatile String deviceError; // set by the device callbacks once the camera is lost
    private volatile boolean collecting; // preview results are queued only while a settle wait reads them

    private HandlerThread thread;
    private Handler handler;
    private Executor executor;
    private CameraCharacteristics characteristics;
    private boolean afOffSupported;
    private float focusRequest = Float.NaN; // the clamped LENS_FOCUS_DISTANCE, NaN: not set
    private Range<Integer> fpsRange;
    private Size jpegSize;
    private Size previewSize;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader jpegReader;
    private ImageReader previewReader;

    StillSession(Options options) {
        this.options = options;
    }

    /** Open the camera, configure it, let 3A converge and lock; returns the "ready" event (without id). Closes itself on failure. */
    JSONObject open() throws StillException {
        long start = SystemClock.elapsedRealtime();
        boolean ok = false;
        try {
            thread = new HandlerThread("still-camera");
            thread.start();
            handler = new Handler(thread.getLooper());
            executor = new HandlerExecutor(handler);

            CameraManager manager = ServiceManager.getCameraManager();
            String[] ids = manager.getCameraIdList();
            if (!Arrays.asList(ids).contains(options.cameraId)) {
                throw new StillException("config_unsupported", "camera " + options.cameraId + " not found (cameras: " + Arrays.toString(ids) + ")");
            }
            characteristics = manager.getCameraCharacteristics(options.cameraId);
            afOffSupported = CameraProbe.afOffSupported(characteristics);
            focusRequest = Selection.clampFocus(options.focusDistance, characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));
            fpsRange = CameraProbe.fpsRange(characteristics, options.fpsRange);
            if (fpsRange == null) {
                Protocol.log("warn", "no AE fps range matches '" + options.fpsRange + "'; using the template's");
            }
            if (!Float.isNaN(options.focusDistance) && !afOffSupported) {
                Protocol.log("warn", "camera " + options.cameraId + " cannot disable autofocus; focus_distance ignored");
            }

            long warmupMs = 0;
            if (options.warmupMs > 0) {
                // a short throwaway session, closed and reopened: the next one is sharp (docs/findings.md "Camera warmup")
                long warmupStart = SystemClock.elapsedRealtime();
                startCamera();
                SystemClock.sleep(options.warmupMs);
                closeCamera();
                warmupMs = SystemClock.elapsedRealtime() - warmupStart;
            }

            long openStart = SystemClock.elapsedRealtime();
            startCamera();
            long openMs = SystemClock.elapsedRealtime() - openStart;

            long convergeStart = SystemClock.elapsedRealtime();
            Settled converged = settle(TAG_CONVERGE, CONVERGE_TIMEOUT_MS, CONVERGE_SKIP_FRAMES, CONVERGED_FRAMES,
                    r -> Selection.converged(r.get(CaptureResult.LENS_STATE), r.get(CaptureResult.CONTROL_AE_STATE),
                            r.get(CaptureResult.CONTROL_AWB_STATE)));
            long convergeMs = SystemClock.elapsedRealtime() - convergeStart;
            if (converged.result == null) {
                throw new StillException("timeout", "no frames from camera " + options.cameraId + " within " + CONVERGE_TIMEOUT_MS + " ms");
            }
            TotalCaptureResult last = converged.result;
            if (!converged.reached) {
                Protocol.log("warn", "3A did not converge within " + CONVERGE_TIMEOUT_MS + " ms (" + states(last) + "); continuing");
            }
            boolean lockConfirmed = false;
            if (options.lock) {
                setRepeating(true);
                Settled locked = settle(TAG_LOCK, LOCK_TIMEOUT_MS, 0, LOCKED_FRAMES,
                        r -> Selection.locked(r.get(CaptureResult.CONTROL_AE_STATE), r.get(CaptureResult.CONTROL_AWB_STATE)));
                lockConfirmed = locked.reached;
                if (locked.result != null) {
                    last = locked.result;
                }
                if (!lockConfirmed) {
                    Protocol.log("warn", "AE/AWB lock requested but not reported (" + states(last) + ")");
                }
            }

            File dir = new File(options.dir);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new StillException("io", "cannot create " + dir);
            }

            JSONObject ready = Protocol.event("ready", null);
            Protocol.put(ready, "camera_id", options.cameraId);
            Protocol.put(ready, "hardware_level", CameraProbe.hardwareLevel(characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
            Protocol.put(ready, "jpeg_size", CameraProbe.size(jpegSize));
            Protocol.put(ready, "preview_size", CameraProbe.size(previewSize));
            Protocol.put(ready, "fps_range", fpsRange == null ? null : Protocol.array(fpsRange.getLower(), fpsRange.getUpper()));
            Protocol.put(ready, "af_off_supported", afOffSupported);
            Protocol.put(ready, "focus_distance_requested", options.focusDistance);
            Protocol.put(ready, "min_focus_distance", characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));
            Protocol.put(ready, "focus_calibration",
                    CameraProbe.focusCalibration(characteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)));
            Protocol.put(ready, "converged", converged.reached);
            Protocol.put(ready, "converge_ms", convergeMs);
            Protocol.put(ready, "lock_confirmed", lockConfirmed);
            Protocol.put(ready, "warmup_ms", warmupMs);
            Protocol.put(ready, "open_ms", openMs);
            Protocol.put(ready, "total_ms", SystemClock.elapsedRealtime() - start);
            putResult(ready, last);
            ok = true;
            return ready;
        } catch (StillException e) {
            throw e;
        } catch (Exception e) {
            throw wrap(e, "camera " + options.cameraId);
        } finally {
            if (!ok) {
                close();
            }
        }
    }

    /** Take one still into dir/name.jpg; returns the "shot" event (without id). */
    JSONObject shoot(String name) throws StillException {
        if (!Selection.validName(name)) {
            throw new StillException("bad_command", "bad still name: " + name);
        }
        try {
            checkDevice();
            drainImages();
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(jpegReader.getSurface());
            apply3A(builder, options.lock);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            builder.set(CaptureRequest.JPEG_ORIENTATION, 0);
            builder.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, new Size(0, 0));

            CompletableFuture<TotalCaptureResult> done = new CompletableFuture<>();
            long start = SystemClock.elapsedRealtime();
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                    done.complete(result);
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
                    done.completeExceptionally(new StillException("capture_failed",
                            "capture failed (reason " + failure.getReason() + (failure.wasImageCaptured() ? ", image captured" : "") + ")"));
                }

                @Override
                public void onCaptureBufferLost(CameraCaptureSession s, CaptureRequest request, Surface target, long frameNumber) {
                    done.completeExceptionally(new StillException("buffer_lost", "JPEG buffer lost for frame " + frameNumber));
                }
            }, handler);
            TotalCaptureResult result = await(done, SHOT_TIMEOUT_MS, "still capture");
            long captureMs = SystemClock.elapsedRealtime() - start;

            Image image = takeImage(result.get(CaptureResult.SENSOR_TIMESTAMP), start + SHOT_TIMEOUT_MS);
            long writeStart = SystemClock.elapsedRealtime();
            File file = new File(options.dir, name + ".jpg");
            int bytes;
            int width;
            int height;
            try {
                width = image.getWidth();
                height = image.getHeight();
                bytes = write(image, file);
            } finally {
                image.close();
            }
            long now = SystemClock.elapsedRealtime();

            JSONObject shot = Protocol.event("shot", null);
            Protocol.put(shot, "file", file.getPath());
            Protocol.put(shot, "width", width);
            Protocol.put(shot, "height", height);
            Protocol.put(shot, "bytes", bytes);
            Protocol.put(shot, "capture_ms", captureMs);
            Protocol.put(shot, "write_ms", now - writeStart);
            Protocol.put(shot, "latency_ms", now - start);
            putResult(shot, result);
            return shot;
        } catch (StillException e) {
            throw e;
        } catch (Exception e) {
            throw wrap(e, "still " + name);
        }
    }

    @Override
    public void close() {
        closeCamera();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    private void startCamera() throws StillException, CameraAccessException, InterruptedException {
        openDevice();
        configure();
        setRepeating(false);
    }

    private void closeCamera() {
        if (session != null) {
            try {
                session.stopRepeating();
            } catch (CameraAccessException | IllegalStateException e) {
                // closing anyway
            }
            session.close();
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        drainImages();
        closeReaders();
        results.clear();
    }

    @SuppressLint("MissingPermission")
    private void openDevice() throws StillException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + OPEN_TIMEOUT_MS;
        while (true) {
            CompletableFuture<CameraDevice> future = new CompletableFuture<>();
            try {
                ServiceManager.getCameraManager().openCamera(options.cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        if (!future.complete(camera)) {
                            camera.close(); // the command gave up waiting
                        }
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        deviceError = "camera " + options.cameraId + " disconnected";
                        future.completeExceptionally(new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED));
                        camera.close();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        deviceError = "camera " + options.cameraId + " device error " + error;
                        future.completeExceptionally(new CameraAccessException(deviceErrorReason(error)));
                        camera.close();
                    }
                }, handler);
                long remaining = Math.max(1, deadline - SystemClock.elapsedRealtime());
                device = future.get(remaining, TimeUnit.MILLISECONDS);
                deviceError = null;
                return;
            } catch (CameraAccessException e) { // thrown by openCamera itself
                retryOrThrow(e, deadline);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (!(cause instanceof CameraAccessException)) {
                    throw new StillException("internal", "opening camera " + options.cameraId + ": " + cause, cause);
                }
                retryOrThrow((CameraAccessException) cause, deadline);
            } catch (TimeoutException e) {
                future.thenAccept(CameraDevice::close);
                throw new StillException("timeout", "camera " + options.cameraId + " did not open within " + OPEN_TIMEOUT_MS + " ms");
            }
        }
    }

    /** Wait and return when the camera is only busy (scrcpy has just released it) and time is left; throw otherwise. */
    private void retryOrThrow(CameraAccessException e, long deadline) throws StillException {
        int reason = e.getReason();
        boolean busy = reason == CameraAccessException.CAMERA_IN_USE || reason == CameraAccessException.MAX_CAMERAS_IN_USE;
        if (!busy || SystemClock.elapsedRealtime() + OPEN_RETRY_MS >= deadline) {
            throw new StillException(accessCode(reason), "cannot open camera " + options.cameraId + ": " + e.getMessage(), e);
        }
        SystemClock.sleep(OPEN_RETRY_MS);
    }

    private void configure() throws StillException, CameraAccessException, InterruptedException {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        List<Size> candidates = jpegCandidates(CameraProbe.outputSizes(map, ImageFormat.JPEG),
                CameraProbe.highResolutionSizes(map, ImageFormat.JPEG));
        for (int i = 0; i < candidates.size(); ++i) {
            Size size = candidates.get(i);
            Size preview = CameraProbe.previewSize(map, size);
            if (preview == null) {
                throw new StillException("config_unsupported", "camera " + options.cameraId + " offers no YUV output");
            }
            createReaders(size, preview);
            CompletableFuture<CameraCaptureSession> configured = new CompletableFuture<>();
            List<OutputConfiguration> outputs = Arrays.asList(new OutputConfiguration(previewReader.getSurface()),
                    new OutputConfiguration(jpegReader.getSurface()));
            String description = "JPEG " + size + " + YUV " + preview;
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (!configured.complete(s)) {
                                s.close();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            configured.completeExceptionally(new StillException("config_unsupported", "session configuration failed: " + description));
                        }
                    });
            if (!isSupported(config)) {
                closeReaders();
                if (i < candidates.size() - 1) {
                    Protocol.log("info", description + " is not supported; trying a smaller JPEG size");
                    continue;
                }
                throw new StillException("config_unsupported", description + " is not a supported configuration");
            }
            device.createCaptureSession(config);
            session = await(configured, OPEN_TIMEOUT_MS, "session configuration");
            jpegSize = size;
            previewSize = preview;
            return;
        }
        throw new StillException("config_unsupported", "camera " + options.cameraId + " offers no JPEG output");
    }

    /**
     * The JPEG sizes to try, best first: the largest high-resolution size when it is larger than the largest regular one (it is outside
     * the guaranteed stream combinations, so the session check decides), then the largest regular one.
     */
    private static List<Size> jpegCandidates(Size[] regular, Size[] highRes) {
        List<Size> candidates = new ArrayList<>();
        Size largestRegular = CameraProbe.largest(regular);
        Size largestHighRes = CameraProbe.largest(highRes);
        if (largestHighRes != null && (largestRegular == null || area(largestHighRes) > area(largestRegular))) {
            candidates.add(largestHighRes);
        }
        if (largestRegular != null) {
            candidates.add(largestRegular);
        }
        return candidates;
    }

    private static long area(Size s) {
        return (long) s.getWidth() * s.getHeight();
    }

    private boolean isSupported(SessionConfiguration config) throws CameraAccessException {
        try {
            return device.isSessionConfigurationSupported(config);
        } catch (UnsupportedOperationException e) {
            return true; // the HAL cannot answer: just try it
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void createReaders(Size jpeg, Size preview) {
        jpegReader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(), ImageFormat.JPEG, 2);
        jpegReader.setOnImageAvailableListener(reader -> {
            try {
                Image image = reader.acquireNextImage();
                if (image != null) {
                    images.add(image);
                }
            } catch (IllegalStateException e) {
                Protocol.log("warn", "JPEG image dropped: " + e.getMessage());
            }
        }, handler);
        previewReader = ImageReader.newInstance(preview.getWidth(), preview.getHeight(), ImageFormat.YUV_420_888, 2);
        previewReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                image.close();
            }
        }, handler);
    }

    private void closeReaders() {
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        if (previewReader != null) {
            previewReader.close();
            previewReader = null;
        }
    }

    private void setRepeating(boolean lock) throws CameraAccessException {
        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewReader.getSurface());
        apply3A(builder, lock);
        builder.setTag(lock ? TAG_LOCK : TAG_CONVERGE);
        results.clear();
        collecting = true;
        session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                if (collecting && !results.offer(result)) {
                    results.poll();
                    results.offer(result);
                }
            }
        }, handler);
    }

    private void apply3A(CaptureRequest.Builder builder, boolean lock) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
        if (fpsRange != null) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        }
        if (afOffSupported && (options.afOff || !Float.isNaN(options.focusDistance))) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            if (!Float.isNaN(focusRequest)) {
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusRequest);
            }
        } else if (CameraProbe.hasAfMode(characteristics, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        builder.set(CaptureRequest.CONTROL_AE_LOCK, lock);
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, lock);
    }

    /**
     * Read results of the repeating request tagged `tag` until `condition` holds on `frames` consecutive ones (after skipping `skip`)
     * or `timeoutMs` passes. Stops the result queue afterwards: nothing reads it between settle waits.
     */
    private Settled settle(String tag, int timeoutMs, int skip, int frames, Predicate<CaptureResult> condition)
            throws StillException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        TotalCaptureResult last = null;
        int toSkip = skip;
        int streak = 0;
        try {
            while (true) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                TotalCaptureResult r = remaining > 0 ? results.poll(remaining, TimeUnit.MILLISECONDS) : null;
                checkDevice();
                if (r == null) {
                    return new Settled(last, false);
                }
                if (!tag.equals(r.getRequest().getTag())) {
                    continue;
                }
                last = r;
                if (toSkip > 0) {
                    --toSkip;
                } else if (!condition.test(r)) {
                    streak = 0;
                } else if (++streak >= frames) {
                    return new Settled(r, true);
                }
            }
        } finally {
            collecting = false;
            results.clear();
        }
    }

    private static String states(CaptureResult r) {
        return "lens " + CameraProbe.lensState(r.get(CaptureResult.LENS_STATE)) + ", AE " + CameraProbe.aeState(
                r.get(CaptureResult.CONTROL_AE_STATE)) + ", AWB " + CameraProbe.awbState(r.get(CaptureResult.CONTROL_AWB_STATE));
    }

    private Image takeImage(Long timestamp, long deadline) throws StillException, InterruptedException {
        while (true) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            Image image = remaining > 0 ? images.poll(remaining, TimeUnit.MILLISECONDS) : null;
            if (image == null) {
                checkDevice();
                throw new StillException("timeout", "no JPEG arrived within " + SHOT_TIMEOUT_MS + " ms");
            }
            if (timestamp == null || image.getTimestamp() == timestamp) {
                return image;
            }
            image.close(); // left over from an earlier failed shot
        }
    }

    private static int write(Image image, File file) throws IOException {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        int bytes = buffer.remaining();
        File part = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            IO.writeFully(out.getFD(), buffer);
        }
        if (!part.renameTo(file)) {
            throw new IOException("cannot rename " + part + " to " + file.getName());
        }
        return bytes;
    }

    private void drainImages() {
        Image image;
        while ((image = images.poll()) != null) {
            image.close();
        }
    }

    private void checkDevice() throws StillException {
        String lost = deviceError;
        if (lost != null) {
            throw new StillException("camera_disconnected", lost);
        }
    }

    private static void putResult(JSONObject o, CaptureResult r) {
        Protocol.put(o, "exposure_ns", r.get(CaptureResult.SENSOR_EXPOSURE_TIME));
        Protocol.put(o, "iso", r.get(CaptureResult.SENSOR_SENSITIVITY));
        Protocol.put(o, "frame_duration_ns", r.get(CaptureResult.SENSOR_FRAME_DURATION));
        Protocol.put(o, "focus_distance", r.get(CaptureResult.LENS_FOCUS_DISTANCE));
        Protocol.put(o, "lens_state", CameraProbe.lensState(r.get(CaptureResult.LENS_STATE)));
        Protocol.put(o, "ae_state", CameraProbe.aeState(r.get(CaptureResult.CONTROL_AE_STATE)));
        Protocol.put(o, "awb_state", CameraProbe.awbState(r.get(CaptureResult.CONTROL_AWB_STATE)));
        RggbChannelVector gains = r.get(CaptureResult.COLOR_CORRECTION_GAINS);
        Protocol.put(o, "awb_gains",
                gains == null ? null : Protocol.array(gains.getRed(), gains.getGreenEven(), gains.getGreenOdd(), gains.getBlue()));
        Protocol.put(o, "active_physical_id", r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID));
        Protocol.put(o, "sensor_timestamp", r.get(CaptureResult.SENSOR_TIMESTAMP));
        Protocol.put(o, "frame_number", r.getFrameNumber());
    }

    private <T> T await(CompletableFuture<T> future, long timeoutMs, String what) throws StillException, InterruptedException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new StillException("timeout", what + " timed out after " + timeoutMs + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StillException) {
                throw (StillException) cause;
            }
            if (cause instanceof Exception) {
                throw wrap((Exception) cause, what);
            }
            throw new StillException("internal", what + ": " + cause, cause);
        }
    }

    /** A failure as the error the host receives: camera errors keep their reason; anything after the camera was lost says so. */
    private StillException wrap(Exception e, String what) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        String code;
        if (e instanceof CameraAccessException) {
            code = accessCode(((CameraAccessException) e).getReason());
        } else if (e instanceof IOException) {
            code = "io";
        } else if (deviceError != null) {
            code = "camera_disconnected"; // the session or device was closed underneath
        } else {
            code = "internal";
        }
        return new StillException(code, what + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
    }

    private static int deviceErrorReason(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return CameraAccessException.CAMERA_IN_USE;
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return CameraAccessException.MAX_CAMERAS_IN_USE;
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return CameraAccessException.CAMERA_DISABLED;
            default:
                return CameraAccessException.CAMERA_ERROR;
        }
    }

    static String accessCode(int reason) {
        switch (reason) {
            case CameraAccessException.CAMERA_IN_USE:
            case CameraAccessException.MAX_CAMERAS_IN_USE:
                return "camera_in_use";
            case CameraAccessException.CAMERA_DISCONNECTED:
                return "camera_disconnected";
            case CameraAccessException.CAMERA_DISABLED:
                return "camera_disabled";
            default:
                return "camera_error";
        }
    }
}
