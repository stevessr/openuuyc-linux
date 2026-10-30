// Official OpenVR background mirror reader. No scene takeover, injection or game launch.
#define GL_GLEXT_PROTOTYPES
#include <SDL.h>
#include <SDL_opengl.h>
#include "vendor/openvr.h"
#include "mirror_pose.h"
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <csignal>
#include <stdexcept>
#include <vector>
#include <string>
#include <algorithm>
extern "C" {
#include "../openxr/stereo_capture.h"
}
static volatile std::sig_atomic_t stopped=0;
static void stop(int) {stopped=1;}
static void require(bool value,const char *message) {if(!value) throw std::runtime_error(message);}
struct Client {
    void *library=nullptr;
    void (*shutdown)()=nullptr;
    bool initialized=false;
    vr::IVRSystem *system=nullptr;vr::IVRCompositor *compositor=nullptr;
    explicit Client(const char *path) {
        require(path && path[0]=='/',"OpenVR library path must be absolute");
        // This process explicitly targets SteamVR, never an inherited xrizer
        // override. Changes are local to this short-lived helper process.
        unsetenv("VR_OVERRIDE");unsetenv("XR_RUNTIME_JSON");
        library=dlopen(path,RTLD_NOW|RTLD_LOCAL);require(library,"cannot load installed OpenVR library");
    }
    vr::EVRInitError connect() {
        auto init=reinterpret_cast<uint32_t(*)(vr::EVRInitError*,vr::EVRApplicationType,const char*)>(dlsym(library,"VR_InitInternal2"));
        auto get=reinterpret_cast<void*(*)(const char*,vr::EVRInitError*)>(dlsym(library,"VR_GetGenericInterface"));
        shutdown=reinterpret_cast<void(*)()>(dlsym(library,"VR_ShutdownInternal"));
        require(init && get && shutdown,"installed OpenVR exports are incomplete");
        vr::EVRInitError error=vr::VRInitError_None;
        // The SDK explicitly defines Background as not starting SteamVR.
        init(&error,vr::VRApplication_Background,nullptr);if(error!=vr::VRInitError_None) return error;
        initialized=true;
        system=static_cast<vr::IVRSystem*>(get(vr::IVRSystem_Version,&error));
        if(error!=vr::VRInitError_None || !system) return error==vr::VRInitError_None?vr::VRInitError_Init_InterfaceNotFound:error;
        compositor=static_cast<vr::IVRCompositor*>(get(vr::IVRCompositor_Version,&error));
        return compositor?error:vr::VRInitError_Init_InterfaceNotFound;
    }
    ~Client() {if(initialized && shutdown) shutdown();if(library) dlclose(library);}
};
struct GL {
    SDL_Window *window=nullptr;SDL_GLContext context=nullptr;
    GL() {
        require(SDL_Init(SDL_INIT_VIDEO)==0,"SDL video unavailable");
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,3);SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,3);
        window=SDL_CreateWindow("OpenUUYC SteamVR mirror",0,0,32,32,SDL_WINDOW_OPENGL|SDL_WINDOW_HIDDEN);
        require(window,"hidden OpenGL window unavailable");context=SDL_GL_CreateContext(window);
        require(context,"OpenGL context unavailable");
    }
    ~GL(){if(context) SDL_GL_DeleteContext(context);if(window) SDL_DestroyWindow(window);SDL_Quit();}
};
struct Mirror {
    vr::IVRCompositor *compositor;vr::glUInt_t texture=0;vr::glSharedTextureHandle_t shared=nullptr;
    Mirror(vr::IVRCompositor *c,vr::EVREye eye):compositor(c) {
        auto error=c->GetMirrorTextureGL(eye,&texture,&shared);
        if(error!=vr::VRCompositorError_None || !texture || !shared) {
            if(texture && shared) {c->ReleaseSharedGLTexture(texture,shared);texture=0;shared=nullptr;}
            throw std::runtime_error("SteamVR GL mirror texture unavailable");
        }
    }
    ~Mirror(){if(texture && shared) compositor->ReleaseSharedGLTexture(texture,shared);}
};
struct Access {
    Mirror &mirror;
    explicit Access(Mirror &m):mirror(m){mirror.compositor->LockGLSharedTextureForAccess(mirror.shared);}
    ~Access(){mirror.compositor->UnlockGLSharedTextureForAccess(mirror.shared);}
};
struct Readback {
    GLuint source=0,target=0,texture=0;
    Readback(){glGenFramebuffers(1,&source);glGenFramebuffers(1,&target);glGenTextures(1,&texture);}
    ~Readback(){glDeleteTextures(1,&texture);glDeleteFramebuffers(1,&source);glDeleteFramebuffers(1,&target);}
    bool read(Mirror &mirror,uint32_t &width,uint32_t &height,std::vector<unsigned char> &rgba) {
        Access access(mirror);
        glBindTexture(GL_TEXTURE_2D,mirror.texture);
        GLint w=0,h=0;glGetTexLevelParameteriv(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH,&w);glGetTexLevelParameteriv(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT,&h);
        if(w<64 || h<64 || w>8192 || h>8192) return false;
        double scale=std::min(512.0/h,1024.0/w);
        width=std::max(64u,uint32_t(w*scale)&~1u);height=std::max(64u,uint32_t(h*scale)&~1u);
        glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,mirror.texture,0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        if(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE || glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) return false;
        glBlitFramebuffer(0,h,w,0,0,0,width,height,GL_COLOR_BUFFER_BIT,GL_LINEAR);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,target);glReadBuffer(GL_COLOR_ATTACHMENT0);
        rgba.resize(size_t(width)*height*4);glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,rgba.data());
        return glGetError()==GL_NO_ERROR;
    }
};
int main(int argc,char **argv) {
    try {
        const char *library=nullptr;bool probe=false;
        for(int i=1;i<argc;i++) {
            if(!std::strcmp(argv[i],"--library") && i+1<argc) library=argv[++i];
            else if(!std::strcmp(argv[i],"--probe")) probe=true;
            else throw std::runtime_error("usage: --library ABSOLUTE_OPENVR_API [--probe]");
        }
        Client client(library);auto error=client.connect();
        if(error!=vr::VRInitError_None) {
            std::printf("{\"available\":false,\"openvr_error\":%d,\"reason\":\"existing SteamVR runtime unavailable\"}\n",int(error));
            return probe?0:3;
        }
        uint32_t scene=client.compositor->GetCurrentSceneFocusProcess();
        std::printf("{\"available\":%s,\"scene_pid\":%u,\"renderer_pid\":%u,\"mode\":\"background-mirror-observer\"}\n",
            scene?"true":"false",scene,client.compositor->GetLastFrameRenderer());std::fflush(stdout);
        if(probe) return 0;
        require(scene,"no running VR scene to attach");
        require(std::getenv("OPENUUYC_STEREO_CAPTURE"),"host stereo export must be explicitly supplied");
        GL gl;Mirror left(client.compositor,vr::Eye_Left),right(client.compositor,vr::Eye_Right);Readback readback;
        std::vector<unsigned char> eyes[2];uint32_t width[2],height[2];uint32_t previous=0;
        std::signal(SIGINT,stop);std::signal(SIGTERM,stop);
        // Origin 1 must be aligned by the host before feeding a PICO LOCAL projection.
        oust_set_origin(1);
        while(!stopped) {
            require(client.compositor->GetCurrentSceneFocusProcess()==scene,"scene changed; explicit reattachment required");
            vr::Compositor_FrameTiming before{};before.m_nSize=sizeof(before);
            if(!client.compositor->GetFrameTiming(&before) || before.m_nFrameIndex==previous || (!before.m_HmdPose.bPoseIsValid || !before.m_HmdPose.bDeviceIsConnected) ||
               client.compositor->GetLastFrameRenderer()!=scene) {SDL_Delay(10);continue;}
            if(!readback.read(left,width[0],height[0],eyes[0]) || !readback.read(right,width[1],height[1],eyes[1])) throw std::runtime_error("SteamVR mirror GPU readback failed");
            vr::Compositor_FrameTiming after{};after.m_nSize=sizeof(after);
            if(!client.compositor->GetFrameTiming(&after) || after.m_nFrameIndex!=before.m_nFrameIndex ||
               client.compositor->GetCurrentSceneFocusProcess()!=scene || client.compositor->GetLastFrameRenderer()!=scene ||
               width[0]!=width[1] || height[0]!=height[1]) continue;
            float views[22];bool valid=true;
            for(int eye=0;eye<2;eye++) {
                float l,r,down,up;client.system->GetProjectionRaw(vr::EVREye(eye),&l,&r,&down,&up);
                auto pose=mirror_product(before.m_HmdPose.mDeviceToAbsoluteTracking,client.system->GetEyeToHeadTransform(vr::EVREye(eye)));
                valid &= mirror_view(pose,l,r,down,up,views+eye*11);
            }
            double ns=before.m_flSystemTimeInSeconds*1e9;
            if(!valid || !std::isfinite(ns) || ns<=0 || ns>9e18) continue;
            oust_set_views(views);
            for(uint32_t eye=0;eye<2;eye++) oust_capture_eye(eye,uint64_t(before.m_nFrameIndex)+1,uint64_t(ns),width[eye],height[eye],width[eye]*4,eyes[eye].data());
            previous=before.m_nFrameIndex;SDL_Delay(30);
        }
        return 0;
    } catch(const std::exception &error) {std::fprintf(stderr,"SteamVR mirror: %s\n",error.what());return 2;}
}
