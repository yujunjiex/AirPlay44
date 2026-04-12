#include "audio_sink.h"
#include "video_sink.h"    // for localair::jvm()
#include <android/log.h>
#include <mutex>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "audio_sink", __VA_ARGS__)

namespace {
jobject   g_sinkRef = nullptr;
jmethodID g_onAac   = nullptr;
std::mutex g_mu;

JNIEnv* attach() {
    JavaVM* vm = localair::jvm();
    if (!vm) return nullptr;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
    return nullptr;
}
}

namespace localair {

void setAudioSink(JNIEnv* env, jobject sink) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_sinkRef) { env->DeleteGlobalRef(g_sinkRef); g_sinkRef = nullptr; g_onAac = nullptr; }
    if (!sink) return;
    g_sinkRef = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(g_sinkRef);
    g_onAac = env->GetMethodID(cls, "onAacFrame", "([BJ)V");
    if (!g_onAac) LOGE("onAacFrame not found on sink");
    env->DeleteLocalRef(cls);
}

void dispatchAac(const uint8_t* data, int len, int64_t ptsUs) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_sinkRef || !g_onAac) return;
    JNIEnv* env = attach();
    if (!env) return;
    jbyteArray arr = env->NewByteArray(len);
    env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(data));
    env->CallVoidMethod(g_sinkRef, g_onAac, arr, static_cast<jlong>(ptsUs));
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    env->DeleteLocalRef(arr);
}

} // namespace localair
