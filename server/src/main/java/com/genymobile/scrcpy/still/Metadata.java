package com.genymobile.scrcpy.still;

import com.genymobile.scrcpy.AndroidVersions;

import android.annotation.TargetApi;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Camera metadata (characteristics, capture results) as JSON, every key the framework knows including vendor tags: what the host needs
 * to develop a RAW frame on the PC (black/white levels, colour matrices, shading map, crop, sensor mode...).
 */
@TargetApi(AndroidVersions.API_29_ANDROID_10)
final class Metadata {

    private Metadata() {
        // not instantiable
    }

    static JSONObject characteristics(CameraCharacteristics c) {
        JSONObject o = new JSONObject();
        for (CameraCharacteristics.Key<?> key : c.getKeys()) {
            Protocol.put(o, key.getName(), encode(c.get(key)));
        }
        return o;
    }

    static JSONObject result(CaptureResult r) {
        JSONObject o = new JSONObject();
        for (CaptureResult.Key<?> key : r.getKeys()) {
            Protocol.put(o, key.getName(), encode(r.get(key)));
        }
        return o;
    }

    static JSONObject request(CaptureRequest r) {
        JSONObject o = new JSONObject();
        for (CaptureRequest.Key<?> key : r.getKeys()) {
            Protocol.put(o, key.getName(), encode(r.get(key)));
        }
        return o;
    }

    /** The logical result plus the per-physical-camera results of a total result. */
    static JSONObject total(TotalCaptureResult r) {
        JSONObject o = new JSONObject();
        Protocol.put(o, "result", result(r));
        JSONObject physical = new JSONObject();
        for (Map.Entry<String, CaptureResult> e : r.getPhysicalCameraResults().entrySet()) {
            Protocol.put(physical, e.getKey(), result(e.getValue()));
        }
        Protocol.put(o, "physical_results", physical);
        Protocol.put(o, "request", request(r.getRequest()));
        return o;
    }

    static void write(File file, JSONObject o) throws IOException {
        File part = part(file);
        try (Writer out = new OutputStreamWriter(new FileOutputStream(part), StandardCharsets.UTF_8)) {
            out.write(o.toString());
        }
        commit(part, file);
    }

    /** Atomic file writes: write to part(file), then commit(part, file). */
    static File part(File file) {
        return new File(file.getPath() + ".part");
    }

    static void commit(File part, File file) throws IOException {
        if (!part.renameTo(file)) {
            throw new IOException("cannot rename " + part + " to " + file.getName());
        }
    }

    static Object encode(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Rational) {
            return ((Rational) v).doubleValue(); // a Number whose toString() is "n/d", which is not JSON
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof String) {
            return v;
        }
        if (v instanceof Rect) {
            Rect r = (Rect) v;
            return Protocol.array(r.left, r.top, r.width(), r.height());
        }
        if (v instanceof Size) {
            return CameraProbe.size((Size) v);
        }
        if (v instanceof SizeF) {
            return Protocol.array(((SizeF) v).getWidth(), ((SizeF) v).getHeight());
        }
        if (v instanceof Range) {
            Range<?> r = (Range<?>) v;
            return Protocol.array(r.getLower(), r.getUpper());
        }
        if (v instanceof RggbChannelVector) {
            RggbChannelVector g = (RggbChannelVector) v;
            return Protocol.array(g.getRed(), g.getGreenEven(), g.getGreenOdd(), g.getBlue());
        }
        if (v instanceof BlackLevelPattern) {
            int[] levels = new int[4];
            ((BlackLevelPattern) v).copyTo(levels, 0);
            return encode(levels);
        }
        if (v instanceof ColorSpaceTransform) {
            ColorSpaceTransform t = (ColorSpaceTransform) v;
            JSONArray rows = new JSONArray();
            for (int r = 0; r < 3; ++r) {
                rows.put(Protocol.array(t.getElement(0, r).doubleValue(), t.getElement(1, r).doubleValue(), t.getElement(2, r).doubleValue()));
            }
            return rows;
        }
        if (v instanceof LensShadingMap) {
            LensShadingMap m = (LensShadingMap) v;
            float[] gains = new float[m.getGainFactorCount()];
            m.copyGainFactors(gains, 0);
            JSONObject o = new JSONObject();
            Protocol.put(o, "rows", m.getRowCount());
            Protocol.put(o, "columns", m.getColumnCount());
            Protocol.put(o, "gains", encode(gains)); // RGGB interleaved, row-major
            return o;
        }
        if (v instanceof MeteringRectangle) {
            MeteringRectangle m = (MeteringRectangle) v;
            return Protocol.array(m.getX(), m.getY(), m.getWidth(), m.getHeight(), m.getMeteringWeight());
        }
        if (v instanceof StreamConfigurationMap) {
            return "<stream configuration map>";
        }
        if (v.getClass().isArray()) {
            int n = Array.getLength(v);
            JSONArray a = new JSONArray();
            for (int i = 0; i < n; ++i) {
                a.put(encode(Array.get(v, i)));
            }
            return a;
        }
        return v.toString();
    }
}
