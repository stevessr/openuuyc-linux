#pragma once
#include <jni.h>

// The runtime may return a global reference (PICO does). Its handle is borrowed:
// never delete it as a local/global reference. Own only a new local reference.
inline bool notify_video_surface(JNIEnv *env, jobject activity, jmethodID ready, jint pane, jobject surface) {
    jobject local = env->NewLocalRef(surface);
    if (!local) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    env->CallVoidMethod(activity, ready, pane, local);
    env->DeleteLocalRef(local);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}
