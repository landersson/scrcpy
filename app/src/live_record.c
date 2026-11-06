#include "live_record.h"

#include <assert.h>
#include <errno.h>
#include <inttypes.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <libavutil/avutil.h>

#include "control_msg.h"
#include "util/log.h"

#define DOWNCAST_VIDEO(SINK) \
    container_of(SINK, struct sc_live_record, video_packet_sink)
#define DOWNCAST_AUDIO(SINK) \
    container_of(SINK, struct sc_live_record, audio_packet_sink)

enum sc_record_format
sc_live_record_format(const char *path) {
    size_t len = strlen(path);
    if (len >= 4 && !strcmp(path + len - 4, ".mkv")) {
        return SC_RECORD_FORMAT_MKV;
    }
    return SC_RECORD_FORMAT_MP4;
}

static void
request_keyframe(struct sc_live_record *lr) {
    if (!lr->controller) {
        return;
    }
    struct sc_control_msg msg = {
        .type = SC_CONTROL_MSG_TYPE_REQUEST_KEYFRAME,
    };
    if (!sc_controller_push_msg(lr->controller, &msg)) {
        LOGW("Live record: could not request a keyframe");
    }
}

static void
on_recorder_ended(struct sc_recorder *recorder, bool success, void *userdata) {
    // the recorder logs "Recording complete to ..." or "Recording failed to ..."
    (void) recorder;
    (void) success;
    (void) userdata;
}

// Called with the mutex held
static void
push_failed(struct sc_live_record *lr, const char *what) {
    if (!lr->push_failed) {
        lr->push_failed = true;
        LOGE("Live record: the recorder refused %s packets for %s", what,
             lr->path);
    }
}

static bool
replace_packet(AVPacket **dst, const AVPacket *src) {
    if (!*dst) {
        *dst = av_packet_alloc();
        if (!*dst) {
            LOG_OOM();
            return false;
        }
    } else {
        av_packet_unref(*dst);
    }
    if (av_packet_ref(*dst, src)) {
        LOGE("Live record: could not keep a config packet");
        return false;
    }
    return true;
}

// The recorder of a recording: it opens its file when it starts, before the
// first keyframe (sc_recorder_start())
static struct sc_recorder *
create_recorder(const char *path, bool audio, enum sc_orientation orientation) {
    struct sc_recorder *rec = calloc(1, sizeof(*rec));
    if (!rec) {
        LOG_OOM();
        return NULL;
    }
    static const struct sc_recorder_callbacks cbs = {
        .on_ended = on_recorder_ended,
    };
    if (!sc_recorder_init(rec, path, sc_live_record_format(path), true, audio,
                          orientation, &cbs, NULL)) {
        free(rec);
        return NULL;
    }
    if (!sc_recorder_start(rec)) {
        sc_recorder_destroy(rec);
        free(rec);
        return NULL;
    }
    return rec;
}

// Writes what the recorder has queued and the file's trailer
static void
free_recorder(struct sc_recorder *rec) {
    sc_recorder_stop(rec);
    sc_recorder_join(rec);
    sc_recorder_destroy(rec);
    free(rec);
}

// Called with the mutex held, on the first keyframe after "record"
static bool
begin_recording(struct sc_live_record *lr, const AVPacket *keyframe) {
    assert(lr->state == SC_LIVE_RECORD_ARMED);
    struct sc_recorder *rec = lr->recorder;
    struct sc_packet_sink *vs = &rec->video_packet_sink;
    struct sc_packet_sink *as = &rec->audio_packet_sink;

    if (!vs->ops->open(vs, lr->video_ctx, &lr->session)
            || !vs->ops->push(vs, lr->video_config)
            || !vs->ops->push(vs, keyframe)) {
        return false;
    }
    if (lr->recording_audio) {
        if (lr->audio_ctx) {
            if (!as->ops->open(as, lr->audio_ctx, NULL)
                    || (lr->audio_config
                        && !as->ops->push(as, lr->audio_config))) {
                return false;
            }
        } else {
            // the audio stream ended after "record": video only
            as->ops->disable(as);
            lr->recording_audio = false;
        }
    }

    lr->first_pts = keyframe->pts;
    lr->push_failed = false;
    lr->frames = 1;
    lr->state = SC_LIVE_RECORD_RECORDING;
    // phonecap waits for this line: the file holds a frame from here on
    LOGI("Live recording started: first pts=%" PRIi64 " %s", keyframe->pts,
         lr->path);
    return true;
}

// A recording taken off the stream, ended after the mutex is released
struct sc_live_record_ending {
    struct sc_recorder *recorder; // NULL: nothing to end
    char *path;
    uint64_t frames;
    bool started; // false: no frame was recorded
};

// Called with the mutex held
static struct sc_live_record_ending
detach_recording(struct sc_live_record *lr) {
    struct sc_live_record_ending e = {
        .recorder = lr->recorder,
        .path = lr->path,
        .frames = lr->frames,
        .started = lr->state == SC_LIVE_RECORD_RECORDING,
    };
    lr->recorder = NULL;
    lr->path = NULL;
    lr->state = SC_LIVE_RECORD_IDLE;
    return e;
}

// Called without the mutex
static void
end_recording(struct sc_live_record_ending *e) {
    if (!e->recorder) {
        return;
    }
    free_recorder(e->recorder);
    if (e->started) {
        LOGI("Live recording stopped: %" PRIu64 " frames %s", e->frames,
             e->path);
    } else {
        // the file has no frame (the recorder logged "Recording failed")
        LOGI("Live recording cancelled before its first keyframe: %s",
             e->path);
        if (unlink(e->path) && errno != ENOENT) {
            LOGW("Live record: could not remove %s", e->path);
        }
    }
    free(e->path);
}

static bool
video_open(struct sc_packet_sink *sink, AVCodecContext *ctx,
           const struct sc_stream_session *session) {
    struct sc_live_record *lr = DOWNCAST_VIDEO(sink);
    sc_mutex_lock(&lr->mutex);
    lr->video_ctx = ctx;
    if (session) {
        lr->session = *session;
    }
    sc_mutex_unlock(&lr->mutex);
    return true;
}

static bool
video_push_session(struct sc_packet_sink *sink,
                   const struct sc_stream_session *session) {
    struct sc_live_record *lr = DOWNCAST_VIDEO(sink);
    sc_mutex_lock(&lr->mutex);
    lr->session = *session;
    sc_mutex_unlock(&lr->mutex);
    return true;
}

static void
video_close(struct sc_packet_sink *sink) {
    struct sc_live_record *lr = DOWNCAST_VIDEO(sink);
    sc_mutex_lock(&lr->mutex);
    struct sc_live_record_ending e = detach_recording(lr);
    lr->video_closed = true;
    lr->video_ctx = NULL;
    sc_mutex_unlock(&lr->mutex);
    end_recording(&e);
}

static bool
video_push(struct sc_packet_sink *sink, const AVPacket *packet) {
    struct sc_live_record *lr = DOWNCAST_VIDEO(sink);
    bool is_config = packet->pts == AV_NOPTS_VALUE;
    struct sc_live_record_ending failed = {0};

    if (!is_config && (packet->flags & AV_PKT_FLAG_KEY)) {
        LOGV("Live record: keyframe pts=%" PRIi64, packet->pts);
    }

    sc_mutex_lock(&lr->mutex);
    if (is_config) {
        replace_packet(&lr->video_config, packet);
    }
    if (lr->state == SC_LIVE_RECORD_RECORDING) {
        struct sc_packet_sink *vs = &lr->recorder->video_packet_sink;
        if (!vs->ops->push(vs, packet)) {
            push_failed(lr, "video");
        } else if (!is_config) {
            ++lr->frames;
        }
    } else if (lr->state == SC_LIVE_RECORD_ARMED && !is_config
            && (packet->flags & AV_PKT_FLAG_KEY) && lr->video_config) {
        if (!begin_recording(lr, packet)) {
            LOGE("Live record: could not start recording to %s", lr->path);
            failed = detach_recording(lr);
        }
    }
    sc_mutex_unlock(&lr->mutex);
    end_recording(&failed);
    // a live-record problem must never end the stream
    return true;
}

static bool
audio_open(struct sc_packet_sink *sink, AVCodecContext *ctx,
           const struct sc_stream_session *session) {
    (void) session;
    struct sc_live_record *lr = DOWNCAST_AUDIO(sink);
    sc_mutex_lock(&lr->mutex);
    lr->audio_ctx = ctx;
    sc_mutex_unlock(&lr->mutex);
    return true;
}

static void
audio_close(struct sc_packet_sink *sink) {
    struct sc_live_record *lr = DOWNCAST_AUDIO(sink);
    sc_mutex_lock(&lr->mutex);
    lr->audio_ctx = NULL;
    sc_mutex_unlock(&lr->mutex);
}

static void
audio_disable(struct sc_packet_sink *sink) {
    struct sc_live_record *lr = DOWNCAST_AUDIO(sink);
    sc_mutex_lock(&lr->mutex);
    lr->audio_disabled = true;
    sc_mutex_unlock(&lr->mutex);
}

static bool
audio_push(struct sc_packet_sink *sink, const AVPacket *packet) {
    struct sc_live_record *lr = DOWNCAST_AUDIO(sink);
    bool is_config = packet->pts == AV_NOPTS_VALUE;

    sc_mutex_lock(&lr->mutex);
    if (is_config) {
        replace_packet(&lr->audio_config, packet);
    }
    // audio from before the recording's first video frame is dropped, so the
    // file starts on its keyframe at t = 0
    if (lr->state == SC_LIVE_RECORD_RECORDING && !is_config
            && lr->recording_audio && packet->pts >= lr->first_pts) {
        struct sc_packet_sink *as = &lr->recorder->audio_packet_sink;
        if (!as->ops->push(as, packet)) {
            push_failed(lr, "audio");
        }
    }
    sc_mutex_unlock(&lr->mutex);
    return true;
}

static int
run_requester(void *data) {
    struct sc_live_record *lr = data;
    sc_mutex_lock(&lr->mutex);
    while (!lr->stopped) {
        sc_tick deadline = sc_tick_now() + lr->keyframe_request_interval;
        sc_cond_timedwait(&lr->cond, &lr->mutex, deadline);
        if (lr->stopped) {
            break;
        }
        bool request = lr->dormant && lr->state == SC_LIVE_RECORD_IDLE
                    && sc_tick_now() >= deadline;
        if (request) {
            sc_mutex_unlock(&lr->mutex);
            request_keyframe(lr);
            sc_mutex_lock(&lr->mutex);
        }
    }
    sc_mutex_unlock(&lr->mutex);
    return 0;
}

bool
sc_live_record_init(struct sc_live_record *lr, bool video, bool audio,
                    enum sc_orientation orientation,
                    sc_tick keyframe_request_interval) {
    memset(lr, 0, sizeof(*lr));
    if (!video) {
        LOGE("Live record needs the video stream");
        return false;
    }
    if (!sc_mutex_init(&lr->mutex)) {
        return false;
    }
    if (!sc_cond_init(&lr->cond)) {
        sc_mutex_destroy(&lr->mutex);
        return false;
    }
    lr->video = video;
    lr->audio = audio;
    lr->orientation = orientation;
    lr->keyframe_request_interval = keyframe_request_interval;
    lr->state = SC_LIVE_RECORD_IDLE;

    static const struct sc_packet_sink_ops video_ops = {
        .open = video_open,
        .close = video_close,
        .push = video_push,
        .push_session = video_push_session,
    };
    static const struct sc_packet_sink_ops audio_ops = {
        .open = audio_open,
        .close = audio_close,
        .push = audio_push,
        .disable = audio_disable,
    };
    lr->video_packet_sink.ops = &video_ops;
    lr->audio_packet_sink.ops = &audio_ops;
    return true;
}

void
sc_live_record_set_controller(struct sc_live_record *lr,
                              struct sc_controller *controller) {
    lr->controller = controller;
}

bool
sc_live_record_start_requester(struct sc_live_record *lr) {
    if (!lr->controller || !lr->keyframe_request_interval) {
        return true; // nothing to request periodically
    }
    bool ok = sc_thread_create(&lr->requester, run_requester,
                               "scrcpy-keyframes", lr);
    if (!ok) {
        LOGE("Live record: could not start the keyframe requester");
        return false;
    }
    lr->requester_started = true;
    return true;
}

void
sc_live_record_record(struct sc_live_record *lr, const char *path,
                      size_t len) {
    char *p = strndup(path, len);
    if (!p) {
        LOG_OOM();
        return;
    }
    // only this (stdin) thread arms a recording; stop and the stream's end
    // may come in between, so the checks are made again once it is created
    sc_mutex_lock(&lr->mutex);
    if (lr->video_closed || lr->stopped) {
        sc_mutex_unlock(&lr->mutex);
        LOGW("Live record: the stream has ended, not recording %s", p);
        free(p);
        return;
    }
    if (lr->state != SC_LIVE_RECORD_IDLE) {
        sc_mutex_unlock(&lr->mutex);
        LOGW("Live record: already recording %s, ignoring %s", lr->path, p);
        free(p);
        return;
    }
    bool audio = lr->audio && !lr->audio_disabled && lr->audio_ctx;
    sc_mutex_unlock(&lr->mutex);

    // opens the file now, off the stream's thread
    struct sc_recorder *rec = create_recorder(p, audio, lr->orientation);
    if (!rec) {
        LOGE("Live record: could not record to %s", p);
        free(p);
        return;
    }

    sc_mutex_lock(&lr->mutex);
    if (lr->video_closed || lr->stopped) {
        sc_mutex_unlock(&lr->mutex);
        struct sc_live_record_ending e = {.recorder = rec, .path = p};
        end_recording(&e);
        return;
    }
    lr->recorder = rec;
    lr->path = p;
    lr->recording_audio = audio;
    lr->state = SC_LIVE_RECORD_ARMED;
    sc_mutex_unlock(&lr->mutex);
    LOGI("Live recording armed: %s", p);
    request_keyframe(lr);
}

void
sc_live_record_stop_recording(struct sc_live_record *lr) {
    sc_mutex_lock(&lr->mutex);
    struct sc_live_record_ending e = detach_recording(lr);
    sc_mutex_unlock(&lr->mutex);
    if (!e.recorder) {
        LOGD("Live record: nothing to stop");
    }
    end_recording(&e);
}

void
sc_live_record_set_dormant(struct sc_live_record *lr, bool dormant) {
    sc_mutex_lock(&lr->mutex);
    bool was = lr->dormant;
    lr->dormant = dormant;
    sc_cond_signal(&lr->cond);
    sc_mutex_unlock(&lr->mutex);
    if (was && !dormant) {
        // full decoding resumes at the next keyframe: ask for one now
        request_keyframe(lr);
    }
}

void
sc_live_record_stop(struct sc_live_record *lr) {
    sc_mutex_lock(&lr->mutex);
    // no new recording from here on (the stdin thread may still be running)
    lr->stopped = true;
    sc_cond_signal(&lr->cond);
    struct sc_live_record_ending e = detach_recording(lr);
    sc_mutex_unlock(&lr->mutex);
    end_recording(&e);
}

void
sc_live_record_join(struct sc_live_record *lr) {
    if (lr->requester_started) {
        sc_thread_join(&lr->requester, NULL);
    }
}

void
sc_live_record_destroy(struct sc_live_record *lr) {
    assert(lr->state == SC_LIVE_RECORD_IDLE);
    av_packet_free(&lr->video_config);
    av_packet_free(&lr->audio_config);
    sc_cond_destroy(&lr->cond);
    sc_mutex_destroy(&lr->mutex);
}
