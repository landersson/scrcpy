#ifndef SC_LIVE_RECORD_H
#define SC_LIVE_RECORD_H

#include "common.h"

#include <stdbool.h>
#include <stdint.h>
#include <libavcodec/avcodec.h>

#include "controller.h"
#include "options.h"
#include "recorder.h"
#include "trait/packet_sink.h"
#include "util/thread.h"
#include "util/tick.h"

// Recording of the running stream, on command (--live-record, with --stdin-control): "record
// <path>" writes the stream to <path> from the next keyframe on, with no restart of the camera
// or the encoder; "record-stop" finalizes the file. The stream, the decoder and the v4l2 sink go
// on meanwhile. Each recording is a fresh sc_recorder fed with the latest config packets first,
// so recordings can follow each other in one process.
//
// With a controller, a keyframe is requested from the device when a recording is armed and when
// the decoder goes back to full decoding, and every keyframe_request_interval while the decoder
// only decodes keyframes (a preview nobody watches), so the stream itself can use the long
// keyframe interval of a recording.

// keyframe requests while only keyframes are decoded, like the 2 s keyframe
// interval of a preview without live recording
#define SC_LIVE_RECORD_DORMANT_KEYFRAME_MS 2000

enum sc_live_record_state {
    SC_LIVE_RECORD_IDLE,
    SC_LIVE_RECORD_ARMED,     // waiting for a keyframe
    SC_LIVE_RECORD_RECORDING,
};

struct sc_live_record {
    struct sc_packet_sink video_packet_sink;
    struct sc_packet_sink audio_packet_sink;

    bool video;
    bool audio;
    enum sc_orientation orientation;
    struct sc_controller *controller; // may be NULL: no keyframe requests

    sc_mutex mutex;
    sc_cond cond; // wakes the keyframe requester

    // the streams, as the demuxers opened them
    AVCodecContext *video_ctx;
    AVCodecContext *audio_ctx;
    struct sc_stream_session session;
    bool audio_disabled;
    bool video_closed;
    AVPacket *video_config; // latest config packets
    AVPacket *audio_config;

    enum sc_live_record_state state;
    char *path;
    struct sc_recorder *recorder;
    bool recording_audio; // the current recording has an audio track
    int64_t first_pts;    // its keyframe: earlier audio is dropped
    bool push_failed;     // logged once per recording
    uint64_t frames;

    // keyframe requests while only keyframes are decoded
    sc_tick keyframe_request_interval; // 0: never periodically
    bool dormant;
    bool stopped;
    sc_thread requester;
    bool requester_started;
};

bool
sc_live_record_init(struct sc_live_record *lr, bool video, bool audio,
                    enum sc_orientation orientation,
                    sc_tick keyframe_request_interval);

// Set before the demuxers start (the controller is created after the sinks are added)
void
sc_live_record_set_controller(struct sc_live_record *lr,
                              struct sc_controller *controller);

bool
sc_live_record_start_requester(struct sc_live_record *lr);

// The stdin commands
void
sc_live_record_record(struct sc_live_record *lr, const char *path, size_t len);

void
sc_live_record_stop_recording(struct sc_live_record *lr);

// The decoder decodes only keyframes (dormant) or every frame again
void
sc_live_record_set_dormant(struct sc_live_record *lr, bool dormant);

// Finalize any recording and stop the requester
void
sc_live_record_stop(struct sc_live_record *lr);

void
sc_live_record_join(struct sc_live_record *lr);

void
sc_live_record_destroy(struct sc_live_record *lr);

// The container for a path: mkv for ".mkv", else mp4
enum sc_record_format
sc_live_record_format(const char *path);

#endif
