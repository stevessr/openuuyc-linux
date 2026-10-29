#pragma once
// Shared stereo targets with a default diagnostic painter; not remote game video. Uses real runtime view
// configuration/FOV/eye poses and acquire/wait/render/release ownership.
#include <EGL/egl.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <GLES3/gl3.h>
#include <algorithm>
#include <array>
#include <vector>
#include <cmath>
class PcvrProjectionProbe {
    XrSession session=XR_NULL_HANDLE;
    std::array<XrSwapchain,2> chains{};
    std::array<std::vector<XrSwapchainImageOpenGLESKHR>,2> images;
    GLuint framebuffer=0;
public:
    uint32_t width[2]{},height[2]{};
    XrView views[2]{{XR_TYPE_VIEW},{XR_TYPE_VIEW}};
    XrCompositionLayerProjectionView projection_views[2]{{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    uint64_t frames=0;
    bool initialize(XrInstance instance,XrSystemId system,XrSession xr_session,uint32_t max_edge=2048) {
        session=xr_session;
        uint32_t count=0;
        if(XR_FAILED(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,0,&count,nullptr)) || count!=2) return false;
        XrViewConfigurationView config[2]{{XR_TYPE_VIEW_CONFIGURATION_VIEW},{XR_TYPE_VIEW_CONFIGURATION_VIEW}};
        if(XR_FAILED(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,2,&count,config))) return false;
        uint32_t formats_count=0;
        if(XR_FAILED(xrEnumerateSwapchainFormats(session,0,&formats_count,nullptr))) return false;
        std::vector<int64_t> formats(formats_count);
        if(XR_FAILED(xrEnumerateSwapchainFormats(session,formats_count,&formats_count,formats.data()))) return false;
        int64_t format=0;
        for(int64_t candidate:{int64_t(GL_SRGB8_ALPHA8),int64_t(GL_RGBA8)})
            if(std::find(formats.begin(),formats.end(),candidate)!=formats.end()) {format=candidate;break;}
        if(!format) return false;
        for(int eye=0;eye<2;++eye) {
            // Bounded diagnostic allocation; preserve the runtime target's aspect.
            const auto& c=config[eye];
            if(!c.recommendedImageRectWidth || !c.recommendedImageRectHeight || !c.maxImageRectWidth || !c.maxImageRectHeight) return false;
            const float scale=std::min({1.f,float(max_edge)/c.recommendedImageRectWidth,float(max_edge)/c.recommendedImageRectHeight});
            width[eye]=std::min(c.maxImageRectWidth,std::max(1u,uint32_t(c.recommendedImageRectWidth*scale)));
            height[eye]=std::min(c.maxImageRectHeight,std::max(1u,uint32_t(c.recommendedImageRectHeight*scale)));
            XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
            info.usageFlags=XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;info.format=format;
            info.sampleCount=1;info.width=width[eye];info.height=height[eye];info.faceCount=1;info.arraySize=1;info.mipCount=1;
            if(XR_FAILED(xrCreateSwapchain(session,&info,&chains[eye]))) return false;
            uint32_t n=0;
            if(XR_FAILED(xrEnumerateSwapchainImages(chains[eye],0,&n,nullptr)) || !n) return false;
            images[eye].resize(n,{XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
            if(XR_FAILED(xrEnumerateSwapchainImages(chains[eye],n,&n,reinterpret_cast<XrSwapchainImageBaseHeader*>(images[eye].data())))) return false;
        }
        glGenFramebuffers(1,&framebuffer);
        layer.viewCount=2;layer.views=projection_views;
        return framebuffer!=0;
    }
    enum class Result {Ready,Skip,Error};
    using Painter=bool(*)(void*,const XrView&,uint32_t,uint32_t);
    Result render(XrSpace world,XrTime display_time,void* context=nullptr,Painter painter=nullptr) {
        if(!framebuffer) return Result::Error;
        XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO};
        locate.viewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;locate.displayTime=display_time;locate.space=world;
        XrViewState state{XR_TYPE_VIEW_STATE};uint32_t count=0;
        const auto valid=XR_VIEW_STATE_ORIENTATION_VALID_BIT|XR_VIEW_STATE_POSITION_VALID_BIT;
        if(XR_FAILED(xrLocateViews(session,&locate,&state,2,&count,views)) || count!=2) return Result::Error;
        if((state.viewStateFlags&valid)!=valid) return Result::Skip;
        for(int eye=0;eye<2;++eye) {
            uint32_t index=0;
            XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
            if(XR_FAILED(xrAcquireSwapchainImage(chains[eye],&acquire,&index))) return Result::Error;
            XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};wait.timeout=XR_INFINITE_DURATION;
            if(XR_FAILED(xrWaitSwapchainImage(chains[eye],&wait))) return Result::Error; // session will be torn down on failure
            if(index>=images[eye].size()) return Result::Error;
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,images[eye][index].image,0);
            bool ok=glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE;
            if(ok && painter) ok=painter(context,views[eye],width[eye],height[eye]);
            else if(ok) {
                glViewport(0,0,width[eye],height[eye]);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);
                // Different colours in each eye prove this is a two-view projection,
                // not a quad duplicated into both eyes. Grid is intentionally flat.
                glClearColor(eye==0?.14f:.025f,.035f,eye==1?.14f:.025f,1);glClear(GL_COLOR_BUFFER_BIT);
                glEnable(GL_SCISSOR_TEST);glClearColor(.22f,.25f,.28f,1);
                for(int n=1;n<10;++n) {
                    glScissor(int(width[eye]*n/10),0,2,height[eye]);glClear(GL_COLOR_BUFFER_BIT);
                    glScissor(0,int(height[eye]*n/10),width[eye],2);glClear(GL_COLOR_BUFFER_BIT);
                }
                glClearColor(.85f,.85f,.85f,1);
                glScissor(int(width[eye]/2)-2,int(height[eye]/2)-24,4,48);glClear(GL_COLOR_BUFFER_BIT);
                glScissor(int(width[eye]/2)-24,int(height[eye]/2)-2,48,4);glClear(GL_COLOR_BUFFER_BIT);
                glDisable(GL_SCISSOR_TEST);
                ok=glGetError()==GL_NO_ERROR;
            }
            glBindFramebuffer(GL_FRAMEBUFFER,0);glFlush();
            XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            if(XR_FAILED(xrReleaseSwapchainImage(chains[eye],&release)) || !ok) return Result::Error;
            auto& p=projection_views[eye];p.pose=views[eye].pose;p.fov=views[eye].fov;
            p.subImage.swapchain=chains[eye];p.subImage.imageRect={{0,0},{int32_t(width[eye]),int32_t(height[eye])}};
        }
        layer.space=world;++frames;return Result::Ready;
    }
    float ipd() const {
        const auto& a=views[0].pose.position;const auto& b=views[1].pose.position;
        return std::sqrt((a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)+(a.z-b.z)*(a.z-b.z));
    }
    void destroy() {
        if(framebuffer) {glDeleteFramebuffers(1,&framebuffer);framebuffer=0;}
        for(auto& chain:chains) if(chain!=XR_NULL_HANDLE) {xrDestroySwapchain(chain);chain=XR_NULL_HANDLE;}
    }
};
