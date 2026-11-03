#include <jni.h>
#include <cstdint>

extern "C" JNIEXPORT void JNICALL Java_BufferReadCopySmoke_nativeWrite(
        JNIEnv* env, jclass, jobject buffer, jint seed) {
    auto* bytes = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    jlong count = env->GetDirectBufferCapacity(buffer);
    if (!bytes || count < 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "direct buffer required");
        return;
    }
    for (jlong i = 0; i < count; ++i) bytes[i] = static_cast<uint8_t>(seed + i);
}
