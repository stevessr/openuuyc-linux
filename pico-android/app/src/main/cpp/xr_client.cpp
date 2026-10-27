// PICO OpenXR native client. Stereo display comes from the compositor:
// up to three independent world-space quads can display separate UU RTP
// tracks, with each Android decoder producing into its own Surface.
#include <jni.h>
#include "xr_surface_callback.h"
#include "xr_panel_geometry.h"
#include "xr_pose_math.h"
#include "xr_pointer_feedback.h"
#include "xr_video_resolution.h"
#include "xr_panel_depth.h"
#include "xr_menu_click.h"
#include "xr_grip_hold.h"
#include "xr_panel_orbit.h"
#include "xr_ray_geometry.h"
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <atomic>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>
#include <string>
#include <thread>
#include <mutex>
#include <chrono>
#include <initializer_list>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OpenUUYC-PICO", __VA_ARGS__)
static std::atomic<bool> stop_requested{false};
static std::atomic<uint32_t> window_visible_mask{1u};
static std::atomic<bool> passthrough_requested{true};
static std::atomic<bool> layout_reset{false};
static std::atomic<float> panel_aspects[6]{{9.f/16.f},{9.f/16.f},{9.f/16.f},{9.f/16.f},{9.f/16.f},{9.f/16.f}};
static PanelGeometry panels[6] = {{0.f,0.f,1.65f},{-1.55f,0.f,1.25f},{1.55f,0.f,1.25f},{0.f,1.3f,1.25f},{-1.55f,1.3f,1.25f},{1.55f,1.3f,1.25f}};
static bool toolbar_available = false;
static PanelGeometry menu_panel{0.f,-.82f,1.65f};
static std::atomic<bool> menu_portrait{false};
static bool menu_grab=false;
static bool menu_grip=false;
static std::atomic<int> menu_host{-1};
static std::atomic<bool> menu_reset_requested{false};
static OrbitVector menu_grab_origin{0,0,0};
static float menu_grab_radius=2;
static float window_grab_radius=2;
static XrGripHold grip_holds[2];
static bool grip_dragging=false;
static bool trigger_blocked[2]={true,true};
static int interaction_hand=-1;
static std::mutex follow_pose_mutex;
static XrPosef follow_requested_offset{};
static bool follow_requested_valid=false;
static XrPosef follow_click_offset{};
static bool follow_click_valid=false;

static bool frame_available = false;
static std::atomic<bool> follow_view{false};
static int grabbed_pane = -1;
static bool resizing = false;
static int resize_corner=3;
static float resize_anchor_x=0,resize_anchor_y=0,resize_anchor_z=-2;
static XrVector3f grab_depth_origin{0.f,0.f,0.f};
static XrVector3f grab_depth_direction{0.f,0.f,-1.f};
static float grab_u=0.f, grab_v=0.f, grab_yaw=0.f, grab_hand_yaw=0.f;

static XrVector3f rotate_vector(const XrQuaternionf &, XrVector3f);
static bool panel_ray(const XrPosef &aim, const PanelGeometry &p, float &x, float &y) {
    const auto d=rotate_vector(aim.orientation,{0.f,0.f,-1.f});
    return panel_intersect(p,{aim.position.x,aim.position.y,aim.position.z},{d.x,d.y,d.z},x,y);
}



static bool ray_visible[2]={false,false};
static XrPosef ray_poses[2]{};
static XrPosef focus_poses[2]{};
static float ray_lengths[2]={2,2};
static bool focus_pressed[2]={false,false};
static int preferred_hand=1;
static void update_free_ray(int hand,const XrPosef &aim,const XrPosef &eye,float distance=2.f) {
    const auto d=rotate_vector(aim.orientation,{0.f,0.f,-1.f});
    const auto b=ray_basis({d.x,d.y,d.z},{eye.position.x-aim.position.x,eye.position.y-aim.position.y,eye.position.z-aim.position.z});
    // Columns are the billboard basis; its local Y follows the ray.
    const float m00=b.right.x,m01=b.up.x,m02=b.normal.x;
    const float m10=b.right.y,m11=b.up.y,m12=b.normal.y;
    const float m20=b.right.z,m21=b.up.z,m22=b.normal.z;
    XrQuaternionf q{};const float trace=m00+m11+m22;
    if(trace>0) {const float t=std::sqrt(trace+1)*2;q={ (m21-m12)/t,(m02-m20)/t,(m10-m01)/t,t/4 };}
    else if(m00>m11 && m00>m22) {const float t=std::sqrt(1+m00-m11-m22)*2;q={t/4,(m01+m10)/t,(m02+m20)/t,(m21-m12)/t};}
    else if(m11>m22) {const float t=std::sqrt(1+m11-m00-m22)*2;q={(m01+m10)/t,t/4,(m12+m21)/t,(m02-m20)/t};}
    else {const float t=std::sqrt(1+m22-m00-m11)*2;q={(m02+m20)/t,(m12+m21)/t,t/4,(m10-m01)/t};}
    distance=std::clamp(distance,.05f,10.f);ray_lengths[hand]=distance;
    ray_poses[hand]={q,{aim.position.x+d.x*distance/2,aim.position.y+d.y*distance/2,aim.position.z+d.z*distance/2}};
    focus_poses[hand]={eye.orientation,{aim.position.x+d.x*distance,aim.position.y+d.y*distance,aim.position.z+d.z*distance}};
    ray_visible[hand]=true;
}

static XrPosef identity_pose(float z = 0.f) {
    XrPosef pose{};
    pose.orientation.w = 1.f;
    pose.position.z = z;
    return pose;
}

static XrQuaternionf panel_orientation(const PanelGeometry &p) {
    const float sy=std::sin(p.yaw/2),cy=std::cos(p.yaw/2),sx=std::sin(p.pitch/2),cx=std::cos(p.pitch/2);
    return {cy*sx,sy*cx,-sy*sx,cy*cx};
}

static XrPosef compose_xr_pose(const XrPosef &a,const XrPosef &b) {
    const auto q=pose_compose({{a.orientation.x,a.orientation.y,a.orientation.z,a.orientation.w},{a.position.x,a.position.y,a.position.z}},
        {{b.orientation.x,b.orientation.y,b.orientation.z,b.orientation.w},{b.position.x,b.position.y,b.position.z}});
    return {{q.rotation.x,q.rotation.y,q.rotation.z,q.rotation.w},{q.position.x,q.position.y,q.position.z}};
}

static bool xr_ok(XrResult result, const char *step) {
    if (XR_FAILED(result)) {
        LOGE("%s failed: %d", step, static_cast<int>(result));
        return false;
    }
    return true;
}

static void error(JNIEnv *env, jobject activity, const char *message) {
    jclass klass = env->GetObjectClass(activity);
    jmethodID method = env->GetMethodID(klass, "onXrError", "(Ljava/lang/String;)V");
    if (method) {
        jstring text = env->NewStringUTF(message);
        env->CallVoidMethod(activity, method, text);
        env->DeleteLocalRef(text);
    }
    env->DeleteLocalRef(klass);
}

struct EGLState {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLConfig config = nullptr;
    bool create() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return false;
        const EGLint attrs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
            EGL_NONE
        };
        EGLint count = 0;
        if (!eglChooseConfig(display, attrs, &config, 1, &count) || count < 1) return false;
        const EGLint ctx[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx);
        if (context == EGL_NO_CONTEXT) return false;
        const EGLint buffer[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        surface = eglCreatePbufferSurface(display, config, buffer);
        if (surface == EGL_NO_SURFACE) return false;
        return eglMakeCurrent(display, surface, surface, context) == EGL_TRUE;
    }
    void destroy() {
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            eglTerminate(display);
        }
    }
};

static XrPath path(XrInstance instance, const char *name) {
    XrPath id = XR_NULL_PATH;
    xrStringToPath(instance, name, &id);
    return id;
}

struct Actions {
    XrActionSet set = XR_NULL_HANDLE;
    XrAction aim = XR_NULL_HANDLE;
    XrAction click = XR_NULL_HANDLE;
    XrAction grip = XR_NULL_HANDLE;
    XrAction cancel_follow = XR_NULL_HANDLE;
    XrAction toggle_follow = XR_NULL_HANDLE;
    XrAction scroll = XR_NULL_HANDLE;
    XrActionSpaceCreateInfo unused{}; // non-owning action reference
    XrSpace hands[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    XrPath hand_paths[2] = {XR_NULL_PATH, XR_NULL_PATH};

    bool create(XrInstance instance, XrSession session) {
        hand_paths[0] = path(instance, "/user/hand/left");
        hand_paths[1] = path(instance, "/user/hand/right");
        XrActionSetCreateInfo set_info{XR_TYPE_ACTION_SET_CREATE_INFO};
        std::strcpy(set_info.actionSetName, "remote");
        std::strcpy(set_info.localizedActionSetName, "Remote control");
        set_info.priority = 0;
        if (!xr_ok(xrCreateActionSet(instance, &set_info, &set), "xrCreateActionSet")) return false;
        XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
        info.actionType = XR_ACTION_TYPE_POSE_INPUT;
        std::strcpy(info.actionName, "aim");
        std::strcpy(info.localizedActionName, "Controller aim");
        info.countSubactionPaths = 2;
        info.subactionPaths = hand_paths;
        if (!xr_ok(xrCreateAction(set, &info, &aim), "create aim action")) return false;
        info.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
        std::strcpy(info.actionName, "click");
        std::strcpy(info.localizedActionName, "Trigger click");
        if (!xr_ok(xrCreateAction(set, &info, &click), "create trigger action")) return false;
        std::strcpy(info.actionName,"window_grip");
        std::strcpy(info.localizedActionName,"Hold inner grip to move window");
        if(!xr_ok(xrCreateAction(set,&info,&grip),"create grip action")) return false;
        std::strcpy(info.actionName,"cancel_follow");
        std::strcpy(info.localizedActionName,"B stop following");
        if(!xr_ok(xrCreateAction(set,&info,&cancel_follow),"create B action")) return false;
        std::strcpy(info.actionName,"toggle_follow");
        std::strcpy(info.localizedActionName,"A toggle camera follow");
        if(!xr_ok(xrCreateAction(set,&info,&toggle_follow),"create A action")) return false;
        info.actionType = XR_ACTION_TYPE_FLOAT_INPUT;
        std::strcpy(info.actionName, "scroll_y");
        std::strcpy(info.localizedActionName, "Remote mouse scroll");
        if (!xr_ok(xrCreateAction(set, &info, &scroll), "create scroll action")) return false;

        // Try each PICO generation, then the Khronos simple controller.
        // Profiles unsupported by older runtimes are harmlessly ignored.
        const char *profiles[] = {
            "/interaction_profiles/bytedance/pico4s_controller",
            "/interaction_profiles/bytedance/pico4_controller",
            "/interaction_profiles/bytedance/pico_neo3_controller",
            "/interaction_profiles/khr/simple_controller"
        };
        for (const auto *profile : profiles) {
            bool simple = std::strcmp(profile, "/interaction_profiles/khr/simple_controller") == 0;
            XrActionSuggestedBinding bindings[10] = {
                {aim, path(instance, "/user/hand/left/input/aim/pose")},
                {aim, path(instance, "/user/hand/right/input/aim/pose")},
                {click, path(instance, simple ? "/user/hand/left/input/select/click" : "/user/hand/left/input/trigger/click")},
                {click, path(instance, simple ? "/user/hand/right/input/select/click" : "/user/hand/right/input/trigger/click")},
                {scroll, path(instance, "/user/hand/left/input/thumbstick/y")},
                {scroll, path(instance, "/user/hand/right/input/thumbstick/y")},
                {grip,path(instance,"/user/hand/left/input/squeeze/click")},
                {grip,path(instance,"/user/hand/right/input/squeeze/click")},
                {cancel_follow,path(instance,"/user/hand/right/input/b/click")},
                {toggle_follow,path(instance,"/user/hand/right/input/a/click")}
            };
            XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
            suggested.interactionProfile = path(instance, profile);
            suggested.suggestedBindings = bindings;
            suggested.countSuggestedBindings = simple ? 4 : 10;
            if (suggested.interactionProfile != XR_NULL_PATH) {
                const auto result=xrSuggestInteractionProfileBindings(instance,&suggested);
                if(XR_FAILED(result) && !simple) {
                    LOGE("PICO profile A/B binding unavailable (%d); retaining pointer bindings",int(result));
                    suggested.countSuggestedBindings=8;
                    xrSuggestInteractionProfileBindings(instance,&suggested);
                }
            }
        }
        XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
        attach.countActionSets = 1;
        attach.actionSets = &set;
        if (!xr_ok(xrAttachSessionActionSets(session, &attach), "attach actions")) return false;
        for (int i = 0; i < 2; i++) {
            XrActionSpaceCreateInfo space{XR_TYPE_ACTION_SPACE_CREATE_INFO};
            space.action = aim;
            space.subactionPath = hand_paths[i];
            space.poseInActionSpace = identity_pose();
            if (!xr_ok(xrCreateActionSpace(session, &space, &hands[i]), "controller aim space")) return false;
        }
        return true;
    }

    void destroy() {
        for (auto hand : hands) if (hand != XR_NULL_HANDLE) xrDestroySpace(hand);
        if (set != XR_NULL_HANDLE) xrDestroyActionSet(set);
    }
};

static XrVector3f rotate_vector(const XrQuaternionf &q, XrVector3f v) {
    // v' = v + 2*q.xyz cross (q.xyz cross v + q.w*v)
    XrVector3f t{
        2.f * (q.y * v.z - q.z * v.y),
        2.f * (q.z * v.x - q.x * v.z),
        2.f * (q.x * v.y - q.y * v.x)
    };
    return {v.x + q.w*t.x + q.y*t.z - q.z*t.y,
            v.y + q.w*t.y + q.z*t.x - q.x*t.z,
            v.z + q.w*t.z + q.x*t.y - q.y*t.x};
}

static bool intersect_toolbar(const XrPosef &aim,float &u,float &v) {
    float x,y;
    if(!toolbar_available || !panel_ray(aim,menu_panel,x,y)) return false;
    u=x/menu_panel.width+.5f;v=.5f-y/(menu_panel.width*menu_panel.aspect);
    return u>=0 && u<=1 && v>=0 && v<=1;
}

static bool intersect_quad(const XrPosef &aim, float &u, float &v, int &pane) {
    const uint32_t visible=window_visible_mask.load();
    for(int i=0;i<6;++i) {
        if(!(visible&(1u<<i))) continue;
        float x,y;
        if(!panel_ray(aim,panels[i],x,y)) continue;
        const float w=panels[i].width,h=w*panels[i].aspect;
        if(frame_available && y>h*.5f && y<h*.58f && x>w*.24f && x<w*.37f) {pane=43+i;u=x;v=y;return true;}
        if(frame_available && y>h*.5f && y<h*.58f) {
            if(x>-w*.44f && x<-w*.26f) {pane=49+i;u=x;v=y;return true;}
            if(x>-w*.23f && x<-w*.18f) {pane=67+i;u=x;v=y;return true;}
            if(x>-w*.17f && x<-w*.12f) {pane=73+i;u=x;v=y;return true;}
            if(x>-w*.10f && x<w*.23f) {pane=55+i;u=x;v=y;return true;}
            if(x>w*.39f && x<w*.44f) {pane=61+i;u=x;v=y;return true;}
        }
        // All four corner handles, close button and a separate grab rail.
        if(frame_available && std::fabs(x)>=w*.38f && std::fabs(x)<=w*.53f && std::fabs(y)>=h*.42f && std::fabs(y)<=h*.58f) {
            const int corner=(y<0?2:0)+(x>0?1:0);pane=13+i*4+corner;u=x;v=y;return true;
        }
        if(frame_available && y < -h/2 && y >= -h*.58f) {
            if(x>w*.27f && x<w*.36f) {pane=37+i;u=x;v=y;return true;}
            if(std::fabs(x)<=w*.25f) {pane=7+i;u=x/w+.5f;v=.5f-y/h;return true;}
        }
        u=x/w+.5f;v=.5f-y/h;
        if(u>=0 && u<=1 && v>=0 && v<=1) {pane=i;return true;}
    }
    return false;
}

static void process_input(JNIEnv *env, jobject activity, jmethodID method, jmethodID scroll_method, jmethodID hover_method, jmethodID menu_method, jmethodID window_close, jmethodID window_menu,
                          XrSession session, XrSpace world, XrSpace menu_space, XrSpace view_space, Actions &actions,
                          XrTime timestamp, bool &last_down, float &old_u, float &old_v,
                          int &last_pane, XrTime &last_scroll_time) {
    ray_visible[0]=ray_visible[1]=false;
    XrActiveActionSet active{actions.set, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    if (XR_FAILED(xrSyncActions(session, &sync))) return;
    XrActionStateGetInfo back_query{XR_TYPE_ACTION_STATE_GET_INFO};
    back_query.action=actions.cancel_follow;back_query.subactionPath=actions.hand_paths[1];
    XrActionStateBoolean back{XR_TYPE_ACTION_STATE_BOOLEAN};
    if(XR_SUCCEEDED(xrGetActionStateBoolean(session,&back_query,&back)) && back.isActive && back.currentState && back.changedSinceLastSync) {
        follow_view.store(false);grabbed_pane=-1;menu_grab=false;interaction_hand=-1;
        if(last_down && last_pane<6) env->CallVoidMethod(activity,method,last_pane,old_u,old_v,JNI_FALSE);
        last_down=false;return;
    }
    XrActionStateGetInfo follow_query{XR_TYPE_ACTION_STATE_GET_INFO};
    follow_query.action=actions.toggle_follow;follow_query.subactionPath=actions.hand_paths[1];
    XrActionStateBoolean toggle{XR_TYPE_ACTION_STATE_BOOLEAN};
    if(XR_SUCCEEDED(xrGetActionStateBoolean(session,&follow_query,&toggle)) && toggle.isActive && toggle.currentState && toggle.changedSinceLastSync) {
        if(follow_view.load()) follow_view.store(false);
        else {
            std::lock_guard<std::mutex> lock(follow_pose_mutex);
            XrSpaceLocation offset{XR_TYPE_SPACE_LOCATION};
            const auto valid=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
            const auto click_time=toggle.lastChangeTime>0?toggle.lastChangeTime:timestamp;
            follow_requested_valid=XR_SUCCEEDED(xrLocateSpace(world,view_space,click_time,&offset)) && (offset.locationFlags&valid)==valid;
            if(!follow_requested_valid)
                follow_requested_valid=XR_SUCCEEDED(xrLocateSpace(world,view_space,timestamp,&offset)) && (offset.locationFlags&valid)==valid;
            if(follow_requested_valid) {follow_requested_offset=offset.pose;follow_view.store(true);}
            else LOGE("A follow: camera-relative tracking unavailable; keeping world layout");
        }
        if(last_down && last_pane<6) env->CallVoidMethod(activity,method,last_pane,old_u,old_v,JNI_FALSE);
        last_down=false;grabbed_pane=-1;menu_grab=false;menu_grip=false;grip_dragging=false;interaction_hand=-1;
        trigger_blocked[0]=trigger_blocked[1]=true;grip_holds[0].reset();grip_holds[1].reset();return;
    }
    // Evaluate BOTH hands before choosing. An idle right-hand desktop hover
    // must never starve a left-hand menu click (or vice versa).
    XrPointerIntent intents[2];
    XrSpaceLocation eye{XR_TYPE_SPACE_LOCATION};
    const auto eye_flags=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    const bool eye_valid=XR_SUCCEEDED(xrLocateSpace(view_space,world,timestamp,&eye)) && (eye.locationFlags&eye_flags)==eye_flags;
    for(int hand=0;hand<2;++hand) {
        XrSpaceLocation aim{XR_TYPE_SPACE_LOCATION};
        const auto flags=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
        if(XR_FAILED(xrLocateSpace(actions.hands[hand],world,timestamp,&aim)) || (aim.locationFlags&flags)!=flags) continue;
        intents[hand].tracked=true;
        float u=0,v=0;int target=-1;
        intents[hand].menu=intersect_toolbar(aim.pose,u,v);
        intents[hand].hit=intents[hand].menu;
        if(intents[hand].menu) target=6;
        else intents[hand].hit=intersect_quad(aim.pose,u,v,target);
        XrActionStateGetInfo query{XR_TYPE_ACTION_STATE_GET_INFO};query.subactionPath=actions.hand_paths[hand];
        XrActionStateBoolean trigger{XR_TYPE_ACTION_STATE_BOOLEAN},grip{XR_TYPE_ACTION_STATE_BOOLEAN};
        query.action=actions.click;xrGetActionStateBoolean(session,&query,&trigger);
        query.action=actions.grip;xrGetActionStateBoolean(session,&query,&grip);
        intents[hand].pressed=(trigger.isActive && trigger.currentState) || (grip.isActive && grip.currentState);
        intents[hand].rising=(trigger.changedSinceLastSync && trigger.currentState) || (grip.changedSinceLastSync && grip.currentState);
        focus_pressed[hand]=intents[hand].pressed;
        if(eye_valid && !xr_stream_content(intents[hand].hit,target)) {
            float distance=2;
            const int owner=xr_control_owner(target);
            const PanelGeometry *panel=target==6?&menu_panel:(owner>=0?&panels[owner]:nullptr);
            float x=0,y=0;
            if(panel && panel_ray(aim.pose,*panel,x,y)) {
                const auto point=panel_point(*panel,x,y);
                const auto dx=point.x-aim.pose.position.x,dy=point.y-aim.pose.position.y,dz=point.z-aim.pose.position.z;
                distance=std::sqrt(dx*dx+dy*dy+dz*dz);
            }
            update_free_ray(hand,aim.pose,eye.pose,distance);
        }
    }
    const int selected_hand=xr_choose_pointer(intents,interaction_hand,preferred_hand);
    if(selected_hand>=0) preferred_hand=selected_hand;
    for (int hand : {selected_hand}) {
        if(hand<0) continue;
        XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
        if (XR_FAILED(xrLocateSpace(actions.hands[hand], world, timestamp, &location)) ||
            (location.locationFlags & (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) !=
            (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) continue;
        float u = 0.f, v = 0.f;
        int pane = 0;
        XrSpaceLocation menu_location{XR_TYPE_SPACE_LOCATION};
        bool hit=false;
        if (grabbed_pane<0 && XR_SUCCEEDED(xrLocateSpace(actions.hands[hand],menu_space,timestamp,&menu_location)) &&
            (menu_location.locationFlags & (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) ==
            (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
            hit=intersect_toolbar(menu_location.pose,u,v); if(hit) pane=6;
        }
        if(!hit) hit=intersect_quad(location.pose,u,v,pane);
        XrActionStateGetInfo grip_query{XR_TYPE_ACTION_STATE_GET_INFO};
        grip_query.action=actions.grip;grip_query.subactionPath=actions.hand_paths[hand];
        XrActionStateBoolean grip_state{XR_TYPE_ACTION_STATE_BOOLEAN};
        const bool grip_held=XR_SUCCEEDED(xrGetActionStateBoolean(session,&grip_query,&grip_state)) && grip_state.isActive && grip_state.currentState;
        if(grip_held) trigger_blocked[hand]=true;
        if(grip_held && hit && pane==6 && !menu_grab && grabbed_pane<0) {
            if(last_down && last_pane<6) env->CallVoidMethod(activity,method,last_pane,old_u,old_v,JNI_FALSE);
            last_down=false;menu_grab=true;menu_grip=true;interaction_hand=hand;grab_u=u;grab_v=v;
            XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
            if(XR_SUCCEEDED(xrLocateSpace(view_space,menu_space,timestamp,&head)))
                menu_grab_origin={head.pose.position.x,head.pose.position.y,head.pose.position.z};
            menu_grab_radius=orbit_radius(menu_panel,menu_grab_origin,u,v,menu_panel.aspect);
        }
        const int pointed=hit?(pane<6?pane:(pane>=7 && pane<=12?pane-7:(pane>=13 && pane<=36?(pane-13)/4:-1))):-1;
        const int grip_target=grip_holds[hand].update(grip_held,pointed,timestamp);
        if(grip_target>=0 && grabbed_pane<0 && !menu_grab) {
            if(last_down && last_pane<6) env->CallVoidMethod(activity,method,last_pane,old_u,old_v,JNI_FALSE);
            last_down=false;grip_dragging=true;interaction_hand=hand;
            pane=7+grip_target;hit=true;
            float x=0,y=0;
            if(panel_ray(location.pose,panels[grip_target],x,y)) {
                u=x/panels[grip_target].width+.5f;v=.5f-y/(panels[grip_target].width*panels[grip_target].aspect);
            }
        }
        if(grip_held && grip_target<0 && !grip_dragging && grabbed_pane<0 && !menu_grab) {
            if(last_down && last_pane<6) env->CallVoidMethod(activity,method,last_pane,old_u,old_v,JNI_FALSE);
            last_down=false;
            if(pointed>=0) interaction_hand=hand;
            if(hover_method) env->CallVoidMethod(activity,hover_method,pane,u,v,JNI_FALSE);
            return; // grip outside a window must not leak a remote click
        }
        if(!grip_held && !grip_dragging && grabbed_pane<0 && !menu_grab && !last_down) interaction_hand=-1;
        if (!hit && grabbed_pane < 0 && !menu_grab) continue;
        if (!hit) pane = resizing ? 13+grabbed_pane*4+resize_corner : 7+grabbed_pane;
        XrActionStateGetInfo query{XR_TYPE_ACTION_STATE_GET_INFO};
        query.action = actions.click;
        query.subactionPath = actions.hand_paths[hand];
        XrActionStateBoolean state{XR_TYPE_ACTION_STATE_BOOLEAN};
        if (XR_FAILED(xrGetActionStateBoolean(session, &query, &state))) continue;
        const bool trigger_down=state.isActive && state.currentState == XR_TRUE;
        if(!trigger_down) trigger_blocked[hand]=false;
        bool down = (grip_dragging || (menu_grab && menu_grip))?grip_held:(trigger_down && !trigger_blocked[hand]);
        if (hover_method) env->CallVoidMethod(activity,hover_method,grip_dragging?(7+(grabbed_pane>=0?grabbed_pane:grip_target)):pane,u,v,down?JNI_TRUE:JNI_FALSE);
        if(menu_grab || (!grip_dragging && pane==6 && v>=.92f)) {
            if(down && !menu_grab && state.changedSinceLastSync) {
                menu_grab=true;menu_grip=false;interaction_hand=hand;grab_u=u;grab_v=v;
                XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
                if(XR_SUCCEEDED(xrLocateSpace(view_space,menu_space,timestamp,&head))) menu_grab_origin={head.pose.position.x,head.pose.position.y,head.pose.position.z};
                menu_grab_radius=orbit_radius(menu_panel,menu_grab_origin,u,v,menu_panel.aspect);
            }
            if(menu_grab && down) {
                XrActionStateGetInfo q{XR_TYPE_ACTION_STATE_GET_INFO};q.action=actions.scroll;q.subactionPath=actions.hand_paths[hand];
                XrActionStateFloat axis{XR_TYPE_ACTION_STATE_FLOAT};
                if(XR_SUCCEEDED(xrGetActionStateFloat(session,&q,&axis)) && axis.isActive && std::fabs(axis.currentState)>.25f &&
                    (last_scroll_time==0 || timestamp-last_scroll_time>30'000'000)) {
                    menu_grab_radius=std::clamp(menu_grab_radius+axis.currentState*.035f,.45f,6.f);
                    last_scroll_time=timestamp;
                }
                const auto d=rotate_vector(menu_location.pose.orientation,{0,0,-1});
                orbit_drag(menu_panel,{menu_location.pose.position.x,menu_location.pose.position.y,menu_location.pose.position.z},
                    {d.x,d.y,d.z},menu_grab_origin,menu_grab_radius,grab_u,grab_v,menu_panel.aspect);
            }
            if(!down) {menu_grab=false;menu_grip=false;interaction_hand=-1;}
            last_down=down;last_pane=6;return;
        }
        // Only a real trigger rising edge selects a menu item. Ray/space changes
        // while held must not synthesize another click.
        if(!grip_dragging && xr_menu_click(pane,down,state.changedSinceLastSync) && menu_method) {
            // Sample layout-in-view at the actual trigger timestamp, not at the
            // menu origin or a later Java callback/frame.
            std::lock_guard<std::mutex> lock(follow_pose_mutex);
            XrSpaceLocation offset{XR_TYPE_SPACE_LOCATION};
            const XrTime click_time=state.lastChangeTime>0?state.lastChangeTime:timestamp;
            follow_click_valid=XR_SUCCEEDED(xrLocateSpace(world,view_space,click_time,&offset)) &&
                (offset.locationFlags & (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) ==
                (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT);
            if(follow_click_valid) follow_click_offset=offset.pose;
            env->CallVoidMethod(activity,menu_method,u,v);
        }
        if(pane>=49 && pane<79 && !grip_dragging) {
            if(down && state.changedSinceLastSync && window_menu)
                env->CallVoidMethod(activity,window_menu,(pane-49)%6,(pane-49)/6+1);
            last_down=down;last_pane=pane;return;
        }
        if(pane>=43 && pane<49 && !grip_dragging) {
            if(down && state.changedSinceLastSync && window_menu) {
                menu_host.store(pane-43);
                const auto &p=panels[pane-43];const auto point=panel_point(p,0,p.width*p.aspect/2-.275f);
                const auto normal=panel_normal(p);
                menu_panel={point.x+normal.x*.015f,point.y+normal.y*.015f,1.65f,p.yaw,point.z+normal.z*.015f,1.f/3.f,p.pitch};
                env->CallVoidMethod(activity,window_menu,pane-43,0);
            }
            last_down=down;last_pane=pane;return;
        }
        if(pane>=37 && !grip_dragging) {
            if(down && state.changedSinceLastSync && window_close) {
                window_visible_mask.fetch_and(~(1u<<(pane-37)));
                env->CallVoidMethod(activity,window_close,pane-37);
            }
            last_down=down;last_pane=pane;return;
        }
        if (grabbed_pane >= 0 || pane >= 7) {
            if (last_down && last_pane < 6) {
                env->CallVoidMethod(activity,method,last_pane,old_u,old_v,JNI_FALSE);
                last_down=false;
            }
            const auto d=rotate_vector(location.pose.orientation,{0.f,0.f,-1.f});
            const float hand_yaw=std::atan2(-d.x,-d.z);
            if (grabbed_pane < 0 && down && !last_down) {
                interaction_hand=hand;
                grabbed_pane=pane>=13?(pane-13)/4:pane-7; resizing=pane>=13;
                resize_corner=pane>=13?(pane-13)%4:3;
                const auto &p=panels[grabbed_pane];
                const float sx=resize_corner%2?1.f:-1.f,sy=resize_corner<2?1.f:-1.f;
                const auto anchor=panel_point(p,-sx*p.width/2,-sy*p.width*p.aspect/2);
                resize_anchor_x=anchor.x;resize_anchor_y=anchor.y;resize_anchor_z=anchor.z;
                XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
                grab_depth_direction={0.f,0.f,-1.f};
                if(XR_SUCCEEDED(xrLocateSpace(view_space,world,timestamp,&head)) && (head.locationFlags&XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
                    grab_depth_direction=rotate_vector(head.pose.orientation,{0.f,0.f,-1.f});grab_depth_origin=head.pose.position;
                }
                window_grab_radius=orbit_radius(p,{grab_depth_origin.x,grab_depth_origin.y,grab_depth_origin.z},u,v,p.aspect);
                grab_u=u; grab_v=v; grab_yaw=panels[grabbed_pane].yaw; grab_hand_yaw=hand_yaw;
            }
            if(grabbed_pane>=0 && down && !resizing) {
                XrActionStateGetInfo q{XR_TYPE_ACTION_STATE_GET_INFO};q.action=actions.scroll;q.subactionPath=actions.hand_paths[hand];
                XrActionStateFloat axis{XR_TYPE_ACTION_STATE_FLOAT};
                if(XR_SUCCEEDED(xrGetActionStateFloat(session,&q,&axis)) && axis.isActive && std::fabs(axis.currentState)>.25f &&
                    (last_scroll_time==0 || timestamp-last_scroll_time>30'000'000)) {
                    auto &p=panels[grabbed_pane];
                    // Positive stick pushes away; negative pulls nearer. Preserve
                    // the grab anchor: the ray intersection below recomputes x/y.
                    window_grab_radius=std::clamp(window_grab_radius+axis.currentState*.035f,.45f,6.f);
                    last_scroll_time=timestamp;
                }
            }
            if(grabbed_pane>=0 && down) {
                auto &p=panels[grabbed_pane];
                float x,y;
                if(!resizing || panel_ray(location.pose,p,x,y)) {
                    if(resizing) {
                        const float sx=resize_corner%2?1.f:-1.f,sy=resize_corner<2?1.f:-1.f;
                        const PanelVector delta{p.x-resize_anchor_x,p.y-resize_anchor_y,p.z-resize_anchor_z};
                        const float dx=panel_dot(delta,panel_right(p))+x;
                        const float dy=panel_dot(delta,panel_up(p))+y;
                        p.width=std::clamp(std::max(sx*dx,sy*dy/p.aspect),.4f,panel_max_width(p.aspect));
                        const auto r=panel_right(p),up=panel_up(p);
                        p.x=resize_anchor_x+r.x*sx*p.width/2+up.x*sy*p.width*p.aspect/2;
                        p.y=resize_anchor_y+r.y*sx*p.width/2+up.y*sy*p.width*p.aspect/2;
                        p.z=resize_anchor_z+r.z*sx*p.width/2+up.z*sy*p.width*p.aspect/2;
                    }
                    else {
                        const auto d=rotate_vector(location.pose.orientation,{0,0,-1});
                        orbit_drag(p,{location.pose.position.x,location.pose.position.y,location.pose.position.z},
                            {d.x,d.y,d.z},{grab_depth_origin.x,grab_depth_origin.y,grab_depth_origin.z},window_grab_radius,grab_u,grab_v,p.aspect);
                    }
                }
            }
            if(!down) {grabbed_pane=-1;interaction_hand=-1;grip_dragging=false;}
            last_down=down;last_pane=pane;old_u=u;old_v=v;
            return;
        }
        if(pane<6 && down && !last_down) interaction_hand=hand;
        if (pane != last_pane && last_down) {
            env->CallVoidMethod(activity, method, last_pane, old_u, old_v, JNI_FALSE);
            last_down = false;
            down = false; // never carry a drag across different remote desktops
        }
        if (pane != last_pane || down != last_down ||
            std::fabs(u-old_u) >= 0.002f || std::fabs(v-old_v) >= 0.002f) {
            env->CallVoidMethod(activity, method, pane, u, v, down ? JNI_TRUE : JNI_FALSE);
            old_u = u; old_v = v; last_down = down; last_pane = pane;
        }
        // PICO4/PICO4S/Neo3: thumbstick vertical axis maps to the UU
        // mouse_scroll action. Dead zone and 120ms repeat protect against
        // noise and flooding the remote host with wheel events.
        XrActionStateGetInfo scroll_query{XR_TYPE_ACTION_STATE_GET_INFO};
        scroll_query.action = actions.scroll;
        scroll_query.subactionPath = actions.hand_paths[hand];
        XrActionStateFloat scroll_state{XR_TYPE_ACTION_STATE_FLOAT};
        if (pane < 6 && scroll_method && XR_SUCCEEDED(xrGetActionStateFloat(session, &scroll_query, &scroll_state)) &&
            scroll_state.isActive && std::fabs(scroll_state.currentState) >= 0.45f) {
            constexpr XrTime scroll_period = 120'000'000; // nanoseconds
            if (last_scroll_time == 0 || timestamp - last_scroll_time >= scroll_period) {
                jint delta = scroll_state.currentState >= 0.f ? 120 : -120;
                env->CallVoidMethod(activity, scroll_method, pane, delta);
                last_scroll_time = timestamp;
            }
        } else {
            last_scroll_time = 0;
        }
        return;
    }
    if (hover_method) env->CallVoidMethod(activity,hover_method,-1,0.f,0.f,JNI_FALSE);
    grabbed_pane=-1;menu_grab=false;interaction_hand=-1;grip_dragging=false;grip_holds[0].reset();grip_holds[1].reset();
    if (last_down) {
        env->CallVoidMethod(activity, method, last_pane, old_u, old_v, JNI_FALSE);
        last_down = false;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_openuuyc_pico_PicoActivity_nativeStop(JNIEnv *, jobject) {
    stop_requested.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_org_openuuyc_pico_PicoActivity_nativeSetMenuPortrait(JNIEnv *,jobject,jboolean portrait) {menu_portrait.store(portrait==JNI_TRUE);}

extern "C" JNIEXPORT void JNICALL
Java_org_openuuyc_pico_PicoActivity_nativeDockMenu(JNIEnv *,jobject,jint pane) {
    menu_host.store(pane);
    if(pane<0) menu_reset_requested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_org_openuuyc_pico_PicoActivity_nativeSetWindowAspect(JNIEnv *,jobject,jint pane,jint width,jint height) {
    if(pane>=0 && pane<6 && width>0 && height>0) panel_aspects[pane].store(std::clamp(float(height)/width,.125f,8.f));
}

extern "C" JNIEXPORT void JNICALL
Java_org_openuuyc_pico_PicoActivity_nativeSetFollowView(JNIEnv *, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(follow_pose_mutex);
    if(enabled==JNI_TRUE) {follow_requested_offset=follow_click_offset;follow_requested_valid=follow_click_valid;}
    follow_view.store(enabled==JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_org_openuuyc_pico_PicoActivity_nativeResetLayout(JNIEnv *, jobject) { layout_reset.store(true); }

extern "C" JNIEXPORT void JNICALL
Java_org_openuuyc_pico_PicoActivity_nativeSetWindowVisible(JNIEnv *, jobject, jint pane, jboolean visible) {
    if (pane < 0 || pane >= 6) return;
    const uint32_t bit = 1u << pane;
    if (visible) window_visible_mask.fetch_or(bit, std::memory_order_acq_rel);
    else window_visible_mask.fetch_and(~bit, std::memory_order_acq_rel);
}

extern "C" JNIEXPORT void JNICALL
Java_org_openuuyc_pico_PicoActivity_nativeSetPassthroughEnabled(JNIEnv *, jobject, jboolean enabled) {
    passthrough_requested.store(enabled == JNI_TRUE, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_org_openuuyc_pico_PicoActivity_nativeRun(JNIEnv *env, jobject, jobject activity) {
    stop_requested.store(false, std::memory_order_release);
    window_visible_mask.store(0u, std::memory_order_release);
    jclass klass = env->GetObjectClass(activity);
    jmethodID ready = env->GetMethodID(klass, "onXrSurface", "(ILandroid/view/Surface;)V");
    jmethodID pointer = env->GetMethodID(klass, "onPointer", "(IFFZ)V");
    jmethodID window_menu=env->GetMethodID(klass,"onXrWindowMenu","(II)V");
    jmethodID window_close=env->GetMethodID(klass,"onXrWindowClose","(I)V");
    jmethodID menu_method=env->GetMethodID(klass,"onXrMenuClick","(FF)V");
    jmethodID follow_report=env->GetMethodID(klass,"onXrFollowState","(Z)V");
    jmethodID hover_method = env->GetMethodID(klass,"onXrHover","(IFFZ)V");
    jmethodID scroll_method = env->GetMethodID(klass, "onScroll", "(II)V");
    jmethodID passthrough_report = env->GetMethodID(klass, "onPassthroughSupported", "(Z)V");
    JavaVM *vm = nullptr;
    env->GetJavaVM(&vm);

    XrInstance instance = XR_NULL_HANDLE;
    XrSession session = XR_NULL_HANDLE;
    XrSpace world = XR_NULL_HANDLE;
    XrSpace view = XR_NULL_HANDLE;
    XrSpace frozen = XR_NULL_HANDLE;
    bool actual_follow=false;
    XrSpace following_space=XR_NULL_HANDLE;
    XrSwapchain swapchains[8] = {};
    grip_dragging=false;grip_holds[0].reset();grip_holds[1].reset();
    menu_host.store(-1);menu_reset_requested.store(false);menu_portrait.store(false);
    menu_panel={0.f,-.82f,1.65f};menu_grab=false;menu_grip=false;interaction_hand=-1;
    {std::lock_guard<std::mutex> lock(follow_pose_mutex);follow_click_valid=false;follow_requested_valid=false;}
    toolbar_available = false; frame_available=false; grabbed_pane=-1; follow_view.store(false); layout_reset.store(true);
    EGLState egl;
    Actions actions;
    bool running = false;
    bool input_focused = false;
    bool action_ready = false;
    bool previous_down = false;
    float previous_u = -1.f, previous_v = -1.f;
    int previous_pane = 0;
    XrTime last_scroll_time = 0;
    const char *failure = nullptr;

    do {
        PFN_xrInitializeLoaderKHR init_loader = nullptr;
        if (!xr_ok(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                     reinterpret_cast<PFN_xrVoidFunction *>(&init_loader)), "OpenXR loader") || !init_loader) {
            failure = "OpenXR Android loader unavailable"; break;
        }
        XrLoaderInitInfoAndroidKHR loader{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loader.applicationVM = vm;
        loader.applicationContext = activity;
        if (!xr_ok(init_loader(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR *>(&loader)), "initialize loader")) {
            failure = "OpenXR runtime not installed"; break;
        }
        const char *extensions[] = {
            XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
            XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
            XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME,
        };
        uint32_t count = 0;
        if (!xr_ok(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr), "query extensions")) {
            failure = "OpenXR extension enumeration failed"; break;
        }
        std::vector<XrExtensionProperties> supported(count, {XR_TYPE_EXTENSION_PROPERTIES});
        if (!xr_ok(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, supported.data()), "read extensions")) {
            failure = "OpenXR extension read failed"; break;
        }
        for (auto *needed : extensions) {
            bool found = false;
            for (const auto &ext : supported) found |= std::strcmp(ext.extensionName, needed) == 0;
            if (!found) { LOGE("Missing required extension: %s", needed); failure = "PICO OS lacks required OpenXR extension"; }
        }
        if (failure) break;
        XrInstanceCreateInfoAndroidKHR android_info{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        android_info.applicationVM = vm;
        android_info.applicationActivity = activity;
        XrInstanceCreateInfo create{XR_TYPE_INSTANCE_CREATE_INFO};
        create.next = &android_info;
        std::strcpy(create.applicationInfo.applicationName, "OpenUUYC PICO");
        std::strcpy(create.applicationInfo.engineName, "OpenUUYC");
        create.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
        create.enabledExtensionCount = 3;
        create.enabledExtensionNames = extensions;
        if (!xr_ok(xrCreateInstance(&create, &instance), "create instance")) {
            failure = "Cannot create OpenXR instance"; break;
        }
        XrSystemGetInfo get_system{XR_TYPE_SYSTEM_GET_INFO};
        get_system.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        XrSystemId system = XR_NULL_SYSTEM_ID;
        if (!xr_ok(xrGetSystem(instance, &get_system, &system), "get HMD")) {
            failure = "No OpenXR headset runtime"; break;
        }
        bool supports_alpha_passthrough = false;
        uint32_t mode_count = 0;
        if (XR_SUCCEEDED(xrEnumerateEnvironmentBlendModes(instance, system,
                XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &mode_count, nullptr)) && mode_count > 0) {
            std::vector<XrEnvironmentBlendMode> modes(mode_count);
            if (XR_SUCCEEDED(xrEnumerateEnvironmentBlendModes(instance, system,
                    XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, mode_count, &mode_count, modes.data()))) {
                supports_alpha_passthrough = std::find(modes.begin(), modes.end(),
                    XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND) != modes.end();
            }
        }
        if (passthrough_report)
            env->CallVoidMethod(activity, passthrough_report,
                                supports_alpha_passthrough ? JNI_TRUE : JNI_FALSE);
        PFN_xrGetOpenGLESGraphicsRequirementsKHR requirements = nullptr;
        xrGetInstanceProcAddr(instance, "xrGetOpenGLESGraphicsRequirementsKHR",
                              reinterpret_cast<PFN_xrVoidFunction *>(&requirements));
        if (!requirements) { failure = "GLES requirements unavailable"; break; }
        XrGraphicsRequirementsOpenGLESKHR graphics{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        if (!xr_ok(requirements(instance, system, &graphics), "GLES requirements")) {
            failure = "OpenGL ES unsupported"; break;
        }
        if (!egl.create()) { failure = "Cannot create OpenGL ES3 context"; break; }
        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display = egl.display;
        binding.config = egl.config;
        binding.context = egl.context;
        XrSessionCreateInfo session_info{XR_TYPE_SESSION_CREATE_INFO};
        session_info.next = &binding;
        session_info.systemId = system;
        if (!xr_ok(xrCreateSession(instance, &session_info, &session), "create XR session")) {
            failure = "Cannot start headset session"; break;
        }
        XrReferenceSpaceCreateInfo space_info{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        space_info.poseInReferenceSpace = identity_pose();
        if (!xr_ok(xrCreateReferenceSpace(session, &space_info, &world), "local reference space")) {
            failure = "No local tracking reference space"; break;
        }
        XrReferenceSpaceCreateInfo view_info{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        view_info.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_VIEW;view_info.poseInReferenceSpace=identity_pose();
        xrCreateReferenceSpace(session,&view_info,&view);
        action_ready = actions.create(instance, session);
        XrSystemProperties properties{XR_TYPE_SYSTEM_PROPERTIES};
        uint32_t max_layers = 1;
        uint32_t video_edge=1920;
        if (XR_SUCCEEDED(xrGetSystemProperties(instance, system, &properties))) {
            max_layers = std::min(16u, properties.graphicsProperties.maxLayerCount);
            video_edge=xr_video_edge(properties.graphicsProperties.maxSwapchainImageWidth,properties.graphicsProperties.maxSwapchainImageHeight);
        }
        if(video_edge==0) {failure="Runtime reports no usable video Surface size";break;}
        const uint32_t ray_budget=max_layers>4?std::min(2u,max_layers-4):0;
        const uint32_t panel_count=max_layers>=4?std::max(1u,std::min(6u,(max_layers-2-ray_budget)/2)):1u;
        PFN_xrCreateSwapchainAndroidSurfaceKHR create_surface = nullptr;
        xrGetInstanceProcAddr(instance, "xrCreateSwapchainAndroidSurfaceKHR",
            reinterpret_cast<PFN_xrVoidFunction *>(&create_surface));
        if (!create_surface) { failure = "Android Surface swapchain unavailable"; break; }
        XrSwapchainCreateInfo swapchain_info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        swapchain_info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        swapchain_info.width = 1920;
        swapchain_info.height = 1080;
        // Extension requires format/sampleCount/faceCount/arraySize/mipCount = 0.
        for (uint32_t slot = 0; slot < panel_count + (max_layers>=2?1:0) + (max_layers>=3?1:0); ++slot) {
            // Reserve the second runtime layer for the toolbar, then extra video.
            const uint32_t pane = slot == 0 ? 0 : slot == 1 ? 6 : slot == 2 ? 7 : slot-2;
            swapchain_info.width = pane == 7 ? 2400 : pane == 6 ? 1200 : video_edge;
            swapchain_info.height = pane == 6 ? 400 : pane == 7 ? 3232 : video_edge;
            jobject surface = nullptr;
            if (!xr_ok(create_surface(session, &swapchain_info, &swapchains[pane], &surface),
                       "create pane swapchain") || !surface) {
                if (pane == 0) failure = "Cannot create main video Surface";
                else LOGE("Auxiliary pane %u unavailable; continuing with fewer windows", pane);
                continue;
            }
            if (pane == 6) toolbar_available = true;
            if (pane == 7) frame_available = true;
            if (!notify_video_surface(env, activity, ready, static_cast<jint>(pane), surface)) {
                failure = "Android video Surface callback failed"; break;
            }
        }
        if (failure) break;
        while (!stop_requested.load(std::memory_order_acquire)) {
            XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
            while (xrPollEvent(instance, &event) == XR_SUCCESS) {
                if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                    auto *changed = reinterpret_cast<XrEventDataSessionStateChanged *>(&event);
                    // When the headset loses focus (system menu, sleep,
                    // passthrough interruption) release a held remote trigger
                    // immediately. A remote PC must not keep dragging while
                    // the user is no longer interacting with our XR layer.
                    input_focused = changed->state == XR_SESSION_STATE_FOCUSED;
                    if (!input_focused) {if(hover_method) env->CallVoidMethod(activity,hover_method,-1,0.f,0.f,JNI_FALSE);trigger_blocked[0]=trigger_blocked[1]=true;grabbed_pane=-1;menu_grab=false;interaction_hand=-1;grip_dragging=false;grip_holds[0].reset();grip_holds[1].reset();}
                    if (!input_focused && previous_down) {
                        env->CallVoidMethod(activity, pointer, previous_pane, previous_u, previous_v, JNI_FALSE);
                        previous_down = false;
                    }
                    if (changed->state == XR_SESSION_STATE_READY && !running) {
                        XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
                        begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                        running = XR_SUCCEEDED(xrBeginSession(session, &begin));
                    } else if (changed->state == XR_SESSION_STATE_STOPPING && running) {
                        xrEndSession(session);
                        running = false;
                    } else if (changed->state == XR_SESSION_STATE_EXITING ||
                               changed->state == XR_SESSION_STATE_LOSS_PENDING) {
                        stop_requested.store(true);
                    }
                }
                event = {XR_TYPE_EVENT_DATA_BUFFER};
            }
            if (!running) {
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                continue;
            }
            XrFrameWaitInfo wait_info{XR_TYPE_FRAME_WAIT_INFO};
            XrFrameState state{XR_TYPE_FRAME_STATE};
            if (XR_FAILED(xrWaitFrame(session, &wait_info, &state))) break;
            XrFrameBeginInfo begin{XR_TYPE_FRAME_BEGIN_INFO};
            if (XR_FAILED(xrBeginFrame(session, &begin))) break;
            // LOCAL is not necessarily the user's current position/facing (and
            // can be floor-height). Anchor initial/recovered layout at the eyes,
            // with yaw only, so the windows cannot start below/behind the user.
            if (layout_reset.load() && state.shouldRender) {
                XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
                const auto valid=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
                if(XR_SUCCEEDED(xrLocateSpace(view,world,state.predictedDisplayTime,&head)) &&
                    (head.locationFlags&valid)==valid) {
                    const auto forward=rotate_vector(head.pose.orientation,{0,0,-1});
                    const float yaw=panel_anchor_yaw(forward.x,forward.z);
                    XrReferenceSpaceCreateInfo anchor{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
                    anchor.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;
                    anchor.poseInReferenceSpace=head.pose;
                    anchor.poseInReferenceSpace.orientation={0,std::sin(yaw/2),0,std::cos(yaw/2)};
                    XrSpace next=XR_NULL_HANDLE;
                    if(XR_SUCCEEDED(xrCreateReferenceSpace(session,&anchor,&next))) {
                        if(frozen!=XR_NULL_HANDLE) xrDestroySpace(frozen);
                        frozen=next; actual_follow=false;follow_view.store(false);
                        {std::lock_guard<std::mutex> lock(follow_pose_mutex);follow_click_valid=false;follow_requested_valid=false;}
                        panels[0]={0.f,0.f,1.65f}; panels[1]={-1.55f,0.f,1.25f}; panels[2]={1.55f,0.f,1.25f};
                        panels[3]={0.f,1.3f,1.25f};panels[4]={-1.55f,1.3f,1.25f};panels[5]={1.55f,1.3f,1.25f};
                        menu_panel={0,-.82f,1.65f};menu_host.store(-1);
                        grabbed_pane=-1;menu_grab=false;layout_reset.store(false);
                        if(follow_report) env->CallVoidMethod(activity,follow_report,JNI_FALSE);
                    }
                }
            }
            if(menu_reset_requested.exchange(false)) menu_panel={0,-.82f,1.65f};
            menu_panel.width=menu_portrait.load()?.85f:1.65f;
            menu_panel.aspect=menu_portrait.load()?1000.f/600.f:1.f/3.f;
            for(int i=0;i<6;++i) {panels[i].aspect=panel_aspects[i].load();panels[i].width=std::min(panels[i].width,panel_max_width(panels[i].aspect));}
            XrCompositionLayerQuad quads[16]{};
            const XrCompositionLayerBaseHeader *layers[16]{};
            const bool requested_follow=follow_view.load();
            if(requested_follow != actual_follow) {
                bool adopted=false;
                if(requested_follow && view!=XR_NULL_HANDLE) {
                    std::lock_guard<std::mutex> lock(follow_pose_mutex);
                    XrReferenceSpaceCreateInfo attached{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
                    attached.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;
                    XrSpaceLocation camera{XR_TYPE_SPACE_LOCATION};
                    const auto flags=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
                    const bool camera_valid=XR_SUCCEEDED(xrLocateSpace(view,world,state.predictedDisplayTime,&camera)) && (camera.locationFlags&flags)==flags;
                    attached.poseInReferenceSpace=compose_xr_pose(camera.pose,follow_requested_offset);
                    XrSpace next=XR_NULL_HANDLE;
                    if(camera_valid && follow_requested_valid && XR_SUCCEEDED(xrCreateReferenceSpace(session,&attached,&next))) {
                        if(following_space!=XR_NULL_HANDLE) xrDestroySpace(following_space);
                        following_space=next;actual_follow=true;adopted=true;
                    }
                }
                else if(!requested_follow) {
                    XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
                    if(XR_SUCCEEDED(xrLocateSpace(following_space,world,state.predictedDisplayTime,&head)) &&
                        (head.locationFlags & (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) ==
                        (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
                        XrReferenceSpaceCreateInfo anchor{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
                        anchor.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;anchor.poseInReferenceSpace=head.pose;
                        XrSpace next=XR_NULL_HANDLE;
                        if(XR_SUCCEEDED(xrCreateReferenceSpace(session,&anchor,&next))) {
                            if(frozen!=XR_NULL_HANDLE) xrDestroySpace(frozen);
                            frozen=next;actual_follow=false;adopted=true;
                        }
                    }
                }
                grabbed_pane=-1;
                if(!adopted) follow_view.store(actual_follow);
                if(follow_report) env->CallVoidMethod(activity,follow_report,actual_follow?JNI_TRUE:JNI_FALSE);
            }
            // Always derive from the immutable camera-relative capture and
            // current LOCAL camera pose. Never feed last frame's follow pose
            // back into the next frame (nor create offset VIEW reference spaces).
            if(actual_follow) {
                XrSpaceLocation camera{XR_TYPE_SPACE_LOCATION};
                const auto flags=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
                if(XR_SUCCEEDED(xrLocateSpace(view,world,state.predictedDisplayTime,&camera)) && (camera.locationFlags&flags)==flags) {
                    XrReferenceSpaceCreateInfo anchor{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
                    anchor.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;
                    {std::lock_guard<std::mutex> lock(follow_pose_mutex);anchor.poseInReferenceSpace=compose_xr_pose(camera.pose,follow_requested_offset);}
                    XrSpace next=XR_NULL_HANDLE;
                    if(XR_SUCCEEDED(xrCreateReferenceSpace(session,&anchor,&next))) {
                        if(following_space!=XR_NULL_HANDLE) xrDestroySpace(following_space);
                        following_space=next;
                    }
                }
            }
            const XrSpace active_space=actual_follow?following_space:(frozen!=XR_NULL_HANDLE?frozen:world);
            if (action_ready && input_focused && state.shouldRender) {
                process_input(env, activity, pointer, scroll_method, hover_method, menu_method, window_close, window_menu, session, active_space, active_space, view, actions,
                              state.predictedDisplayTime, previous_down, previous_u, previous_v,
                              previous_pane, last_scroll_time);
            } else if (previous_down) {
                env->CallVoidMethod(activity, pointer, previous_pane, previous_u, previous_v, JNI_FALSE);
                previous_down = false;
            }
            if(!input_focused || !state.shouldRender) ray_visible[0]=ray_visible[1]=false;
            uint32_t layer_count = 0;
            if(frame_available && max_layers>=4) {
                auto &bg=quads[layer_count];bg.type=XR_TYPE_COMPOSITION_LAYER_QUAD;bg.space=view;
                bg.eyeVisibility=XR_EYE_VISIBILITY_BOTH;bg.pose=identity_pose(-10);bg.size={40,26.666667f};
                bg.layerFlags=XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                bg.subImage.swapchain=swapchains[7];bg.subImage.imageRect={{0,2432},{1200,800}};
                layers[layer_count++]=reinterpret_cast<const XrCompositionLayerBaseHeader *>(&bg);
            }
            const uint32_t visible = window_visible_mask.load(std::memory_order_acquire);
            for (uint32_t index = 0; index < 7; ++index) {
                if (swapchains[index] == XR_NULL_HANDLE || (index != 6 && !(visible & (1u << index))))
                    continue;
                auto &quad = quads[layer_count];
                quad.type = XR_TYPE_COMPOSITION_LAYER_QUAD;
                quad.space = index==6?(active_space):active_space;
                quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                quad.pose = identity_pose(index == 6 ? menu_panel.z : panels[index].z);
                quad.pose.position.x = index == 6 ? menu_panel.x : panels[index].x;
                quad.pose.position.y = index == 6 ? menu_panel.y : panels[index].y;
                quad.pose.orientation=panel_orientation(index==6?menu_panel:panels[index]);
                quad.size.width = index == 6 ? menu_panel.width : panels[index].width;
                quad.size.height = index == 6 ? menu_panel.width*menu_panel.aspect : quad.size.width * panels[index].aspect;
                quad.subImage.swapchain = swapchains[index];
                quad.subImage.imageRect = index == 6 ? XrRect2Di{{0,0},{1200,400}} : XrRect2Di{{0,0},{static_cast<int32_t>(video_edge),static_cast<int32_t>(video_edge)}};
                quad.subImage.imageArrayIndex = 0;
                layers[layer_count++] = reinterpret_cast<const XrCompositionLayerBaseHeader *>(&quad);
            }
            if(frame_available) for(uint32_t i=0;i<6;++i) {
                if(swapchains[i]==XR_NULL_HANDLE || !(visible&(1u<<i))) continue;
                auto &frame=quads[layer_count];
                frame.type=XR_TYPE_COMPOSITION_LAYER_QUAD;
                frame.layerFlags=XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                frame.space=active_space;frame.eyeVisibility=XR_EYE_VISIBILITY_BOTH;
                frame.pose=identity_pose(panels[i].z);frame.pose.position.x=panels[i].x;frame.pose.position.y=panels[i].y;
                frame.pose.orientation=panel_orientation(panels[i]);
                frame.size={panels[i].width*1.06f,panels[i].width*panels[i].aspect*1.16f};
                frame.subImage.swapchain=swapchains[7];frame.subImage.imageRect={{static_cast<int32_t>(i%2)*1200,static_cast<int32_t>(i/2)*800},{1200,800}};
                layers[layer_count++]=reinterpret_cast<const XrCompositionLayerBaseHeader *>(&frame);
            }
            // Menu hit testing has priority; keep its composition layer topmost too.
            for(uint32_t i=0;i+1<layer_count;++i) {
                if(reinterpret_cast<const XrCompositionLayerQuad *>(layers[i])->subImage.swapchain==swapchains[6]) {
                    const auto *menu=layers[i];
                    for(uint32_t j=i;j+1<layer_count;++j) layers[j]=layers[j+1];
                    layers[layer_count-1]=menu;break;
                }
            }
            if(frame_available) {
                // One ray + one camera-facing cursor per pointer. With six
                // windows prioritize the active hand, without exceeding runtime
                // layer count; with spare layers show both complete pointers.
                for(int hand:{preferred_hand,1-preferred_hand}) if(ray_visible[hand] && layer_count+2<=max_layers) {
                    auto &ray=quads[layer_count];ray.type=XR_TYPE_COMPOSITION_LAYER_QUAD;
                    ray.space=active_space;ray.eyeVisibility=XR_EYE_VISIBILITY_BOTH;ray.pose=ray_poses[hand];
                    ray.size={.003f,ray_lengths[hand]};ray.subImage.swapchain=swapchains[7];ray.subImage.imageRect={{0,2400},{32,32}};
                    layers[layer_count++]=reinterpret_cast<const XrCompositionLayerBaseHeader *>(&ray);
                    auto &cursor=quads[layer_count];cursor.type=XR_TYPE_COMPOSITION_LAYER_QUAD;
                    cursor.layerFlags=XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                    cursor.space=active_space;cursor.eyeVisibility=XR_EYE_VISIBILITY_BOTH;cursor.pose=focus_poses[hand];
                    const float size=std::clamp(ray_lengths[hand]*.012f,.008f,.06f);cursor.size={size,size};
                    cursor.subImage.swapchain=swapchains[7];cursor.subImage.imageRect={{focus_pressed[hand]?64:32,2400},{32,32}};
                    layers[layer_count++]=reinterpret_cast<const XrCompositionLayerBaseHeader *>(&cursor);
                }
            }
            XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};
            end.displayTime = state.predictedDisplayTime;
            // PICO uses ALPHA_BLEND (mode 3) to compose OS-owned video
            // passthrough behind the remote desktop quads; the application
            // does not capture or expose private camera frames.
            end.environmentBlendMode = supports_alpha_passthrough &&
                passthrough_requested.load(std::memory_order_acquire)
                    ? XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND
                    : XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
            end.layerCount = state.shouldRender ? layer_count : 0u;
            end.layers = end.layerCount ? layers : nullptr;
            if (!xr_ok(xrEndFrame(session, &end), "submit composition layers")) break;
        }
    } while (false);

    // Normal session teardown can bypass STOPPING. Release any held input
    // *before* detaching the Java remote-control session and Surface.
    if (previous_down) {
        env->CallVoidMethod(activity, pointer, previous_pane, previous_u, previous_v, JNI_FALSE);
        previous_down = false;
    }
    if (running) xrEndSession(session);
    actions.destroy();
    for (auto chain : swapchains) if (chain != XR_NULL_HANDLE) xrDestroySwapchain(chain);
    if (following_space != XR_NULL_HANDLE) xrDestroySpace(following_space);
    if (frozen != XR_NULL_HANDLE) xrDestroySpace(frozen);
    if (view != XR_NULL_HANDLE) xrDestroySpace(view);
    if (world != XR_NULL_HANDLE) xrDestroySpace(world);
    if (session != XR_NULL_HANDLE) xrDestroySession(session);
    if (instance != XR_NULL_HANDLE) xrDestroyInstance(instance);
    egl.destroy();
    env->DeleteLocalRef(klass);
    if (failure && !stop_requested.load(std::memory_order_relaxed)) error(env, activity, failure);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_openuuyc_pico_PicoRuntimeProbe_extensions(JNIEnv *env, jobject, jobject context) {
    PFN_xrInitializeLoaderKHR initialize=nullptr;
    if(XR_FAILED(xrGetInstanceProcAddr(XR_NULL_HANDLE,"xrInitializeLoaderKHR",reinterpret_cast<PFN_xrVoidFunction *>(&initialize))) || !initialize)
        return env->NewStringUTF("error: loader unavailable");
    JavaVM *vm=nullptr;env->GetJavaVM(&vm);
    XrLoaderInitInfoAndroidKHR info{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    info.applicationVM=vm;info.applicationContext=context;
    if(XR_FAILED(initialize(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR *>(&info))))
        return env->NewStringUTF("error: loader initialize failed");
    uint32_t count=0;
    if(XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr,0,&count,nullptr)))
        return env->NewStringUTF("error: enumerate failed");
    std::vector<XrExtensionProperties> extensions(count,{XR_TYPE_EXTENSION_PROPERTIES});
    if(XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr,count,&count,extensions.data())))
        return env->NewStringUTF("error: inventory failed");
    std::string report="";
    for(const auto &e:extensions) {
        if(std::strstr(e.extensionName,"hand") || std::strstr(e.extensionName,"controller") || std::strstr(e.extensionName,"render_model")) {
            if(!report.empty()) report+="\n";
            report+=e.extensionName;
        }
    }
    return env->NewStringUTF(report.empty()?"No hand/controller/model extensions advertised":report.c_str());
}
