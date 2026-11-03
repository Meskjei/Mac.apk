// A real pipe supplies readiness; only the JNI poll bridge is substituted in this isolated JVM test.
#include <jni.h>
#include <cassert>
#include <poll.h>
#include <pthread.h>
#include <unistd.h>

static int pipe_fds[2];
static pthread_t owner;
static jclass smoke;
static jfieldID delivered;
static void poll_once(JNIEnv* env, jclass, jlong, jint) {
    assert(pthread_equal(owner, pthread_self()));
    pollfd ready{pipe_fds[0], POLLIN, 0};
    if (::poll(&ready, 1, 0) == 1 && (ready.revents & POLLIN)) {
        char byte;
        assert(::read(pipe_fds[0], &byte, 1) == 1);
        env->SetStaticIntField(smoke, delivered, env->GetStaticIntField(smoke, delivered) + 1);
    }
}
static void wake(JNIEnv*, jclass, jlong) {}
static void log_line(JNIEnv*, jclass, jint, jstring, jstring) {}
extern "C" JNIEXPORT void JNICALL Java_MainLooperFdFairnessSmoke_signal(JNIEnv*, jclass) {
    assert(::write(pipe_fds[1], "x", 1) == 1);
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    assert(vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK);
    assert(::pipe(pipe_fds) == 0);
    owner = pthread_self();
    smoke = static_cast<jclass>(env->NewGlobalRef(env->FindClass("MainLooperFdFairnessSmoke")));
    delivered = env->GetStaticFieldID(smoke, "delivered", "I");
    JNINativeMethod methods[] = {
        {const_cast<char*>("pollOnce"), const_cast<char*>("(JI)V"), reinterpret_cast<void*>(poll_once)},
        {const_cast<char*>("wake"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(wake)},
    };
    assert(env->RegisterNatives(env->FindClass("com/macapk/loader/NativeLooper"), methods, 2) == JNI_OK);
    JNINativeMethod log_method{const_cast<char*>("print"),
        const_cast<char*>("(ILjava/lang/String;Ljava/lang/String;)V"), reinterpret_cast<void*>(log_line)};
    assert(env->RegisterNatives(env->FindClass("com/macapk/loader/NativeLog"), &log_method, 1) == JNI_OK);
    return JNI_VERSION_1_8;
}
