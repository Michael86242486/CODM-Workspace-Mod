#include "Includes.h"
#include "CODM.h"

// Keep a stable JNI title export. No password/license/auth validation gates are used.
extern "C" JNIEXPORT jstring JNICALL
Java_com_gotoubun_Floating_Title(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF("CODM");
}

void native_onSendConfig(JNIEnv *env, jobject /*thiz*/, jstring s, jstring v) {
    const char *config = env ? env->GetStringUTFChars(s, 0) : nullptr;
    const char *value = env ? env->GetStringUTFChars(v, 0) : nullptr;
    if (config && value) {
        Config[config] = (u_long) strtoul(value, 0, 0);
    }
    if (env && s && config) env->ReleaseStringUTFChars(s, config);
    if (env && v && value) env->ReleaseStringUTFChars(v, value);
}

void native_onSwitch(JNIEnv * /*env*/, jclass /*clazz*/, jint i, jboolean enabled) {
    // Optional Java hook kept for compatibility; menu uses onSendConfig-driven flags.
    Config[std::string("SWITCH::") + std::to_string((int) i)] = enabled ? 1UL : 0UL;
}

int Register1(JNIEnv *env) {
    JNINativeMethod methods[] = {
        {"onSendConfig", "(Ljava/lang/String;Ljava/lang/String;)V", (void *) native_onSendConfig},
        {"onCanvasDraw", "(Landroid/graphics/Canvas;IIF)V", (void *) native_onCanvasDraw},
        {"Switch", "(IZ)V", (void *) native_onSwitch},
    };

    jclass clazz = env->FindClass("com/gotoubun/Floating");
    if (!clazz) return -1;
    env->ExceptionClear();
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != 0) return -1;
    return 0;
}

int Register2(JNIEnv *env) {
    JNINativeMethod methods[] = {{"Init", "(Landroid/content/Context;)V", (void *) native_Init}};

    jclass clazz = env->FindClass("com/gotoubun/Launcher");
    if (!clazz) return -1;
    env->ExceptionClear();
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != 0) return -1;
    return 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    JNIEnv *env = nullptr;
    if (!vm || vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK || !env) return -1;
    if (Register1(env) != 0) return -1;
    if (Register2(env) != 0) return -1;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM * /*vm*/, void * /*reserved*/) {
}
