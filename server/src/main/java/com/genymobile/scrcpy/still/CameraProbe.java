package com.genymobile.scrcpy.still;

import com.genymobile.scrcpy.AndroidVersions;

import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Set;

/** Camera characteristics as the still server sees them, and the size/fps picks derived from them. */
@TargetApi(AndroidVersions.API_29_ANDROID_10)
final class CameraProbe {

    static final int PREVIEW_MIN_WIDTH = 640;

    private static final String[] CAPABILITIES = {
        "BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW", "PRIVATE_REPROCESSING", "READ_SENSOR_SETTINGS",
        "BURST_CAPTURE", "YUV_REPROCESSING", "DEPTH_OUTPUT", "CONSTRAINED_HIGH_SPEED_VIDEO", "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA",
        "MONOCHROME", "SECURE_IMAGE_DATA", "SYSTEM_CAMERA", "OFFLINE_PROCESSING", "ULTRA_HIGH_RESOLUTION_SENSOR", "REMOSAIC_REPROCESSING",
        "DYNAMIC_RANGE_TEN_BIT", "STREAM_USE_CASE", "COLOR_SPACE_PROFILES",
    };

    private CameraProbe() {
        // not instantiable
    }

    static JSONObject describe(String id, CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] jpeg = outputSizes(map, ImageFormat.JPEG);
        Size[] jpegHighRes = highResolutionSizes(map, ImageFormat.JPEG);
        Size jpegMax = largest(jpeg);
        JSONObject o = Protocol.event("camera", null);
        Protocol.put(o, "camera_id", id);
        Protocol.put(o, "facing", name(c.get(CameraCharacteristics.LENS_FACING), "FRONT", "BACK", "EXTERNAL"));
        Protocol.put(o, "hardware_level", hardwareLevel(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
        JSONArray capabilities = new JSONArray();
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null) {
            for (int cap : caps) {
                capabilities.put(name(cap, CAPABILITIES));
            }
        }
        Protocol.put(o, "capabilities", capabilities);
        Protocol.put(o, "jpeg_sizes", sizes(jpeg));
        Protocol.put(o, "jpeg_high_res_sizes", sizes(jpegHighRes));
        Protocol.put(o, "yuv_sizes", sizes(outputSizes(map, ImageFormat.YUV_420_888)));
        Protocol.put(o, "jpeg_max", jpegMax == null ? null : size(jpegMax));
        Size preview = jpegMax == null ? null : previewSize(map, jpegMax);
        Protocol.put(o, "preview_size", preview == null ? null : size(preview));
        JSONArray afModes = new JSONArray();
        int[] modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes != null) {
            for (int mode : modes) {
                afModes.put(name(mode, "OFF", "AUTO", "MACRO", "CONTINUOUS_VIDEO", "CONTINUOUS_PICTURE", "EDOF"));
            }
        }
        Protocol.put(o, "af_modes", afModes);
        Protocol.put(o, "af_off_supported", afOffSupported(c));
        Protocol.put(o, "min_focus_distance", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));
        Protocol.put(o, "focus_calibration", focusCalibration(c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)));
        JSONArray fps = new JSONArray();
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                fps.put(Protocol.array(r.getLower(), r.getUpper()));
            }
        }
        Protocol.put(o, "ae_fps_ranges", fps);
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Protocol.put(o, "active_array", active == null ? null : Protocol.array(active.width(), active.height()));
        Protocol.put(o, "focal_lengths", floats(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)));
        Set<String> physical = c.getPhysicalCameraIds();
        Protocol.put(o, "physical_ids", new JSONArray(physical));
        return o;
    }

    static Size[] outputSizes(StreamConfigurationMap map, int format) {
        Size[] sizes = map == null ? null : map.getOutputSizes(format);
        return sizes == null ? new Size[0] : sizes;
    }

    static Size[] highResolutionSizes(StreamConfigurationMap map, int format) {
        Size[] sizes = map == null ? null : map.getHighResolutionOutputSizes(format);
        return sizes == null ? new Size[0] : sizes;
    }

    static Size largest(Size[] sizes) {
        int i = Selection.largest(pairs(sizes));
        return i < 0 ? null : sizes[i];
    }

    static Size previewSize(StreamConfigurationMap map, Size jpeg) {
        Size[] yuv = outputSizes(map, ImageFormat.YUV_420_888);
        int i = Selection.preview(pairs(yuv), jpeg.getWidth(), jpeg.getHeight(), PREVIEW_MIN_WIDTH);
        return i < 0 ? null : yuv[i];
    }

    /** The AE target fps range for a policy ("lowest" or "fixed:N"); null when none matches (the template's range stays). */
    static Range<Integer> fpsRange(CameraCharacteristics c, String policy) {
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) {
            return null;
        }
        int[][] pairs = new int[ranges.length][];
        for (int i = 0; i < ranges.length; ++i) {
            pairs[i] = new int[] {ranges[i].getLower(), ranges[i].getUpper()};
        }
        int i = Selection.fpsRange(pairs, policy);
        return i < 0 ? null : ranges[i];
    }

    static boolean afOffSupported(CameraCharacteristics c) {
        return hasAfMode(c, CameraMetadata.CONTROL_AF_MODE_OFF);
    }

    static boolean hasAfMode(CameraCharacteristics c, int mode) {
        int[] modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        return modes != null && Arrays.stream(modes).anyMatch(m -> m == mode);
    }

    static int[][] pairs(Size[] sizes) {
        int[][] pairs = new int[sizes.length][];
        for (int i = 0; i < sizes.length; ++i) {
            pairs[i] = new int[] {sizes[i].getWidth(), sizes[i].getHeight()};
        }
        return pairs;
    }

    static JSONArray size(Size s) {
        return Protocol.array(s.getWidth(), s.getHeight());
    }

    static JSONArray sizes(Size[] sizes) {
        Size[] sorted = sizes.clone();
        Arrays.sort(sorted, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        JSONArray a = new JSONArray();
        for (Size s : sorted) {
            a.put(size(s));
        }
        return a;
    }

    private static JSONArray floats(float[] values) {
        JSONArray a = new JSONArray();
        if (values != null) {
            for (float v : values) {
                a.put(Protocol.array(v).opt(0));
            }
        }
        return a;
    }

    static String name(Integer value, String... names) {
        if (value == null) {
            return null;
        }
        return value >= 0 && value < names.length ? names[value] : String.valueOf(value);
    }

    static String hardwareLevel(Integer level) {
        return name(level, "LIMITED", "FULL", "LEGACY", "LEVEL_3", "EXTERNAL");
    }

    static String focusCalibration(Integer calibration) {
        return name(calibration, "UNCALIBRATED", "APPROXIMATE", "CALIBRATED");
    }

    static String lensState(Integer state) {
        return name(state, "STATIONARY", "MOVING");
    }

    static String aeState(Integer state) {
        return name(state, "INACTIVE", "SEARCHING", "CONVERGED", "LOCKED", "FLASH_REQUIRED", "PRECAPTURE");
    }

    static String awbState(Integer state) {
        return name(state, "INACTIVE", "SEARCHING", "CONVERGED", "LOCKED");
    }
}
