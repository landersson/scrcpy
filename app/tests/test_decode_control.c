#include "common.h"

#include <assert.h>
#include <string.h>

#include "decode_control.h"

#define PARSE(S) sc_decode_control_parse(S, strlen(S))

static void test_parse(void) {
    assert(PARSE("keyframes") == SC_DECODE_COMMAND_KEYFRAMES);
    assert(PARSE("full") == SC_DECODE_COMMAND_FULL);
    assert(PARSE("full\r") == SC_DECODE_COMMAND_FULL);
    assert(PARSE("keyframes  ") == SC_DECODE_COMMAND_KEYFRAMES);
    assert(PARSE("") == SC_DECODE_COMMAND_NONE);
    assert(PARSE("ful") == SC_DECODE_COMMAND_NONE);
    assert(PARSE("fully") == SC_DECODE_COMMAND_NONE);
    assert(PARSE(" full") == SC_DECODE_COMMAND_NONE);
    assert(PARSE("KEYFRAMES") == SC_DECODE_COMMAND_NONE);
}

static void test_mode_next(void) {
    // keyframes only as soon as it is wanted, on any packet
    assert(sc_decode_mode_next(false, true, false));
    assert(sc_decode_mode_next(false, true, true));
    assert(sc_decode_mode_next(true, true, false));
    assert(sc_decode_mode_next(true, true, true));
    // full again only from a keyframe
    assert(sc_decode_mode_next(true, false, false));
    assert(!sc_decode_mode_next(true, false, true));
    // full stays full
    assert(!sc_decode_mode_next(false, false, false));
    assert(!sc_decode_mode_next(false, false, true));
}

int main(int argc, char *argv[]) {
    (void) argc;
    (void) argv;

    test_parse();
    test_mode_next();
    return 0;
}
