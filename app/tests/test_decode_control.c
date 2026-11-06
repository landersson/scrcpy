#include "common.h"

#include <assert.h>
#include <string.h>

#include "decode_control.h"

#define PARSE(S) sc_decode_control_parse(S, strlen(S), NULL, NULL)

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
    assert(PARSE("full now") == SC_DECODE_COMMAND_NONE);
    assert(PARSE("record-stop") == SC_DECODE_COMMAND_RECORD_STOP);
    assert(PARSE("record-stop \r") == SC_DECODE_COMMAND_RECORD_STOP);
    assert(PARSE("record-stop x") == SC_DECODE_COMMAND_NONE);
    assert(PARSE("record") == SC_DECODE_COMMAND_NONE); // no path
    assert(PARSE("record   ") == SC_DECODE_COMMAND_NONE);
    assert(PARSE("recording /a") == SC_DECODE_COMMAND_NONE);
}

static void
expect_record(const char *line, const char *path) {
    const char *arg = NULL;
    size_t arg_len = 0;
    enum sc_decode_command cmd =
        sc_decode_control_parse(line, strlen(line), &arg, &arg_len);
    assert(cmd == SC_DECODE_COMMAND_RECORD);
    assert(arg_len == strlen(path));
    assert(!memcmp(arg, path, arg_len));
}

static void test_parse_record(void) {
    expect_record("record /data/a.mp4", "/data/a.mp4");
    expect_record("record   /data/a.mp4 \r", "/data/a.mp4");
    expect_record("record\t/data/a.mp4", "/data/a.mp4");
    // the path is the rest of the line, spaces included
    expect_record("record /data/my turn/a.mkv", "/data/my turn/a.mkv");
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
    test_parse_record();
    test_mode_next();
    return 0;
}
