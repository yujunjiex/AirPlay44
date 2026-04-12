#pragma once
#include <jni.h>
#include <cstdint>

namespace localair {

void initJvm(JavaVM* vm);
JavaVM* jvm();

void setSink(JNIEnv* env, jobject sink);
void dispatchNal(const uint8_t* data, int len, int64_t ptsUs);

} // namespace localair
