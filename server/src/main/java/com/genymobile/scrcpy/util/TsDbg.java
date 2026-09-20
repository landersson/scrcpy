package com.genymobile.scrcpy.util;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Timestamp instrumentation.
 * <p>
 * A recording made by scrcpy can have its video and audio tracks disagree about when they
 * started (measured: 0.43 s on a Galaxy S26, 0.01 s on a Pixel 10 Pro), while the same phone's
 * own camera app puts them within 6 ms. The two streams are captured and stamped independently,
 * so this logs the raw value at every point a timestamp is produced or passed on, next to both
 * of the system clocks read at that same moment.
 * <p>
 * Every line starts with TSDBG and every time is in microseconds:
 * <ul>
 * <li>{@code mono} - System.nanoTime(), i.e. CLOCK_MONOTONIC, which audio timestamps use;</li>
 * <li>{@code boot} - SystemClock.elapsedRealtimeNanos(), i.e. CLOCK_BOOTTIME, which counts
 * suspended time and is what a camera declaring SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME should
 * be using.</li>
 * </ul>
 * Only the first few events of each kind are logged: the disagreement is set up at the start of
 * a recording, and a line per frame would change the timing it is trying to measure.
 */
public final class TsDbg {

    public static final int AUDIO_READ = 0;
    public static final int VIDEO_PACKET = 1;
    public static final int CAMERA_FRAME = 2;
    public static final int GL_FRAME = 3;
    public static final int CAMERA_RESULT = 4;

    private static final int KINDS = 5;
    private static final int MAX_EVENTS = 12;

    private static final AtomicIntegerArray COUNTS = new AtomicIntegerArray(KINDS);

    private TsDbg() {
        // not instantiable
    }

    /**
     * The sequence number of this event, or -1 once enough of its kind have been logged.
     */
    public static int next(int kind) {
        int n = COUNTS.getAndIncrement(kind);
        return n < MAX_EVENTS ? n : -1;
    }

    public static void log(String what, String details) {
        // read both clocks as close together as possible
        long mono = System.nanoTime() / 1000;
        long boot = SystemClock.elapsedRealtimeNanos() / 1000;
        Ln.i("TSDBG " + what + " mono=" + mono + " boot=" + boot + " " + details);
    }

    public static void log(String what) {
        log(what, "");
    }
}
