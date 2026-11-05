package com.genymobile.scrcpy.video;

import java.util.Locale;

/**
 * Camera processing controls for --camera-controls: a comma-separated list of key=value.
 * <ul>
 * <li>{@code nr=off|fast|hq|minimal|zsl} - NOISE_REDUCTION_MODE</li>
 * <li>{@code edge=off|fast|hq|zsl} - EDGE_MODE (sharpening)</li>
 * <li>{@code exposure=<µs>} and {@code iso=<n>} - manual exposure: AE off, the sensor exposure
 * time and sensitivity fixed, the frame duration 1/fps (both must be given)</li>
 * <li>{@code tonemap-gamma=<g>} - TONEMAP_MODE_CONTRAST_CURVE with out = in^(1/g) on every
 * channel</li>
 * <li>{@code log=all} - log every capture result (TSDBG camera-result), not only the first few</li>
 * </ul>
 * Unset keys leave the capture template's own values.
 */
public final class CameraControls {

    public Integer noiseReduction;
    public Integer edge;
    public long exposureUs;
    public int iso;
    public float tonemapGamma;
    public boolean logAll;

    public static CameraControls parse(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        CameraControls c = new CameraControls();
        for (String item : s.split(",")) {
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Invalid camera control (key=value): " + item);
            }
            String key = item.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = item.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            switch (key) {
                case "nr":
                    c.noiseReduction = parseNoiseReduction(value);
                    break;
                case "edge":
                    c.edge = parseEdge(value);
                    break;
                case "exposure":
                    c.exposureUs = Long.parseLong(value);
                    break;
                case "iso":
                    c.iso = Integer.parseInt(value);
                    break;
                case "tonemap-gamma":
                    c.tonemapGamma = Float.parseFloat(value);
                    break;
                case "log":
                    if (!"all".equals(value)) {
                        throw new IllegalArgumentException("Invalid camera control log: " + value + " (all)");
                    }
                    c.logAll = true;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown camera control: " + key);
            }
        }
        if ((c.exposureUs > 0) != (c.iso > 0)) {
            throw new IllegalArgumentException("Camera controls exposure and iso go together");
        }
        return c;
    }

    public boolean manualExposure() {
        return exposureUs > 0 && iso > 0;
    }

    private static int parseNoiseReduction(String v) {
        switch (v) {
            case "off":
                return 0; // NOISE_REDUCTION_MODE_OFF
            case "fast":
                return 1;
            case "hq":
                return 2;
            case "minimal":
                return 3;
            case "zsl":
                return 4;
            default:
                throw new IllegalArgumentException("Invalid camera control nr: " + v);
        }
    }

    private static int parseEdge(String v) {
        switch (v) {
            case "off":
                return 0; // EDGE_MODE_OFF
            case "fast":
                return 1;
            case "hq":
                return 2;
            case "zsl":
                return 3;
            default:
                throw new IllegalArgumentException("Invalid camera control edge: " + v);
        }
    }
}
