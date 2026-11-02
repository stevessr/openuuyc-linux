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

// Android Surface-swapchain handles are runtime-owned. Borrow the original and
// pass a newly owned local reference, even when PICO returns a global reference.
inline bool notify_stereo_surface(JNIEnv *env,jobject activity,jmethodID ready,jobject surface,jint width,jint height) {
    jobject local=env->NewLocalRef(surface);
    if(!local) {if(env->ExceptionCheck())env->ExceptionClear();return false;}
    env->CallVoidMethod(activity,ready,local,width,height);
    env->DeleteLocalRef(local);
    if(env->ExceptionCheck()) {env->ExceptionClear();return false;}
    return true;
}
