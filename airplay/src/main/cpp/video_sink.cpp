#include "video_sink.h"
#include <android/log.h>
#include <mutex>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "video_sink", __VA_ARGS__)

namespace {
JavaVM*   g_vm       = nullptr;
jobject   g_sinkRef  = nullptr;
jmethodID g_onNal    = nullptr;
std::mutex g_mu;

JNIEnv* attach() {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
    return nullptr;
}
}

namespace localair {

void initJvm(JavaVM* vm) { g_vm = vm; }

void setSink(JNIEnv* env, jobject sink) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_sinkRef) { env->DeleteGlobalRef(g_sinkRef); g_sinkRef = nullptr; g_onNal = nullptr; }
    if (!sink) return;
    g_sinkRef = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(g_sinkRef);
    g_onNal = env->GetMethodID(cls, "onNalUnit", "([BJ)V");
    if (!g_onNal) LOGE("onNalUnit not found on sink");
    env->DeleteLocalRef(cls);
}

void dispatchNal(const uint8_t* data, int len, int64_t ptsUs) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_sinkRef || !g_onNal) return;
    JNIEnv* env = attach();
    if (!env) return;
    jbyteArray arr = env->NewByteArray(len);
    env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(data));
    env->CallVoidMethod(g_sinkRef, g_onNal, arr, static_cast<jlong>(ptsUs));
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    env->DeleteLocalRef(arr);
}

} // namespace localair
