#pragma once
#include <jni.h>
#include <cstdint>

namespace localair {

void setAudioSink(JNIEnv* env, jobject sink);
void dispatchAac(const uint8_t* data, int len, int64_t ptsUs);

} // namespace localair
