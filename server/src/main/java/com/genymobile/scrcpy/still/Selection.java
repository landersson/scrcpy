package com.genymobile.scrcpy.still;

import android.hardware.camera2.CameraMetadata;

/**
 * The still server's pure decisions (sizes, fps range, focus clamp, 3A states), kept free of Android objects so they can be unit tested.
 */
final class Selection {

    /** NOISE_REDUCTION_MODE names; the index is the camera2 constant. */
    static final String[] NOISE_REDUCTION_MODES = {"off", "fast", "hq", "minimal", "zsl"};
    /** EDGE_MODE names; the index is the camera2 constant. */
    static final String[] EDGE_MODES = {"off", "fast", "hq", "zsl"};

    private Selection() {
        // not instantiable
    }

    /** The camera2 constant for a mode name in `names`: -1 for "default" or null (the template's), -2 for an unknown name. */
    static int mode(String name, String[] names) {
        if (name == null || "default".equals(name)) {
            return -1;
        }
        for (int i = 0; i < names.length; ++i) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -2;
    }

    /** Index of the largest {width, height} pair (by area, then width), or -1 if there is none. */
    static int largest(int[][] sizes) {
        int best = -1;
        for (int i = 0; i < sizes.length; ++i) {
            if (best < 0 || compare(sizes[i], sizes[best]) > 0) {
                best = i;
            }
        }
        return best;
    }

    private static int compare(int[] a, int[] b) {
        int cmp = Long.compare((long) a[0] * a[1], (long) b[0] * b[1]);
        return cmp != 0 ? cmp : Integer.compare(a[0], b[0]);
    }

    /**
     * Index of the preview size to run 3A on while a JPEG of jpegWidth x jpegHeight is configured: the smallest size of the same aspect
     * ratio that is at least minWidth wide, else the smallest one at least minWidth wide, else the largest.
     */
    static int preview(int[][] sizes, int jpegWidth, int jpegHeight, int minWidth) {
        int sameAspect = -1;
        int wideEnough = -1;
        for (int i = 0; i < sizes.length; ++i) {
            int[] s = sizes[i];
            if (s[0] < minWidth) {
                continue;
            }
            if (wideEnough < 0 || compare(s, sizes[wideEnough]) < 0) {
                wideEnough = i;
            }
            if (sameAspect(s[0], s[1], jpegWidth, jpegHeight) && (sameAspect < 0 || compare(s, sizes[sameAspect]) < 0)) {
                sameAspect = i;
            }
        }
        if (sameAspect >= 0) {
            return sameAspect;
        }
        return wideEnough >= 0 ? wideEnough : largest(sizes);
    }

    static boolean sameAspect(int w1, int h1, int w2, int h2) {
        return Math.abs((long) w1 * h2 - (long) w2 * h1) <= 0.01 * w2 * h1;
    }

    /**
     * The LENS_FOCUS_DISTANCE to request: the requested distance clamped to [0, minFocusDistance], the same rule as scrcpy's
     * --camera-focus-distance. NaN when nothing was requested or the lens is fixed-focus (minimum focus distance 0 or unknown).
     */
    static float clampFocus(float requested, Float minFocusDistance) {
        if (Float.isNaN(requested) || minFocusDistance == null || minFocusDistance == 0) {
            return Float.NaN;
        }
        return Math.max(0f, Math.min(requested, minFocusDistance));
    }

    /**
     * Index of the AE target fps range {lower, upper} for a policy: "lowest" (lowest lower bound, then highest upper bound: the longest
     * exposures AE may use) or "fixed:N" ([N, N], else the narrowest range containing N). -1 if none matches.
     */
    static int fpsRange(int[][] ranges, String policy) {
        int best = -1;
        if ("lowest".equals(policy)) {
            for (int i = 0; i < ranges.length; ++i) {
                int[] r = ranges[i];
                if (best < 0 || r[0] < ranges[best][0] || (r[0] == ranges[best][0] && r[1] > ranges[best][1])) {
                    best = i;
                }
            }
            return best;
        }
        if (policy == null || !policy.startsWith("fixed:")) {
            return -1;
        }
        int fps;
        try {
            fps = Integer.parseInt(policy.substring("fixed:".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
        for (int i = 0; i < ranges.length; ++i) {
            int[] r = ranges[i];
            if (r[0] > fps || r[1] < fps) {
                continue;
            }
            if (best < 0) {
                best = i;
                continue;
            }
            int[] b = ranges[best];
            int width = r[1] - r[0];
            int bestWidth = b[1] - b[0];
            if (width < bestWidth || (width == bestWidth && r[0] > b[0])) {
                best = i;
            }
        }
        return best;
    }

    /** 3A has settled: lens stationary, AE and AWB converged (or locked). A null state means the HAL does not report it. */
    static boolean converged(Integer lensState, Integer aeState, Integer awbState) {
        boolean lens = lensState == null || lensState == CameraMetadata.LENS_STATE_STATIONARY;
        boolean ae = aeState == null || aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED
                || aeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED || aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED;
        boolean awb = awbState == null || awbState == CameraMetadata.CONTROL_AWB_STATE_CONVERGED
                || awbState == CameraMetadata.CONTROL_AWB_STATE_LOCKED;
        return lens && ae && awb;
    }

    /** AE and AWB report LOCKED (a null state means the HAL does not report it). */
    static boolean locked(Integer aeState, Integer awbState) {
        return (aeState == null || aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED)
                && (awbState == null || awbState == CameraMetadata.CONTROL_AWB_STATE_LOCKED);
    }

    /** A still's file name (without ".jpg"): letters, digits, '.', '_', '-'; not hidden, no path. */
    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}");
    }
}
