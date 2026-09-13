package com.genymobile.scrcpy.still;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;

/**
 * The still server's output: one JSON object per line on stdout. Anything else on stdout (framework or scrcpy log lines) is ignored by the
 * host, which only parses lines starting with '{'.
 */
final class Protocol {

    private static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), false);

    private Protocol() {
        // not instantiable
    }

    static synchronized void send(JSONObject message) {
        String line = message.toString(); // null if a value cannot be encoded
        if (line == null) {
            line = "{\"event\":\"error\",\"code\":\"internal\",\"message\":\"unencodable message\",\"fatal\":false}";
        }
        OUT.print(line + '\n');
        OUT.flush();
    }

    static JSONObject event(String name, Integer id) {
        JSONObject o = new JSONObject();
        put(o, "event", name);
        if (id != null) {
            put(o, "id", id);
        }
        return o;
    }

    /** Put a value; null and non-finite numbers become JSON null. */
    static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, encodable(value));
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    static JSONArray array(Object... values) {
        JSONArray a = new JSONArray();
        for (Object value : values) {
            a.put(encodable(value));
        }
        return a;
    }

    private static Object encodable(Object value) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return JSONObject.NULL;
            }
        }
        return value;
    }

    static void error(Integer id, String code, String message, boolean fatal) {
        JSONObject o = event("error", id);
        put(o, "code", code);
        put(o, "message", message);
        put(o, "fatal", fatal);
        send(o);
    }

    static void log(String level, String message) {
        JSONObject o = event("log", null);
        put(o, "level", level);
        put(o, "message", message);
        send(o);
    }
}
