#include "xr_surface_callback.h"
#include <cassert>
#include <cstdarg>

static jobject borrowed = reinterpret_cast<jobject>(0x1000);
static jobject owned = reinterpret_cast<jobject>(0x2000);
static int created = 0, deleted = 0, calls = 0;
static bool stereo = false;
static bool pending = false, fail_new = false, fail_callback = false;
static jobject JNICALL new_local(JNIEnv *, jobject value) {
    assert(value == borrowed);
    if (fail_new) { pending = true; return nullptr; }
    ++created; return owned;
}
static void JNICALL delete_local(JNIEnv *, jobject value) { assert(value == owned); ++deleted; }
static void JNICALL call_void(JNIEnv *, jobject, jmethodID, va_list args) {
    if(stereo) {
        assert(va_arg(args,jobject)==owned);
        assert(va_arg(args,jint)==2048);assert(va_arg(args,jint)==1024);
    } else {assert(va_arg(args, jint) == 2);assert(va_arg(args, jobject) == owned);}
    ++calls;
    pending = fail_callback;
}
static jboolean JNICALL check(JNIEnv *) { return pending; }
static void JNICALL clear(JNIEnv *) { pending = false; }
int main() {
    JNINativeInterface_ table{};
    table.NewLocalRef = new_local;
    table.DeleteLocalRef = delete_local;
    table.CallVoidMethodV = call_void;
    table.ExceptionCheck = check;
    table.ExceptionClear = clear;
    JNIEnv env{&table};
    assert(notify_video_surface(&env, nullptr, nullptr, 2, borrowed));
    assert(created == 1 && deleted == 1 && calls == 1);
    fail_callback = true;
    assert(!notify_video_surface(&env, nullptr, nullptr, 2, borrowed));
    assert(created == 2 && deleted == 2 && calls == 2 && !pending);
    fail_new = true;
    assert(!notify_video_surface(&env, nullptr, nullptr, 2, borrowed));
    assert(created == 2 && deleted == 2 && calls == 2 && !pending);
    stereo=true;fail_new=false;fail_callback=false;created=deleted=calls=0;
    assert(notify_stereo_surface(&env,nullptr,nullptr,borrowed,2048,1024));
    assert(created==1 && deleted==1 && calls==1);
    fail_callback=true;
    assert(!notify_stereo_surface(&env,nullptr,nullptr,borrowed,2048,1024));
    assert(created==2 && deleted==2 && calls==2 && !pending);
    fail_new=true;
    assert(!notify_stereo_surface(&env,nullptr,nullptr,borrowed,2048,1024));
    assert(created==2 && deleted==2 && calls==2 && !pending);
}
