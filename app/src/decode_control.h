#ifndef SC_DECODE_CONTROL_H
#define SC_DECODE_CONTROL_H

#include "common.h"

#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <string.h>

#include "util/thread.h"

// Commands read from stdin (--stdin-control), one per line:
// "keyframes" decodes only the keyframes, "full" decodes every frame again.
// The stream itself is not affected: every packet still reaches the decoder
// (and the recorder, if any); in keyframes mode the decoder skips the others.
// With --live-record, "record <path>" records the running stream to <path>
// from its next keyframe and "record-stop" finalizes the file (live_record.h).

enum sc_decode_command {
    SC_DECODE_COMMAND_NONE,
    SC_DECODE_COMMAND_KEYFRAMES,
    SC_DECODE_COMMAND_FULL,
    SC_DECODE_COMMAND_RECORD,      // the path is the argument
    SC_DECODE_COMMAND_RECORD_STOP,
};

struct sc_live_record;

struct sc_decode_control {
    sc_thread thread;
    atomic_bool want_keyframes; // written by the stdin thread, read per packet
    atomic_bool stopped;
    struct sc_live_record *live_record; // NULL without --live-record
};

static inline bool
sc_decode_control_is_space(char c) {
    return c == ' ' || c == '\t' || c == '\r';
}

static inline bool
sc_decode_control_word(const char *line, size_t len, const char *word) {
    return len == strlen(word) && !memcmp(line, word, len);
}

// One line, without its newline; trailing spaces and '\r' are ignored. The
// argument (a path, which may contain spaces) is the rest of the line after
// the command and its spaces; arg and arg_len may be NULL.
static inline enum sc_decode_command
sc_decode_control_parse(const char *line, size_t len, const char **arg,
                        size_t *arg_len) {
    while (len && sc_decode_control_is_space(line[len - 1])) {
        --len;
    }
    size_t cmd_len = 0;
    while (cmd_len < len && !sc_decode_control_is_space(line[cmd_len])) {
        ++cmd_len;
    }
    size_t a = cmd_len;
    while (a < len && sc_decode_control_is_space(line[a])) {
        ++a;
    }
    if (arg) {
        *arg = line + a;
    }
    if (arg_len) {
        *arg_len = len - a;
    }
    bool has_arg = a < len;

    if (sc_decode_control_word(line, cmd_len, "record")) {
        return has_arg ? SC_DECODE_COMMAND_RECORD : SC_DECODE_COMMAND_NONE;
    }
    if (has_arg || cmd_len != len) {
        return SC_DECODE_COMMAND_NONE;
    }
    if (sc_decode_control_word(line, len, "keyframes")) {
        return SC_DECODE_COMMAND_KEYFRAMES;
    }
    if (sc_decode_control_word(line, len, "full")) {
        return SC_DECODE_COMMAND_FULL;
    }
    if (sc_decode_control_word(line, len, "record-stop")) {
        return SC_DECODE_COMMAND_RECORD_STOP;
    }
    return SC_DECODE_COMMAND_NONE;
}

// The decoder's mode for the next packet: keyframes only as soon as it is
// wanted; full again only from a keyframe, because the frames after skipped
// ones refer to frames that were never decoded
static inline bool
sc_decode_mode_next(bool keyframes_only, bool want_keyframes, bool is_key) {
    if (want_keyframes) {
        return true;
    }
    return keyframes_only && !is_key;
}

bool
sc_decode_control_start(struct sc_decode_control *dc);

void
sc_decode_control_stop(struct sc_decode_control *dc);

void
sc_decode_control_join(struct sc_decode_control *dc);

#endif
