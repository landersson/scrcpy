#include "decode_control.h"

#include <errno.h>
#include <poll.h>
#include <unistd.h>

#include "util/log.h"

#define SC_DECODE_CONTROL_LINE_MAX 256
#define SC_DECODE_CONTROL_POLL_MS 200

void
sc_decode_control_init(struct sc_decode_control *dc) {
    atomic_init(&dc->want_keyframes, false);
    atomic_init(&dc->stopped, false);
}

static void
handle_line(struct sc_decode_control *dc, const char *line, size_t len) {
    switch (sc_decode_control_parse(line, len)) {
        case SC_DECODE_COMMAND_KEYFRAMES:
            atomic_store(&dc->want_keyframes, true);
            break;
        case SC_DECODE_COMMAND_FULL:
            atomic_store(&dc->want_keyframes, false);
            break;
        default:
            LOGW("Decode control: unknown command '%.*s'", (int) len, line);
    }
}

static int
run_decode_control(void *data) {
    struct sc_decode_control *dc = data;

    char line[SC_DECODE_CONTROL_LINE_MAX];
    size_t len = 0;
    bool overlong = false; // drop the rest of a line that did not fit

    while (!atomic_load(&dc->stopped)) {
        struct pollfd pfd = {.fd = STDIN_FILENO, .events = POLLIN};
        int r = poll(&pfd, 1, SC_DECODE_CONTROL_POLL_MS);
        if (r < 0) {
            if (errno == EINTR) {
                continue;
            }
            LOGW("Decode control: poll failed (%d)", errno);
            break;
        }
        if (r == 0) {
            continue;
        }

        char buf[SC_DECODE_CONTROL_LINE_MAX];
        ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }
            LOGW("Decode control: read failed (%d)", errno);
            break;
        }
        if (n == 0) {
            // stdin closed: keep the current mode
            LOGD("Decode control: end of input");
            break;
        }

        for (ssize_t i = 0; i < n; ++i) {
            char c = buf[i];
            if (c == '\n') {
                if (!overlong) {
                    handle_line(dc, line, len);
                }
                len = 0;
                overlong = false;
            } else if (len < sizeof(line)) {
                line[len++] = c;
            } else {
                overlong = true;
            }
        }
    }

    return 0;
}

bool
sc_decode_control_start(struct sc_decode_control *dc) {
    bool ok = sc_thread_create(&dc->thread, run_decode_control, "scrcpy-stdin",
                               dc);
    if (!ok) {
        LOGE("Decode control: could not start thread");
        return false;
    }
    return true;
}

void
sc_decode_control_stop(struct sc_decode_control *dc) {
    atomic_store(&dc->stopped, true);
}

void
sc_decode_control_join(struct sc_decode_control *dc) {
    sc_thread_join(&dc->thread, NULL);
}
