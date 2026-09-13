package com.genymobile.scrcpy.still;

/** A command failure reported to the host as {"event":"error","code":...}. */
final class StillException extends Exception {

    private final String code;

    StillException(String code, String message) {
        this(code, message, null);
    }

    StillException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    String getCode() {
        return code;
    }
}
