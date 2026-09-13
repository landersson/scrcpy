package com.genymobile.scrcpy.still;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Native Camera2 stills for phonecap, run as the shell user like the scrcpy server itself:
 * <pre>
 * CLASSPATH=/data/local/tmp/phonecap-still-server.jar app_process / com.genymobile.scrcpy.still.StillServer probe [cameraId]
 * CLASSPATH=/data/local/tmp/phonecap-still-server.jar app_process / com.genymobile.scrcpy.still.StillServer serve [--idle-timeout-s N]
 * </pre>
 * "probe" prints one {"event":"camera"} line per camera and exits; it opens no camera. "serve" prints {"event":"hello"}, then reads one
 * JSON command per line on stdin (open, shoot, ping, close) and answers each with one JSON line on stdout (ready, shot, pong, closed or
 * error) carrying the command's id. Stdin EOF closes the camera and exits; so does a long idle period.
 */
public final class StillServer {

    static final int PROTOCOL = 1;
    private static final int DEFAULT_IDLE_TIMEOUT_S = 300;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_IDLE = 3;

    private static volatile long lastActivity;
    private static volatile boolean busy;

    private StillServer() {
        // not instantiable
    }

    public static void main(String... args) {
        int status = 0;
        try {
            status = run(args);
        } catch (Throwable t) {
            Protocol.error(null, "internal", String.valueOf(t), true);
            status = 1;
        } finally {
            // the Android SDK may leave non-daemon threads running
            System.exit(status);
        }
    }

    private static int run(String... args) throws Exception {
        dropRootPrivileges();
        prepareMainLooper();
        Ln.disableSystemStreams();
        Ln.initLogLevel(Ln.Level.WARN);
        if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
            Protocol.error(null, "config_unsupported", "the still server needs Android 10 or later", true);
            return EXIT_USAGE;
        }
        Workarounds.apply();

        String mode = args.length > 0 ? args[0] : "serve";
        switch (mode) {
            case "probe":
                return probe(args.length > 1 ? args[1] : null);
            case "serve":
                return serve(idleTimeoutS(args));
            default:
                Protocol.error(null, "bad_command", "usage: StillServer probe [cameraId] | serve [--idle-timeout-s N]", true);
                return EXIT_USAGE;
        }
    }

    private static int probe(String cameraId) throws Exception {
        CameraManager manager = ServiceManager.getCameraManager();
        String[] ids = cameraId != null ? new String[] {cameraId} : manager.getCameraIdList();
        for (String id : ids) {
            try {
                Protocol.send(CameraProbe.describe(id, manager.getCameraCharacteristics(id)));
            } catch (IllegalArgumentException e) {
                Protocol.error(null, "config_unsupported", "camera " + id + " not found", true);
                return EXIT_USAGE;
            }
        }
        return 0;
    }

    private static int serve(int idleTimeoutS) throws IOException {
        JSONObject hello = Protocol.event("hello", null);
        Protocol.put(hello, "protocol", PROTOCOL);
        Protocol.put(hello, "sdk", Build.VERSION.SDK_INT);
        Protocol.put(hello, "release", Build.VERSION.RELEASE);
        Protocol.put(hello, "manufacturer", Build.MANUFACTURER);
        Protocol.put(hello, "model", Build.MODEL);
        Protocol.put(hello, "pid", Process.myPid());
        Protocol.send(hello);

        lastActivity = SystemClock.elapsedRealtime();
        if (idleTimeoutS > 0) {
            startIdleWatchdog(idleTimeoutS);
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StillSession session = null;
        try {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                busy = true;
                try {
                    session = handle(line, session);
                } finally {
                    busy = false;
                    lastActivity = SystemClock.elapsedRealtime();
                }
            }
        } finally {
            if (session != null) {
                session.close();
            }
        }
        return 0;
    }

    /** Run one command; returns the session that is open afterwards (null if none). */
    private static StillSession handle(String line, StillSession session) {
        Integer id = null;
        try {
            JSONObject command = new JSONObject(line);
            id = command.has("id") && !command.isNull("id") ? command.getInt("id") : null;
            String cmd = command.optString("cmd");
            switch (cmd) {
                case "ping":
                    Protocol.send(Protocol.event("pong", id));
                    return session;
                case "open":
                    if (session != null) {
                        session.close();
                        session = null;
                    }
                    StillSession opened = new StillSession(StillSession.Options.fromJson(command));
                    JSONObject ready = opened.open();
                    Protocol.put(ready, "id", id);
                    Protocol.send(ready);
                    return opened;
                case "shoot":
                    if (session == null) {
                        throw new StillException("bad_command", "no camera is open (send open first)");
                    }
                    JSONObject shot = session.shoot(command.getString("name"));
                    Protocol.put(shot, "id", id);
                    Protocol.send(shot);
                    return session;
                case "close":
                    if (session != null) {
                        session.close();
                    }
                    Protocol.send(Protocol.event("closed", id));
                    return null;
                default:
                    throw new StillException("bad_command", "unknown command: " + cmd);
            }
        } catch (StillException e) {
            Protocol.error(id, e.getCode(), e.getMessage(), false);
            if ("camera_disconnected".equals(e.getCode()) && session != null) {
                session.close();
                return null;
            }
            return session;
        } catch (JSONException e) {
            Protocol.error(id, "bad_command", "bad command: " + e.getMessage(), false);
            return session;
        } catch (RuntimeException e) {
            Protocol.error(id, "internal", String.valueOf(e), false);
            return session;
        }
    }

    private static void startIdleWatchdog(int idleTimeoutS) {
        Thread watchdog = new Thread(() -> {
            while (true) {
                SystemClock.sleep(1000);
                if (!busy && SystemClock.elapsedRealtime() - lastActivity > idleTimeoutS * 1000L) {
                    Protocol.log("warn", "no command for " + idleTimeoutS + " s; exiting");
                    System.exit(EXIT_IDLE); // the camera service releases the camera when the process dies
                }
            }
        }, "still-idle");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static int idleTimeoutS(String... args) {
        for (int i = 1; i < args.length - 1; ++i) {
            if ("--idle-timeout-s".equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    break;
                }
            }
        }
        return DEFAULT_IDLE_TIMEOUT_S;
    }

    private static void prepareMainLooper() {
        // Like Looper.prepareMainLooper(), but with quitAllowed set to true (as Server does)
        Looper.prepare();
        synchronized (Looper.class) {
            try {
                @SuppressLint("DiscouragedPrivateApi")
                Field field = Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, Looper.myLooper());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static void dropRootPrivileges() {
        try {
            if (Os.getuid() == 0) {
                Os.setuid(2000);
            }
        } catch (Exception e) {
            Protocol.log("warn", "cannot set UID: " + e);
        }
    }
}
