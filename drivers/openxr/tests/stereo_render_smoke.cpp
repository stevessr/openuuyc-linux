// Synthetic colors + real OpenXR/GL compositor. NOT a headset/game acceptance test.
#define XR_USE_PLATFORM_XLIB
#define XR_USE_GRAPHICS_API_OPENGL
#define GL_GLEXT_PROTOTYPES
#include <X11/Xlib.h>
#include <GL/gl.h>
#include <GL/glx.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <cstdio>
#include <cstring>
#include <vector>
#include <unistd.h>
#define OK(call) do {XrResult result=(call);if(XR_FAILED(result)){std::fprintf(stderr,"%s: %d\n",#call,int(result));return 2;}} while(0)
int main() {
    Display *display=XOpenDisplay(nullptr);if(!display)return 3;
    int attributes[]={GLX_X_RENDERABLE,True,GLX_DRAWABLE_TYPE,GLX_PBUFFER_BIT|GLX_WINDOW_BIT,GLX_RENDER_TYPE,GLX_RGBA_BIT,GLX_RED_SIZE,8,GLX_GREEN_SIZE,8,GLX_BLUE_SIZE,8,None};
    int count=0;GLXFBConfig *configs=glXChooseFBConfig(display,DefaultScreen(display),attributes,&count);if(!configs||count==0)return 4;
    GLXContext context=glXCreateNewContext(display,configs[0],GLX_RGBA_TYPE,nullptr,True);if(!context)return 5;
    int pb[]={GLX_PBUFFER_WIDTH,16,GLX_PBUFFER_HEIGHT,16,None};
    GLXPbuffer drawable=glXCreatePbuffer(display,configs[0],pb);
    if(!glXMakeContextCurrent(display,drawable,drawable,context))return 6;
    int visual=0;glXGetFBConfigAttrib(display,configs[0],GLX_VISUAL_ID,&visual);
    XrInstanceCreateInfo create{XR_TYPE_INSTANCE_CREATE_INFO};create.applicationInfo.apiVersion=XR_MAKE_VERSION(1,0,0);
    std::strcpy(create.applicationInfo.applicationName,"OpenUUYC synthetic stereo fixture");
    const char *extensions[]={XR_KHR_OPENGL_ENABLE_EXTENSION_NAME};create.enabledExtensionCount=1;create.enabledExtensionNames=extensions;
    XrInstance instance;OK(xrCreateInstance(&create,&instance));
    XrSystemGetInfo info{XR_TYPE_SYSTEM_GET_INFO};info.formFactor=XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;XrSystemId system;OK(xrGetSystem(instance,&info,&system));
    PFN_xrGetOpenGLGraphicsRequirementsKHR requirements=nullptr;OK(xrGetInstanceProcAddr(instance,"xrGetOpenGLGraphicsRequirementsKHR",reinterpret_cast<PFN_xrVoidFunction*>(&requirements)));
    XrGraphicsRequirementsOpenGLKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_KHR};OK(requirements(instance,system,&req));
    XrGraphicsBindingOpenGLXlibKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_XLIB_KHR};binding.xDisplay=display;binding.visualid=visual;binding.glxFBConfig=configs[0];binding.glxDrawable=drawable;binding.glxContext=context;
    XrSessionCreateInfo session_info{XR_TYPE_SESSION_CREATE_INFO,&binding};session_info.systemId=system;XrSession session;OK(xrCreateSession(instance,&session_info,&session));
    XrReferenceSpaceCreateInfo reference{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};reference.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;reference.poseInReferenceSpace.orientation.w=1;XrSpace space;OK(xrCreateReferenceSpace(session,&reference,&space));
    uint32_t n=0;OK(xrEnumerateSwapchainFormats(session,0,&n,nullptr));std::vector<int64_t> formats(n);OK(xrEnumerateSwapchainFormats(session,n,&n,formats.data()));
    int64_t format=0;for(auto f:formats)if(f==GL_SRGB8_ALPHA8)format=f;if(!format)return 7;
    XrSwapchain swaps[2];std::vector<XrSwapchainImageOpenGLKHR> images[2];
    for(int eye=0;eye<2;eye++) {
        XrSwapchainCreateInfo sci{XR_TYPE_SWAPCHAIN_CREATE_INFO};sci.usageFlags=XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT|XR_SWAPCHAIN_USAGE_SAMPLED_BIT;sci.format=format;sci.sampleCount=1;sci.width=320;sci.height=256;sci.faceCount=1;sci.arraySize=1;sci.mipCount=1;OK(xrCreateSwapchain(session,&sci,&swaps[eye]));
        OK(xrEnumerateSwapchainImages(swaps[eye],0,&n,nullptr));images[eye].resize(n,{XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_KHR});OK(xrEnumerateSwapchainImages(swaps[eye],n,&n,reinterpret_cast<XrSwapchainImageBaseHeader*>(images[eye].data())));
    }
    GLuint fbo;glGenFramebuffers(1,&fbo);bool running=false;int submitted=0;
    for(int retry=0;retry<1000&&submitted<90;retry++) {
        XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
        while(xrPollEvent(instance,&event)==XR_SUCCESS) {
            if(event.type==XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                auto state=reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state;
                if(state==XR_SESSION_STATE_READY){XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};bi.primaryViewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;OK(xrBeginSession(session,&bi));running=true;}
                if(state==XR_SESSION_STATE_STOPPING){OK(xrEndSession(session));running=false;}
            }
            event={XR_TYPE_EVENT_DATA_BUFFER};
        }
        if(!running){usleep(10000);continue;}
        XrFrameWaitInfo wi{XR_TYPE_FRAME_WAIT_INFO};XrFrameState frame{XR_TYPE_FRAME_STATE};OK(xrWaitFrame(session,&wi,&frame));XrFrameBeginInfo bi{XR_TYPE_FRAME_BEGIN_INFO};OK(xrBeginFrame(session,&bi));
        XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO};locate.viewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;locate.displayTime=frame.predictedDisplayTime;locate.space=space;
        XrViewState state{XR_TYPE_VIEW_STATE};XrView views[2]={{XR_TYPE_VIEW},{XR_TYPE_VIEW}};OK(xrLocateViews(session,&locate,&state,2,&n,views));
        XrCompositionLayerProjectionView projection[2]={{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
        for(int eye=0;eye<2;eye++) {
            uint32_t index;XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};OK(xrAcquireSwapchainImage(swaps[eye],&ai,&index));XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};wait.timeout=XR_INFINITE_DURATION;OK(xrWaitSwapchainImage(swaps[eye],&wait));
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,images[eye][index].image,0);if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)return 8;
            glViewport(0,0,320,256);glClearColor(eye==0?1.f:0.f,eye==1?1.f:0.f,0,1);glClear(GL_COLOR_BUFFER_BIT);glFlush();glBindFramebuffer(GL_FRAMEBUFFER,0);
            XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};OK(xrReleaseSwapchainImage(swaps[eye],&release));
            projection[eye].pose=views[eye].pose;projection[eye].fov=views[eye].fov;projection[eye].subImage.swapchain=swaps[eye];projection[eye].subImage.imageRect.extent={320,256};
        }
        XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};layer.space=space;layer.viewCount=2;layer.views=projection;
        const XrCompositionLayerBaseHeader *layers[]={reinterpret_cast<XrCompositionLayerBaseHeader*>(&layer)};
        XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};end.displayTime=frame.predictedDisplayTime;end.environmentBlendMode=XR_ENVIRONMENT_BLEND_MODE_OPAQUE;end.layerCount=frame.shouldRender?1:0;end.layers=layers;OK(xrEndFrame(session,&end));if(frame.shouldRender)submitted++;
    }
    std::printf("{\"synthetic_graphical_frames\":%d}\n",submitted);
    for(auto swap:swaps){xrDestroySwapchain(swap);}
    xrDestroySpace(space);xrDestroySession(session);xrDestroyInstance(instance);
    glDeleteFramebuffers(1,&fbo);glXMakeContextCurrent(display,None,None,nullptr);glXDestroyPbuffer(display,drawable);glXDestroyContext(display,context);XFree(configs);XCloseDisplay(display);
    return submitted>=90?0:9;
}
