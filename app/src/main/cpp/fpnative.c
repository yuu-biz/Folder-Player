/*
 * libfpnative — FFmpeg-based PCM decoder for WMA / APE / ALAC (in MP4) and DSD (DSF / DFF) for Folder Player Fork.
 *
 * Bytes come from Java (NativeIo: positional reads over the source file system) through a custom AVIOContext, so
 * every source type (Local, SAF, WebDAV, SMB, FTP) works the same way. Output is interleaved signed 16-bit PCM,
 * at most 2 channels, resampled down to <= maxOutputRate (DSD is converted to PCM; no DoP / native DSD).
 *
 * Error codes returned to Java: -1 end of stream, -2 unsupported, -3 corrupt data, -4 I/O error.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>

#define TAG "fpnative"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define ERR_EOF (-1)
#define ERR_UNSUPPORTED (-2)
#define ERR_CORRUPT (-3)
#define ERR_IO (-4)
#define AVIO_BUF 65536
#define MAX_CONSECUTIVE_ERRORS 50

typedef struct {
    JNIEnv *env;
    jobject io;            /* global ref to NativeIo */
    jmethodID readMid, seekMid;
    jbyteArray jbuf;       /* global ref, AVIO_BUF bytes */
    int ioError;

    AVFormatContext *fmt;
    AVIOContext *avio;
    AVCodecContext *dec;
    SwrContext *swr;
    AVPacket *pkt;
    AVFrame *frame;
    int stream;
    int inRate, outRate, outChannels;
    int64_t totalFrames, durationUs;

    uint8_t *pcm;          /* pending output PCM */
    int pcmLen, pcmPos, pcmCap;
    int64_t seekTargetIn;  /* drop input samples before this (input-rate units), -1 = none */
    int draining, eof, errors;
    char codecName[64];
} Dec;

static void throwNative(JNIEnv *env, int code, const char *msg) {
    jclass cls = (*env)->FindClass(env, "com/wing/folderplayer/playback/NativeDecoderException");
    if (!cls) return;
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(ILjava/lang/String;)V");
    jstring jmsg = (*env)->NewStringUTF(env, msg);
    jobject ex = (*env)->NewObject(env, cls, ctor, code, jmsg);
    (*env)->Throw(env, (jthrowable) ex);
}

static int readPacket(void *opaque, uint8_t *buf, int size) {
    Dec *d = (Dec *) opaque;
    JNIEnv *env = d->env;
    if (size > AVIO_BUF) size = AVIO_BUF;
    jint n = (*env)->CallIntMethod(env, d->io, d->readMid, d->jbuf, size);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        d->ioError = 1;
        return AVERROR(EIO);
    }
    if (n <= 0) return AVERROR_EOF;
    (*env)->GetByteArrayRegion(env, d->jbuf, 0, n, (jbyte *) buf);
    return n;
}

static int64_t seekPacket(void *opaque, int64_t offset, int whence) {
    Dec *d = (Dec *) opaque;
    JNIEnv *env = d->env;
    int w = whence & AVSEEK_SIZE ? 0x10000 : (whence & ~AVSEEK_FORCE);
    jlong r = (*env)->CallLongMethod(env, d->io, d->seekMid, (jlong) offset, (jint) w);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        d->ioError = 1;
        return AVERROR(EIO);
    }
    return r < 0 ? AVERROR(EIO) : r;
}

static void freeDec(JNIEnv *env, Dec *d) {
    if (!d) return;
    if (d->swr) swr_free(&d->swr);
    if (d->dec) avcodec_free_context(&d->dec);
    if (d->frame) av_frame_free(&d->frame);
    if (d->pkt) av_packet_free(&d->pkt);
    if (d->fmt) avformat_close_input(&d->fmt);
    if (d->avio) {
        av_freep(&d->avio->buffer);
        avio_context_free(&d->avio);
    }
    free(d->pcm);
    if (d->io) (*env)->DeleteGlobalRef(env, d->io);
    if (d->jbuf) (*env)->DeleteGlobalRef(env, d->jbuf);
    free(d);
}

static const char *formatFor(const char *hint) {
    if (!strcmp(hint, "dsf")) return "dsf";
    if (!strcmp(hint, "dff")) return "iff";
    if (!strcmp(hint, "ape")) return "ape";
    if (!strcmp(hint, "wma")) return "asf";
    if (!strcmp(hint, "m4a")) return "mov";
    return NULL;
}

static int setupResampler(Dec *d) {
    if (d->swr) swr_free(&d->swr);
    AVChannelLayout outLayout;
    av_channel_layout_default(&outLayout, d->outChannels);
    AVChannelLayout inLayout;
    if (d->dec->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) av_channel_layout_default(&inLayout, d->dec->ch_layout.nb_channels);
    else av_channel_layout_copy(&inLayout, &d->dec->ch_layout);
    int r = swr_alloc_set_opts2(&d->swr, &outLayout, AV_SAMPLE_FMT_S16, d->outRate,
                                &inLayout, d->dec->sample_fmt, d->inRate, 0, NULL);
    av_channel_layout_uninit(&inLayout);
    if (r < 0) return r;
    return swr_init(d->swr);
}

JNIEXPORT jstring JNICALL
Java_com_wing_folderplayer_playback_NativeDecoder_nativeVersion(JNIEnv *env, jclass clazz) {
    char v[96];
    snprintf(v, sizeof v, "FFmpeg %s (libavcodec %d.%d)", av_version_info(), LIBAVCODEC_VERSION_MAJOR, LIBAVCODEC_VERSION_MINOR);
    return (*env)->NewStringUTF(env, v);
}

JNIEXPORT jlong JNICALL
Java_com_wing_folderplayer_playback_NativeDecoder_nativeOpen(JNIEnv *env, jclass clazz, jobject io, jstring jhint, jint maxRate) {
    Dec *d = calloc(1, sizeof(Dec));
    if (!d) { throwNative(env, ERR_IO, "out of memory"); return 0; }
    d->env = env;
    d->seekTargetIn = -1;
    d->io = (*env)->NewGlobalRef(env, io);
    jclass ioCls = (*env)->GetObjectClass(env, io);
    d->readMid = (*env)->GetMethodID(env, ioCls, "read", "([BI)I");
    d->seekMid = (*env)->GetMethodID(env, ioCls, "seek", "(JI)J");
    jbyteArray local = (*env)->NewByteArray(env, AVIO_BUF);
    d->jbuf = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);

    const char *hint = (*env)->GetStringUTFChars(env, jhint, NULL);
    const char *fmtName = formatFor(hint);
    int isM4a = !strcmp(hint, "m4a");
    (*env)->ReleaseStringUTFChars(env, jhint, hint);
    char msg[160];

    uint8_t *buf = av_malloc(AVIO_BUF);
    d->avio = avio_alloc_context(buf, AVIO_BUF, 0, d, readPacket, NULL, seekPacket);
    d->fmt = avformat_alloc_context();
    d->fmt->pb = d->avio;
    d->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    const AVInputFormat *ifmt = fmtName ? av_find_input_format(fmtName) : NULL;
    int r = avformat_open_input(&d->fmt, NULL, ifmt, NULL);
    if (r < 0) {
        d->fmt = NULL; /* freed by avformat_open_input on failure */
        snprintf(msg, sizeof msg, "cannot open container: %s", av_err2str(r));
        throwNative(env, d->ioError ? ERR_IO : ERR_CORRUPT, msg);
        freeDec(env, d);
        return 0;
    }
    r = avformat_find_stream_info(d->fmt, NULL);
    if (r < 0) {
        throwNative(env, d->ioError ? ERR_IO : ERR_CORRUPT, "cannot read stream info");
        freeDec(env, d);
        return 0;
    }
    d->stream = av_find_best_stream(d->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (d->stream < 0) {
        throwNative(env, ERR_UNSUPPORTED, "no audio stream");
        freeDec(env, d);
        return 0;
    }
    AVStream *st = d->fmt->streams[d->stream];
    enum AVCodecID id = st->codecpar->codec_id;
    if (id == AV_CODEC_ID_DST) {
        throwNative(env, ERR_UNSUPPORTED, "DST-compressed DSD is not supported");
        freeDec(env, d);
        return 0;
    }
    if (isM4a && id != AV_CODEC_ID_ALAC) {
        /* AAC etc. stay on the regular Media3 MP4 path. */
        throwNative(env, ERR_UNSUPPORTED, "not ALAC");
        freeDec(env, d);
        return 0;
    }
    const AVCodec *codec = avcodec_find_decoder(id);
    if (!codec) {
        snprintf(msg, sizeof msg, "no decoder for %s in this build", avcodec_get_name(id));
        throwNative(env, ERR_UNSUPPORTED, msg);
        freeDec(env, d);
        return 0;
    }
    snprintf(d->codecName, sizeof d->codecName, "%s", codec->name);
    d->dec = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(d->dec, st->codecpar);
    d->dec->pkt_timebase = st->time_base;
    if ((r = avcodec_open2(d->dec, codec, NULL)) < 0) {
        snprintf(msg, sizeof msg, "cannot open decoder: %s", av_err2str(r));
        throwNative(env, ERR_CORRUPT, msg);
        freeDec(env, d);
        return 0;
    }
    d->inRate = d->dec->sample_rate;
    d->outRate = d->inRate;
    while (d->outRate > maxRate && d->outRate % 2 == 0) d->outRate /= 2;
    int ch = d->dec->ch_layout.nb_channels;
    d->outChannels = ch >= 2 ? 2 : 1;
    if ((r = setupResampler(d)) < 0) {
        throwNative(env, ERR_UNSUPPORTED, "cannot set up resampler");
        freeDec(env, d);
        return 0;
    }
    int64_t durTb = st->duration != AV_NOPTS_VALUE ? st->duration : -1;
    if (durTb > 0) {
        d->durationUs = av_rescale_q(durTb, st->time_base, AV_TIME_BASE_Q);
        d->totalFrames = av_rescale_q(durTb, st->time_base, (AVRational){1, d->outRate});
    } else if (d->fmt->duration > 0) {
        d->durationUs = d->fmt->duration;
        d->totalFrames = av_rescale(d->fmt->duration, d->outRate, AV_TIME_BASE);
    } else {
        d->durationUs = -1;
        d->totalFrames = -1;
    }
    d->pkt = av_packet_alloc();
    d->frame = av_frame_alloc();
    return (jlong) (intptr_t) d;
}

JNIEXPORT jlongArray JNICALL
Java_com_wing_folderplayer_playback_NativeDecoder_nativeInfo(JNIEnv *env, jclass clazz, jlong h) {
    Dec *d = (Dec *) (intptr_t) h;
    jlong v[5] = {d->outRate, d->outChannels, d->durationUs, d->totalFrames, 1};
    jlongArray a = (*env)->NewLongArray(env, 5);
    (*env)->SetLongArrayRegion(env, a, 0, 5, v);
    return a;
}

JNIEXPORT jstring JNICALL
Java_com_wing_folderplayer_playback_NativeDecoder_nativeCodecName(JNIEnv *env, jclass clazz, jlong h) {
    Dec *d = (Dec *) (intptr_t) h;
    return (*env)->NewStringUTF(env, d->codecName);
}

static int ensurePcm(Dec *d, int bytes) {
    if (d->pcmCap >= bytes) return 0;
    uint8_t *n = realloc(d->pcm, bytes);
    if (!n) return -1;
    d->pcm = n;
    d->pcmCap = bytes;
    return 0;
}

/* Converts one decoded frame (after dropping samples before a seek target) into pending PCM. */
static int convertFrame(Dec *d, AVFrame *f) {
    int offset = 0;
    if (d->seekTargetIn >= 0) {
        int64_t pts = f->best_effort_timestamp;
        if (pts != AV_NOPTS_VALUE) {
            AVStream *st = d->fmt->streams[d->stream];
            int64_t startIn = av_rescale_q(pts, st->time_base, (AVRational){1, d->inRate});
            if (startIn + f->nb_samples <= d->seekTargetIn) return 0; /* whole frame before target */
            if (startIn < d->seekTargetIn) offset = (int) (d->seekTargetIn - startIn);
        }
        d->seekTargetIn = -1;
    }
    int inSamples = f->nb_samples - offset;
    const uint8_t *in[AV_NUM_DATA_POINTERS] = {0};
    int bps = av_get_bytes_per_sample(f->format);
    int planar = av_sample_fmt_is_planar(f->format);
    int chans = f->ch_layout.nb_channels;
    for (int c = 0; c < (planar ? chans : 1) && c < AV_NUM_DATA_POINTERS; c++) {
        in[c] = f->extended_data[c] + (size_t) offset * bps * (planar ? 1 : chans);
    }
    int maxOut = swr_get_out_samples(d->swr, inSamples);
    if (maxOut < 0) return maxOut;
    int need = maxOut * d->outChannels * 2;
    if (ensurePcm(d, need) < 0) return AVERROR(ENOMEM);
    uint8_t *out[1] = {d->pcm};
    int got = swr_convert(d->swr, out, maxOut, in, inSamples);
    if (got < 0) return got;
    d->pcmLen = got * d->outChannels * 2;
    d->pcmPos = 0;
    return 0;
}

static int flushResampler(Dec *d) {
    int maxOut = swr_get_out_samples(d->swr, 0);
    if (maxOut <= 0) return 0;
    if (ensurePcm(d, maxOut * d->outChannels * 2) < 0) return AVERROR(ENOMEM);
    uint8_t *out[1] = {d->pcm};
    int got = swr_convert(d->swr, out, maxOut, NULL, 0);
    if (got < 0) return got;
    d->pcmLen = got * d->outChannels * 2;
    d->pcmPos = 0;
    return got;
}

/* Fills pending PCM. Returns >0 when PCM is available, ERR_EOF at the end, or another negative error. */
static int decodeMore(Dec *d) {
    while (d->pcmPos >= d->pcmLen) {
        if (d->eof) return ERR_EOF;
        int r = avcodec_receive_frame(d->dec, d->frame);
        if (r == 0) {
            d->errors = 0;
            r = convertFrame(d, d->frame);
            av_frame_unref(d->frame);
            if (r < 0) return ERR_CORRUPT;
            continue;
        }
        if (r == AVERROR_EOF) {
            if (flushResampler(d) <= 0) d->eof = 1;
            continue;
        }
        if (r != AVERROR(EAGAIN)) {
            if (++d->errors > MAX_CONSECUTIVE_ERRORS) return ERR_CORRUPT;
            continue;
        }
        if (d->draining) {
            d->eof = 1;
            continue;
        }
        r = av_read_frame(d->fmt, d->pkt);
        if (r < 0) {
            if (d->ioError) return ERR_IO;
            if (r != AVERROR_EOF && ++d->errors > MAX_CONSECUTIVE_ERRORS) return ERR_CORRUPT;
            d->draining = 1;
            avcodec_send_packet(d->dec, NULL);
            continue;
        }
        if (d->pkt->stream_index != d->stream) {
            av_packet_unref(d->pkt);
            continue;
        }
        r = avcodec_send_packet(d->dec, d->pkt);
        av_packet_unref(d->pkt);
        if (r < 0 && r != AVERROR(EAGAIN)) {
            LOGW("send_packet failed: %s", av_err2str(r));
            if (++d->errors > MAX_CONSECUTIVE_ERRORS) return ERR_CORRUPT;
        }
    }
    return 1;
}

JNIEXPORT jint JNICALL
Java_com_wing_folderplayer_playback_NativeDecoder_nativeRead(JNIEnv *env, jclass clazz, jlong h, jbyteArray out, jint off, jint len) {
    Dec *d = (Dec *) (intptr_t) h;
    d->env = env;
    int r = decodeMore(d);
    if (r < 0) return r;
    int n = d->pcmLen - d->pcmPos;
    if (n > len) n = len;
    (*env)->SetByteArrayRegion(env, out, off, n, (const jbyte *) (d->pcm + d->pcmPos));
    d->pcmPos += n;
    return n;
}

JNIEXPORT jint JNICALL
Java_com_wing_folderplayer_playback_NativeDecoder_nativeSeekFrame(JNIEnv *env, jclass clazz, jlong h, jlong outFrame) {
    Dec *d = (Dec *) (intptr_t) h;
    d->env = env;
    AVStream *st = d->fmt->streams[d->stream];
    int64_t targetIn = av_rescale(outFrame, d->inRate, d->outRate);
    int64_t ts = av_rescale_q(targetIn, (AVRational){1, d->inRate}, st->time_base);
    int r = av_seek_frame(d->fmt, d->stream, ts, AVSEEK_FLAG_BACKWARD);
    if (r < 0) {
        /* Container cannot seek by time: restart and decode-and-discard up to the target. */
        r = av_seek_frame(d->fmt, d->stream, 0, AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_BYTE);
        if (r < 0) return d->ioError ? ERR_IO : ERR_UNSUPPORTED;
    }
    avcodec_flush_buffers(d->dec);
    if (setupResampler(d) < 0) return ERR_CORRUPT;
    d->pcmLen = d->pcmPos = 0;
    d->eof = d->draining = 0;
    d->errors = 0;
    d->seekTargetIn = targetIn;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_wing_folderplayer_playback_NativeDecoder_nativeClose(JNIEnv *env, jclass clazz, jlong h) {
    Dec *d = (Dec *) (intptr_t) h;
    if (!d) return;
    d->env = env;
    freeDec(env, d);
}
