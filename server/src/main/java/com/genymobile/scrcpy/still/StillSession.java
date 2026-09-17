package com.genymobile.scrcpy.still;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.util.HandlerExecutor;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
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
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
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
    private static final long MANUAL_FRAME_DURATION_NS = 33_333_333L; // at least; longer for a longer manual exposure

    static final class Options {
        String cameraId = "0";
        float focusDistance = Float.NaN; // diopters; NaN: none requested
        boolean afOff; // autofocus off even without a distance
        boolean lock = true; // lock AE/AWB once converged
        int warmupMs;
        String dir = "/data/local/tmp/phonecap-stills";
        String fpsRange = "lowest"; // "lowest" or "fixed:N" (Selection.fpsRange)
        boolean raw; // also configure a RAW stream (shots may then ask for a RAW file)
        String rawFormat = "sensor"; // "sensor" (RAW_SENSOR, written as a DNG), "raw10" or "raw12" (packed dump + JSON sidecar)
        String rawPixelMode = "default"; // "max": the RAW stream from the maximum-resolution map; RAW shots in SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
        JSONArray vendor = new JSONArray(); // [{name, type, value}] vendor tags set on the session parameters and on every request
        long rawUseCase = -1; // OutputConfiguration stream use case for the RAW stream (a SCALER_AVAILABLE_STREAM_USE_CASES value); -1: none
        String ois = "default"; // optical stabilisation: "on", "off", or "default": the templates'

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
            o.raw = j.optBoolean("raw", o.raw);
            o.rawFormat = j.optString("raw_format", o.rawFormat);
            o.rawPixelMode = j.optString("raw_pixel_mode", o.rawPixelMode);
            o.vendor = j.optJSONArray("vendor") != null ? j.optJSONArray("vendor") : o.vendor;
            o.rawUseCase = j.optLong("raw_use_case", o.rawUseCase);
            o.ois = j.optString("ois", o.ois);
            return o;
        }
    }

    /** How one shot is processed: noise reduction and edge (sharpening) modes, a manual exposure, a RAW DNG next to the JPEG. */
    static final class ShotOptions {
        String noiseReduction = "default"; // a Selection.NOISE_REDUCTION_MODES name, or "default": the still template's
        String edge = "default"; // a Selection.EDGE_MODES name, or "default"
        long exposureNs; // with iso: AE off, this exposure; 0: the (locked) AE exposure
        int iso;
        boolean raw; // also write the RAW frame (the session must be opened with raw)
        boolean jpeg = true; // write name.jpg (never with a maximum-resolution RAW: that request cannot target the JPEG stream)

        static ShotOptions fromJson(JSONObject j) {
            ShotOptions o = new ShotOptions();
            o.noiseReduction = j.optString("noise_reduction", o.noiseReduction);
            o.edge = j.optString("edge", o.edge);
            o.exposureNs = j.optLong("exposure_ns", o.exposureNs);
            o.iso = j.optInt("iso", o.iso);
            o.raw = j.optBoolean("raw", o.raw);
            o.jpeg = j.optBoolean("jpeg", o.jpeg);
            return o;
        }
    }

    /** A vendor tag to set on requests, by name: the framework resolves it against the HAL's vendor tag descriptor. */
    static final class VendorKey {
        final String name;
        final String type; // byte, int, long, float, double (a JSON array value makes it an array of that type)
        final Object value;

        VendorKey(String name, String type, Object value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }

        static List<VendorKey> fromJson(JSONArray a) throws StillException {
            List<VendorKey> keys = new ArrayList<>();
            for (int i = 0; i < a.length(); ++i) {
                JSONObject j = a.optJSONObject(i);
                if (j == null || !j.has("name") || !j.has("value")) {
                    throw new StillException("bad_command", "vendor entries are {name, type, value}: " + a.opt(i));
                }
                keys.add(new VendorKey(j.optString("name"), j.optString("type", "byte"), j.opt("value")));
            }
            return keys;
        }

        void set(CaptureRequest.Builder builder) throws StillException {
            Object v = value;
            boolean array = v instanceof JSONArray;
            int n = array ? ((JSONArray) v).length() : 1;
            switch (type) {
                case "byte": {
                    byte[] bytes = new byte[n];
                    for (int i = 0; i < n; ++i) {
                        bytes[i] = (byte) number(array ? ((JSONArray) v).opt(i) : v).intValue();
                    }
                    if (array) {
                        builder.set(new CaptureRequest.Key<>(name, byte[].class), bytes);
                    } else {
                        builder.set(new CaptureRequest.Key<>(name, Byte.class), bytes[0]);
                    }
                    return;
                }
                case "int": {
                    int[] ints = new int[n];
                    for (int i = 0; i < n; ++i) {
                        ints[i] = number(array ? ((JSONArray) v).opt(i) : v).intValue();
                    }
                    if (array) {
                        builder.set(new CaptureRequest.Key<>(name, int[].class), ints);
                    } else {
                        builder.set(new CaptureRequest.Key<>(name, Integer.class), ints[0]);
                    }
                    return;
                }
                case "long": {
                    long[] longs = new long[n];
                    for (int i = 0; i < n; ++i) {
                        longs[i] = number(array ? ((JSONArray) v).opt(i) : v).longValue();
                    }
                    if (array) {
                        builder.set(new CaptureRequest.Key<>(name, long[].class), longs);
                    } else {
                        builder.set(new CaptureRequest.Key<>(name, Long.class), longs[0]);
                    }
                    return;
                }
                case "float": {
                    float[] floats = new float[n];
                    for (int i = 0; i < n; ++i) {
                        floats[i] = number(array ? ((JSONArray) v).opt(i) : v).floatValue();
                    }
                    if (array) {
                        builder.set(new CaptureRequest.Key<>(name, float[].class), floats);
                    } else {
                        builder.set(new CaptureRequest.Key<>(name, Float.class), floats[0]);
                    }
                    return;
                }
                case "double": {
                    double[] doubles = new double[n];
                    for (int i = 0; i < n; ++i) {
                        doubles[i] = number(array ? ((JSONArray) v).opt(i) : v).doubleValue();
                    }
                    if (array) {
                        builder.set(new CaptureRequest.Key<>(name, double[].class), doubles);
                    } else {
                        builder.set(new CaptureRequest.Key<>(name, Double.class), doubles[0]);
                    }
                    return;
                }
                default:
                    throw new StillException("bad_command", "vendor key " + name + ": unknown type " + type);
            }
        }

        private Number number(Object v) throws StillException {
            if (v instanceof Number) {
                return (Number) v;
            }
            if (v instanceof Boolean) {
                return ((Boolean) v) ? 1 : 0;
            }
            throw new StillException("bad_command", "vendor key " + name + ": not a number: " + v);
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
    private final BlockingQueue<Image> rawImages = new LinkedBlockingQueue<>();
    private volatile String deviceError; // set by the device callbacks once the camera is lost
    private volatile boolean collecting; // preview results are queued only while a settle wait reads them

    private HandlerThread thread;
    private Handler handler;
    private Executor executor;
    private CameraCharacteristics characteristics;
    private boolean afOffSupported;
    private float focusRequest = Float.NaN; // the clamped LENS_FOCUS_DISTANCE, NaN: not set
    private Range<Integer> fpsRange;
    private int oisMode = -1; // LENS_OPTICAL_STABILIZATION_MODE set on every request; -1: the templates'
    private Size jpegSize;
    private Size previewSize;
    private Size rawSize;
    private int rawFormat = ImageFormat.RAW_SENSOR;
    private boolean rawMax; // RAW shots in SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
    private List<VendorKey> vendorKeys = new ArrayList<>();
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader jpegReader;
    private ImageReader previewReader;
    private ImageReader rawReader;

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
            oisMode = opticalStabilization(options.ois);
            if (!Float.isNaN(options.focusDistance) && !afOffSupported) {
                Protocol.log("warn", "camera " + options.cameraId + " cannot disable autofocus; focus_distance ignored");
            }
            rawFormat = rawFormat(options.rawFormat);
            rawMax = rawPixelMode(options.rawPixelMode);
            vendorKeys = VendorKey.fromJson(options.vendor);

            File dir = new File(options.dir);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new StillException("io", "cannot create " + dir);
            }
            if (options.raw) {
                Metadata.write(new File(dir, "characteristics-" + options.cameraId + ".json"), Metadata.characteristics(characteristics));
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

            JSONObject ready = Protocol.event("ready", null);
            Protocol.put(ready, "camera_id", options.cameraId);
            Protocol.put(ready, "hardware_level", CameraProbe.hardwareLevel(characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
            Protocol.put(ready, "jpeg_size", CameraProbe.size(jpegSize));
            Protocol.put(ready, "preview_size", CameraProbe.size(previewSize));
            Protocol.put(ready, "raw_size", rawSize == null ? null : CameraProbe.size(rawSize));
            Protocol.put(ready, "raw_format", rawSize == null ? null : options.rawFormat);
            Protocol.put(ready, "raw_pixel_mode", rawSize == null ? null : (rawMax ? "max" : "default"));
            Protocol.put(ready, "fps_range", fpsRange == null ? null : Protocol.array(fpsRange.getLower(), fpsRange.getUpper()));
            Protocol.put(ready, "af_off_supported", afOffSupported);
            Protocol.put(ready, "focus_distance_requested", options.focusDistance);
            Protocol.put(ready, "ois_requested", options.ois);
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

    /** Take one still into dir/name.jpg (and dir/name.dng when asked); returns the "shot" event (without id). */
    JSONObject shoot(String name, ShotOptions shotOptions) throws StillException {
        if (!Selection.validName(name)) {
            throw new StillException("bad_command", "bad still name: " + name);
        }
        if (shotOptions.raw && rawReader == null) {
            throw new StillException("bad_command", "a RAW still needs a session opened with raw");
        }
        if ((shotOptions.exposureNs > 0) != (shotOptions.iso > 0)) {
            throw new StillException("bad_command", "a manual exposure needs both exposure_ns and iso");
        }
        boolean jpeg = shotOptions.jpeg && !(shotOptions.raw && rawMax);
        if (!jpeg && !shotOptions.raw) {
            throw new StillException("bad_command", "a shot needs a JPEG or a RAW");
        }
        try {
            checkDevice();
            drainImages();
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            if (jpeg) {
                builder.addTarget(jpegReader.getSurface());
            }
            if (shotOptions.raw) {
                builder.addTarget(rawReader.getSurface());
                if (rawMax) {
                    builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
                }
            }
            apply3A(builder, options.lock);
            applyProcessing(builder, shotOptions);
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

            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            long writeStart = SystemClock.elapsedRealtime();
            File file = null;
            int bytes = 0;
            int width = 0;
            int height = 0;
            if (jpeg) {
                Image image = takeImage(images, timestamp, start + SHOT_TIMEOUT_MS, "JPEG");
                file = new File(options.dir, name + ".jpg");
                try {
                    width = image.getWidth();
                    height = image.getHeight();
                    bytes = write(image, file);
                } finally {
                    image.close();
                }
            }
            File rawFile = null;
            File metaFile = null;
            long rawBytes = 0;
            if (shotOptions.raw) {
                Image raw = takeImage(rawImages, timestamp, start + SHOT_TIMEOUT_MS, "RAW");
                try {
                    if (!jpeg) {
                        width = raw.getWidth();
                        height = raw.getHeight();
                    }
                    JSONObject meta = Metadata.total(result);
                    Protocol.put(meta, "format", rawFormatName(rawFormat));
                    Protocol.put(meta, "width", raw.getWidth());
                    Protocol.put(meta, "height", raw.getHeight());
                    Protocol.put(meta, "row_stride", raw.getPlanes()[0].getRowStride());
                    Protocol.put(meta, "pixel_stride", raw.getPlanes()[0].getPixelStride());
                    Protocol.put(meta, "pixel_mode", rawMax ? "max" : "default");
                    if (rawFormat == ImageFormat.RAW_SENSOR) {
                        rawFile = new File(options.dir, name + ".dng");
                        rawBytes = writeDng(raw, result, rawFile);
                    } else {
                        rawFile = new File(options.dir, name + "." + rawFormatName(rawFormat));
                        rawBytes = write(raw, rawFile);
                    }
                    Protocol.put(meta, "raw_file", rawFile.getName());
                    Protocol.put(meta, "raw_bytes", rawBytes);
                    metaFile = new File(options.dir, name + ".json");
                    Metadata.write(metaFile, meta);
                } finally {
                    raw.close();
                }
            }
            long now = SystemClock.elapsedRealtime();

            JSONObject shot = Protocol.event("shot", null);
            Protocol.put(shot, "file", file == null ? null : file.getPath());
            Protocol.put(shot, "width", width);
            Protocol.put(shot, "height", height);
            Protocol.put(shot, "bytes", file == null ? null : bytes);
            Protocol.put(shot, "raw_file", rawFile == null ? null : rawFile.getPath());
            Protocol.put(shot, "raw_bytes", rawFile == null ? null : rawBytes);
            Protocol.put(shot, "meta_file", metaFile == null ? null : metaFile.getPath());
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
            List<OutputConfiguration> outputs = new ArrayList<>(Arrays.asList(new OutputConfiguration(previewReader.getSurface()),
                    new OutputConfiguration(jpegReader.getSurface())));
            Size raw = null;
            if (options.raw) {
                StreamConfigurationMap rawMap = rawMax ? characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) : map;
                raw = CameraProbe.largest(CameraProbe.outputSizes(rawMap, rawFormat));
                if (raw == null) {
                    closeReaders();
                    throw new StillException("config_unsupported", "camera " + options.cameraId + " offers no " + rawFormatName(rawFormat).toUpperCase()
                            + " output" + (rawMax ? " at maximum resolution" : ""));
                }
                rawReader = ImageReader.newInstance(raw.getWidth(), raw.getHeight(), rawFormat, 2);
                rawReader.setOnImageAvailableListener(reader -> queue(reader, rawImages, "RAW"), handler);
                OutputConfiguration rawOutput = new OutputConfiguration(rawReader.getSurface());
                if (rawMax) {
                    rawOutput.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
                }
                if (options.rawUseCase >= 0) {
                    rawOutput.setStreamUseCase(options.rawUseCase);
                }
                outputs.add(rawOutput);
            }
            String description = "JPEG " + size + " + YUV " + preview
                    + (raw == null ? "" : " + " + rawFormatName(rawFormat).toUpperCase() + " " + raw + (rawMax ? " (maximum resolution)" : ""));
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
            if (!vendorKeys.isEmpty()) {
                CaptureRequest.Builder params = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                applyVendor(params);
                config.setSessionParameters(params.build());
            }
            if (!isSupported(config)) {
                if (rawMax) {
                    // the maximum-resolution mix is outside the guaranteed combinations; the HAL decides
                    Protocol.log("warn", description + " is not reported as supported; trying anyway");
                } else {
                    closeReaders();
                    if (i < candidates.size() - 1) {
                        Protocol.log("info", description + " is not supported; trying a smaller JPEG size");
                        continue;
                    }
                    throw new StillException("config_unsupported", description + " is not a supported configuration");
                }
            }
            device.createCaptureSession(config);
            session = await(configured, OPEN_TIMEOUT_MS, "session configuration");
            jpegSize = size;
            previewSize = preview;
            rawSize = raw;
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
        jpegReader.setOnImageAvailableListener(reader -> queue(reader, images, "JPEG"), handler);
        previewReader = ImageReader.newInstance(preview.getWidth(), preview.getHeight(), ImageFormat.YUV_420_888, 2);
        previewReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                image.close();
            }
        }, handler);
    }

    private static void queue(ImageReader reader, BlockingQueue<Image> queue, String what) {
        try {
            Image image = reader.acquireNextImage();
            if (image != null) {
                queue.add(image);
            }
        } catch (IllegalStateException e) {
            Protocol.log("warn", what + " image dropped: " + e.getMessage());
        }
    }

    private void closeReaders() {
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        if (rawReader != null) {
            rawReader.close();
            rawReader = null;
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

    private void applyVendor(CaptureRequest.Builder builder) {
        for (VendorKey key : vendorKeys) {
            try {
                key.set(builder);
            } catch (StillException | IllegalArgumentException | UnsupportedOperationException e) {
                Protocol.log("warn", "vendor key " + key.name + " not set: " + e.getMessage());
            }
        }
    }

    private static int rawFormat(String name) throws StillException {
        switch (name) {
            case "sensor":
                return ImageFormat.RAW_SENSOR;
            case "raw10":
                return ImageFormat.RAW10;
            case "raw12":
                return ImageFormat.RAW12;
            default:
                throw new StillException("bad_command", "raw_format must be sensor, raw10 or raw12: " + name);
        }
    }

    private static String rawFormatName(int format) {
        switch (format) {
            case ImageFormat.RAW_SENSOR:
                return "sensor";
            case ImageFormat.RAW10:
                return "raw10";
            case ImageFormat.RAW12:
                return "raw12";
            default:
                return String.valueOf(format);
        }
    }

    private static boolean rawPixelMode(String name) throws StillException {
        switch (name) {
            case "default":
                return false;
            case "max":
                return true;
            default:
                throw new StillException("bad_command", "raw_pixel_mode must be default or max: " + name);
        }
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
        if (oisMode != -1) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode);
        }
        applyVendor(builder);
    }

    /** LENS_OPTICAL_STABILIZATION_MODE for "on" or "off" (checked against what the lens offers); -1 for "default". */
    private int opticalStabilization(String name) throws StillException {
        if ("default".equals(name)) {
            return -1;
        }
        int mode;
        if ("off".equals(name)) {
            mode = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF;
        } else if ("on".equals(name)) {
            mode = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON;
        } else {
            throw new StillException("bad_command", "ois must be on, off or default: " + name);
        }
        requireMode(mode, name, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, "optical stabilisation");
        return mode;
    }

    /** A shot's noise reduction and edge modes (checked against what the camera offers) and manual exposure. */
    private void applyProcessing(CaptureRequest.Builder builder, ShotOptions o) throws StillException {
        int noiseReduction = Selection.mode(o.noiseReduction, Selection.NOISE_REDUCTION_MODES);
        if (noiseReduction != -1) {
            requireMode(noiseReduction, o.noiseReduction, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, "noise reduction");
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReduction);
        }
        int edge = Selection.mode(o.edge, Selection.EDGE_MODES);
        if (edge != -1) {
            requireMode(edge, o.edge, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, "edge");
            builder.set(CaptureRequest.EDGE_MODE, edge);
        }
        if (o.exposureNs > 0) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, o.exposureNs);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, o.iso);
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(o.exposureNs, MANUAL_FRAME_DURATION_NS));
        }
    }

    private void requireMode(int mode, String name, CameraCharacteristics.Key<int[]> key, String what) throws StillException {
        int[] available = characteristics.get(key);
        if (mode < 0 || available == null || Arrays.stream(available).noneMatch(m -> m == mode)) {
            throw new StillException("config_unsupported", what + " mode '" + name + "' is not offered (" + Arrays.toString(available) + ")");
        }
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

    private Image takeImage(BlockingQueue<Image> queue, Long timestamp, long deadline, String what) throws StillException, InterruptedException {
        while (true) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            Image image = remaining > 0 ? queue.poll(remaining, TimeUnit.MILLISECONDS) : null;
            if (image == null) {
                checkDevice();
                throw new StillException("timeout", "no " + what + " image arrived within " + SHOT_TIMEOUT_MS + " ms");
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

    private long writeDng(Image image, CaptureResult result, File file) throws IOException {
        File part = new File(file.getPath() + ".part");
        try (DngCreator dng = new DngCreator(characteristics, result);
                OutputStream out = new BufferedOutputStream(new FileOutputStream(part), 1 << 20)) {
            dng.writeImage(out, image);
        }
        if (!part.renameTo(file)) {
            throw new IOException("cannot rename " + part + " to " + file.getName());
        }
        return file.length();
    }

    private void drainImages() {
        for (BlockingQueue<Image> queue : Arrays.asList(images, rawImages)) {
            Image image;
            while ((image = queue.poll()) != null) {
                image.close();
            }
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
        // digital gain after the sensor, part of AE on some HALs (the Pixel): not in exposure x ISO
        Protocol.put(o, "post_raw_boost", r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST));
        Protocol.put(o, "noise_reduction_mode", CameraProbe.name(r.get(CaptureResult.NOISE_REDUCTION_MODE), Selection.NOISE_REDUCTION_MODES));
        Protocol.put(o, "edge_mode", CameraProbe.name(r.get(CaptureResult.EDGE_MODE), Selection.EDGE_MODES));
        Integer ois = r.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
        Protocol.put(o, "ois_mode", ois == null ? null : ois == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON ? "on" : "off");
        // where the HAL puts the lens for this frame: optical centre (fx, fy, cx, cy, skew), pose, crop
        Protocol.put(o, "lens_intrinsics", floats(r.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)));
        Protocol.put(o, "lens_pose_translation", floats(r.get(CaptureResult.LENS_POSE_TRANSLATION)));
        Protocol.put(o, "lens_pose_rotation", floats(r.get(CaptureResult.LENS_POSE_ROTATION)));
        Rect crop = r.get(CaptureResult.SCALER_CROP_REGION);
        Protocol.put(o, "crop_region", crop == null ? null : Protocol.array(crop.left, crop.top, crop.width(), crop.height()));
        Protocol.put(o, "active_physical_id", r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID));
        Integer pixelMode = r.get(CaptureResult.SENSOR_PIXEL_MODE);
        Protocol.put(o, "pixel_mode", pixelMode == null ? null : pixelMode == CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION ? "max" : "default");
        Protocol.put(o, "sensor_timestamp", r.get(CaptureResult.SENSOR_TIMESTAMP));
        Protocol.put(o, "frame_number", r.getFrameNumber());
    }

    private static JSONArray floats(float[] values) {
        if (values == null) {
            return null;
        }
        Object[] boxed = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return Protocol.array(boxed);
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
