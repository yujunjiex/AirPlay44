#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>

#include "video_sink.h"
#include "audio_sink.h"

#if HAVE_RPIPLAY
extern "C" {
#include "raop.h"
#include "stream.h"
#include "dnssd.h"
}
#endif

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "airplay_native", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "airplay_native", __VA_ARGS__)

namespace {

#if HAVE_RPIPLAY
raop_t*  g_raop  = nullptr;
dnssd_t* g_dnssd = nullptr;

void audio_process(void*, raop_ntp_t*, aac_decode_struct* data) {
    localair::dispatchAac(data->data, data->data_len, static_cast<int64_t>(data->pts));
}
void video_process(void*, raop_ntp_t*, h264_decode_struct* data) {
    localair::dispatchNal(data->data, data->data_len, static_cast<int64_t>(data->pts));
}
void conn_init(void*)    {}
void conn_destroy(void*) {}
void audio_flush(void*)  {}
void video_flush(void*)  {}
void audio_set_volume(void*, float)                       {}
void audio_set_metadata(void*, const void*, int)          {}
void audio_set_coverart(void*, const void*, int)          {}
void log_callback(void*, int level, const char* msg) {
    __android_log_print(ANDROID_LOG_DEBUG, "rpiplay", "[%d] %s", level, msg);
}
#endif

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_localair_airplay_nativebridge_AirPlayNative_nativeStart(JNIEnv*, jclass) {
#if HAVE_RPIPLAY
    raop_callbacks_t cbs{};
    cbs.audio_process      = audio_process;
    cbs.video_process      = video_process;
    cbs.conn_init          = conn_init;
    cbs.conn_destroy       = conn_destroy;
    cbs.audio_flush        = audio_flush;
    cbs.video_flush        = video_flush;
    cbs.audio_set_volume   = audio_set_volume;
    cbs.audio_set_metadata = audio_set_metadata;
    cbs.audio_set_coverart = audio_set_coverart;

    g_raop = raop_init(10, &cbs);
    if (!g_raop) { LOGE("raop_init failed"); return 0; }
    raop_set_log_callback(g_raop, log_callback, nullptr);
    raop_set_log_level(g_raop, RAOP_LOG_DEBUG);

    // dnssd_stub.c implements this API as a no-op store for name + hw_addr.
    // Kotlin (NsdManager) handles actual Bonjour advertising.
    static const char hw_addr[6] = {(char)0xAA,(char)0xBB,(char)0xCC,(char)0xDD,(char)0xEE,(char)0xFF};
    int err = 0;
    g_dnssd = dnssd_init("localair", 8, hw_addr, 6, &err);
    if (!g_dnssd) { LOGE("dnssd_init failed: %d", err); raop_destroy(g_raop); g_raop = nullptr; return 0; }
    raop_set_dnssd(g_raop, g_dnssd);

    unsigned short port = 0;
    if (raop_start(g_raop, &port) < 0) {
        LOGE("raop_start failed");
        dnssd_destroy(g_dnssd); g_dnssd = nullptr;
        raop_destroy(g_raop);   g_raop  = nullptr;
        return 0;
    }
    LOGI("raop listening on %d", port);
    return static_cast<jint>(port);
#else
    LOGI("stub start — RPiPlay not vendored");
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_localair_airplay_nativebridge_AirPlayNative_nativeStop(JNIEnv*, jclass) {
#if HAVE_RPIPLAY
    if (g_raop)  { raop_stop(g_raop); raop_destroy(g_raop); g_raop = nullptr; }
    if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = nullptr; }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_localair_airplay_nativebridge_AirPlayNative_nativeSetSink(JNIEnv* env, jclass, jobject sink) {
    localair::setSink(env, sink);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localair_airplay_nativebridge_AirPlayNative_nativeSetAudioSink(JNIEnv* env, jclass, jobject sink) {
    localair::setAudioSink(env, sink);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    localair::initJvm(vm);
    return JNI_VERSION_1_6;
}
