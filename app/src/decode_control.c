#include "decode_control.h"

#include <errno.h>
#include <poll.h>
#include <unistd.h>

#include "control_msg.h"
#include "controller.h"
#include "live_record.h"
#include "util/log.h"

// long enough for a recording path
#define SC_DECODE_CONTROL_LINE_MAX 4096
#define SC_DECODE_CONTROL_POLL_MS 200

static void
handle_line(struct sc_decode_control *dc, const char *line, size_t len) {
    const char *arg;
    size_t arg_len;
    enum sc_decode_command cmd =
        sc_decode_control_parse(line, len, &arg, &arg_len);
    bool record_cmd = cmd == SC_DECODE_COMMAND_RECORD
                   || cmd == SC_DECODE_COMMAND_RECORD_STOP;
    if (record_cmd && !dc->live_record) {
        LOGW("Decode control: '%.*s' needs --live-record", (int) len, line);
        return;
    }
    switch (cmd) {
        case SC_DECODE_COMMAND_KEYFRAMES:
            atomic_store(&dc->want_keyframes, true);
            if (dc->live_record) {
                sc_live_record_set_dormant(dc->live_record, true);
            }
            break;
        case SC_DECODE_COMMAND_FULL:
            atomic_store(&dc->want_keyframes, false);
            if (dc->live_record) {
                sc_live_record_set_dormant(dc->live_record, false);
            }
            break;
        case SC_DECODE_COMMAND_RECORD:
            sc_live_record_record(dc->live_record, arg, arg_len);
            break;
        case SC_DECODE_COMMAND_RECORD_STOP:
            sc_live_record_stop_recording(dc->live_record);
            break;
        case SC_DECODE_COMMAND_FOCUS: {
            struct sc_control_msg msg = {
                .type = SC_CONTROL_MSG_TYPE_CAMERA_SET_FOCUS,
            };
            if (!dc->controller) {
                LOGW("Decode control: 'focus' needs control (no --no-control)");
            } else if (!sc_decode_control_parse_focus(
                    arg, arg_len, &msg.camera_set_focus.diopters,
                    &msg.camera_set_focus.id)) {
                LOGW("Decode control: bad focus command '%.*s' (want 'focus "
                     "<diopters> <id>')", (int) len, line);
            } else if (!sc_controller_push_msg(dc->controller, &msg)) {
                LOGW("Decode control: could not send the focus request");
            }
            break;
        }
        default:
            LOGW("Decode control: unknown command '%.*s'", (int) len, line);
    }
}

static int
run_decode_control(void *data) {
    struct sc_decode_control *dc = data;

    char line[SC_DECODE_CONTROL_LINE_MAX];
    size_t len = 0;
    bool overlong = false; // an overlong line is ignored (it may be a path)

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
            if (buf[i] == '\n') {
                if (overlong) {
                    LOGW("Decode control: ignoring a line longer than %d "
                         "bytes", SC_DECODE_CONTROL_LINE_MAX);
                } else {
                    handle_line(dc, line, len);
                }
                len = 0;
                overlong = false;
            } else if (len < sizeof(line)) {
                line[len++] = buf[i];
            } else {
                overlong = true;
            }
        }
    }

    return 0;
}

bool
sc_decode_control_start(struct sc_decode_control *dc) {
    // dc->live_record is set by the caller
    atomic_init(&dc->want_keyframes, false);
    atomic_init(&dc->stopped, false);
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
