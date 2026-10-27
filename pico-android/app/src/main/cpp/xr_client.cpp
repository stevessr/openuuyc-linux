// PICO OpenXR native client. Stereo display comes from the compositor:
// up to three independent world-space quads can display separate UU RTP
// tracks, with each Android decoder producing into its own Surface.
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>
#include <thread>
#include <chrono>
#include <initializer_list>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OpenUUYC-PICO", __VA_ARGS__)
static std::atomic<bool> stop_requested{false};
static std::atomic<uint32_t> window_visible_mask{1u};
static std::atomic<bool> passthrough_requested{true};

static XrPosef identity_pose(float z = 0.f) {
    XrPosef pose{};
    pose.orientation.w = 1.f;
    pose.position.z = z;
    return pose;
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
            XrActionSuggestedBinding bindings[6] = {
                {aim, path(instance, "/user/hand/left/input/aim/pose")},
                {aim, path(instance, "/user/hand/right/input/aim/pose")},
                {click, path(instance, simple ? "/user/hand/left/input/select/click" : "/user/hand/left/input/trigger/click")},
                {click, path(instance, simple ? "/user/hand/right/input/select/click" : "/user/hand/right/input/trigger/click")},
                {scroll, path(instance, "/user/hand/left/input/thumbstick/y")},
                {scroll, path(instance, "/user/hand/right/input/thumbstick/y")}
            };
            XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
            suggested.interactionProfile = path(instance, profile);
            suggested.suggestedBindings = bindings;
            suggested.countSuggestedBindings = simple ? 4 : 6;
            if (suggested.interactionProfile != XR_NULL_PATH)
                xrSuggestInteractionProfileBindings(instance, &suggested);
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

static bool intersect_quad(const XrPosef &aim, float &u, float &v, int &pane) {
    XrVector3f direction = rotate_vector(aim.orientation, {0.f, 0.f, -1.f});
    if (std::fabs(direction.z) < 0.0001f) return false;
    const float t = (-2.f - aim.position.z) / direction.z;
    if (t <= 0.f) return false;
    const float x = aim.position.x + t * direction.x;
    const float y = aim.position.y + t * direction.y;
    const uint32_t visible = window_visible_mask.load(std::memory_order_acquire);
    for (int index = 0; index < 3; ++index) {
        if (!(visible & (1u << index))) continue;
        const float center = index == 1 ? -1.55f : index == 2 ? 1.55f : 0.f;
        const float width = index == 0 ? 1.65f : 1.25f;
        const float height = width * 9.f / 16.f;
        const float horizontal = (x - center) / width + 0.5f;
        const float vertical = 0.5f - y / height;
        if (horizontal >= 0.f && horizontal <= 1.f &&
            vertical >= 0.f && vertical <= 1.f) {
            pane = index;
            u = horizontal;
            v = vertical;
            return true;
        }
    }
    return false;
}

static void process_input(JNIEnv *env, jobject activity, jmethodID method, jmethodID scroll_method,
                          XrSession session, XrSpace world, Actions &actions,
                          XrTime timestamp, bool &last_down, float &old_u, float &old_v,
                          int &last_pane, XrTime &last_scroll_time) {
    XrActiveActionSet active{actions.set, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    if (XR_FAILED(xrSyncActions(session, &sync))) return;
    // Prefer right hand, fallback to left when not tracked.
    for (int hand : {1, 0}) {
        XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
        if (XR_FAILED(xrLocateSpace(actions.hands[hand], world, timestamp, &location)) ||
            (location.locationFlags & (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) !=
            (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) continue;
        float u = 0.f, v = 0.f;
        int pane = 0;
        if (!intersect_quad(location.pose, u, v, pane)) continue;
        XrActionStateGetInfo query{XR_TYPE_ACTION_STATE_GET_INFO};
        query.action = actions.click;
        query.subactionPath = actions.hand_paths[hand];
        XrActionStateBoolean state{XR_TYPE_ACTION_STATE_BOOLEAN};
        if (XR_FAILED(xrGetActionStateBoolean(session, &query, &state))) continue;
        bool down = state.isActive && state.currentState == XR_TRUE;
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
        if (scroll_method && XR_SUCCEEDED(xrGetActionStateFloat(session, &scroll_query, &scroll_state)) &&
            scroll_state.isActive && std::fabs(scroll_state.currentState) >= 0.45f) {
            constexpr XrTime scroll_period = 120'000'000; // nanoseconds
            if (last_scroll_time == 0 || timestamp - last_scroll_time >= scroll_period) {
                jint delta = scroll_state.currentState >= 0.f ? 120 : -120;
                env->CallVoidMethod(activity, scroll_method, delta);
                last_scroll_time = timestamp;
            }
        } else {
            last_scroll_time = 0;
        }
        return;
    }
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
Java_org_openuuyc_pico_PicoActivity_nativeSetWindowVisible(JNIEnv *, jobject, jint pane, jboolean visible) {
    if (pane < 0 || pane >= 3) return;
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
    window_visible_mask.store(1u, std::memory_order_release);
    jclass klass = env->GetObjectClass(activity);
    jmethodID ready = env->GetMethodID(klass, "onXrSurface", "(ILandroid/view/Surface;)V");
    jmethodID pointer = env->GetMethodID(klass, "onPointer", "(IFFZ)V");
    jmethodID scroll_method = env->GetMethodID(klass, "onScroll", "(I)V");
    jmethodID passthrough_report = env->GetMethodID(klass, "onPassthroughSupported", "(Z)V");
    JavaVM *vm = nullptr;
    env->GetJavaVM(&vm);

    XrInstance instance = XR_NULL_HANDLE;
    XrSession session = XR_NULL_HANDLE;
    XrSpace world = XR_NULL_HANDLE;
    XrSwapchain swapchains[3] = {XR_NULL_HANDLE, XR_NULL_HANDLE, XR_NULL_HANDLE};
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
        action_ready = actions.create(instance, session);
        XrSystemProperties properties{XR_TYPE_SYSTEM_PROPERTIES};
        uint32_t max_layers = 1;
        if (XR_SUCCEEDED(xrGetSystemProperties(instance, system, &properties)))
            max_layers = std::min(3u, properties.graphicsProperties.maxLayerCount);
        PFN_xrCreateSwapchainAndroidSurfaceKHR create_surface = nullptr;
        xrGetInstanceProcAddr(instance, "xrCreateSwapchainAndroidSurfaceKHR",
            reinterpret_cast<PFN_xrVoidFunction *>(&create_surface));
        if (!create_surface) { failure = "Android Surface swapchain unavailable"; break; }
        XrSwapchainCreateInfo swapchain_info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        swapchain_info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        swapchain_info.width = 1920;
        swapchain_info.height = 1080;
        // Extension requires format/sampleCount/faceCount/arraySize/mipCount = 0.
        for (uint32_t pane = 0; pane < max_layers; ++pane) {
            jobject surface = nullptr;
            if (!xr_ok(create_surface(session, &swapchain_info, &swapchains[pane], &surface),
                       "create pane swapchain") || !surface) {
                if (pane == 0) failure = "Cannot create main video Surface";
                else LOGE("Auxiliary pane %u unavailable; continuing with fewer windows", pane);
                continue;
            }
            env->CallVoidMethod(activity, ready, static_cast<jint>(pane), surface);
            env->DeleteLocalRef(surface);
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
            XrCompositionLayerQuad quads[3]{};
            const XrCompositionLayerBaseHeader *layers[3]{};
            uint32_t layer_count = 0;
            const uint32_t visible = window_visible_mask.load(std::memory_order_acquire);
            for (uint32_t index = 0; index < 3; ++index) {
                if (swapchains[index] == XR_NULL_HANDLE || !(visible & (1u << index)))
                    continue;
                auto &quad = quads[layer_count];
                quad.type = XR_TYPE_COMPOSITION_LAYER_QUAD;
                quad.space = world;
                quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                quad.pose = identity_pose(-2.f);
                quad.pose.position.x = index == 1 ? -1.55f : index == 2 ? 1.55f : 0.f;
                quad.size.width = index == 0 ? 1.65f : 1.25f;
                quad.size.height = quad.size.width * 9.f / 16.f;
                quad.subImage.swapchain = swapchains[index];
                quad.subImage.imageRect = {{0, 0}, {1920, 1080}};
                quad.subImage.imageArrayIndex = 0;
                layers[layer_count++] = reinterpret_cast<const XrCompositionLayerBaseHeader *>(&quad);
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
            if (action_ready && input_focused && state.shouldRender) {
                process_input(env, activity, pointer, scroll_method, session, world, actions,
                              state.predictedDisplayTime, previous_down, previous_u, previous_v,
                              previous_pane, last_scroll_time);
            } else if (previous_down) {
                env->CallVoidMethod(activity, pointer, previous_u, previous_v, JNI_FALSE);
                previous_down = false;
            }
            if (XR_FAILED(xrEndFrame(session, &end))) break;
        }
    } while (false);

    // Normal session teardown can bypass STOPPING. Release any held input
    // *before* detaching the Java remote-control session and Surface.
    if (previous_down) {
        env->CallVoidMethod(activity, pointer, previous_u, previous_v, JNI_FALSE);
        previous_down = false;
    }
    if (running) xrEndSession(session);
    actions.destroy();
    for (auto chain : swapchains) if (chain != XR_NULL_HANDLE) xrDestroySwapchain(chain);
    if (world != XR_NULL_HANDLE) xrDestroySpace(world);
    if (session != XR_NULL_HANDLE) xrDestroySession(session);
    if (instance != XR_NULL_HANDLE) xrDestroyInstance(instance);
    egl.destroy();
    env->DeleteLocalRef(klass);
    if (failure && !stop_requested.load(std::memory_order_relaxed)) error(env, activity, failure);
}
