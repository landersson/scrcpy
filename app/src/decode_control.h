#ifndef SC_DECODE_CONTROL_H
#define SC_DECODE_CONTROL_H

#include "common.h"

#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <string.h>

#include "util/thread.h"

// Decode mode commands read from stdin (--stdin-control), one per line:
// "keyframes" decodes only the keyframes, "full" decodes every frame again.
// The stream itself is not affected: every packet still reaches the decoder
// (and the recorder, if any); in keyframes mode the decoder skips the others.

enum sc_decode_command {
    SC_DECODE_COMMAND_NONE,
    SC_DECODE_COMMAND_KEYFRAMES,
    SC_DECODE_COMMAND_FULL,
};

struct sc_decode_control {
    sc_thread thread;
    atomic_bool want_keyframes; // written by the stdin thread, read per packet
    atomic_bool stopped;
};

// One line, without its newline; trailing spaces and '\r' are ignored
static inline enum sc_decode_command
sc_decode_control_parse(const char *line, size_t len) {
    while (len && (line[len - 1] == ' ' || line[len - 1] == '\t'
                   || line[len - 1] == '\r')) {
        --len;
    }
    if (len == strlen("keyframes") && !memcmp(line, "keyframes", len)) {
        return SC_DECODE_COMMAND_KEYFRAMES;
    }
    if (len == strlen("full") && !memcmp(line, "full", len)) {
        return SC_DECODE_COMMAND_FULL;
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
