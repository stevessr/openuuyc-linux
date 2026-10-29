#pragma once
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <array>
#include <chrono>
#include <cmath>
#include <mutex>

struct PcvrStereoState {
    std::mutex mutex;
    uint32_t generation=0,width=0,height=0;
    uint64_t frame=0;
    std::array<float,22> views{};
    std::chrono::steady_clock::time_point received{};
};
// One SBS Surface means the compositor can never pair frames from different eyes.
class PcvrStereoLayer {
    XrSwapchain chain=XR_NULL_HANDLE;
    uint32_t generation=0,width=0,height=0;
    std::array<XrCompositionLayerProjectionView,2> views{};
public:
    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    bool matches(uint32_t gen,uint32_t w,uint32_t h) const {return chain!=XR_NULL_HANDLE && generation==gen && width==w && height==h;}
    bool create(XrSession session,PFN_xrCreateSwapchainAndroidSurfaceKHR factory,uint32_t gen,uint32_t w,uint32_t h,jobject* surface) {
        destroy();if(!gen || w<128 || w>8192 || w%4 || h<64 || h>4096 || h%2) return false;
        XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};info.width=w;info.height=h;
        info.usageFlags=XR_SWAPCHAIN_USAGE_SAMPLED_BIT|XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        if(XR_FAILED(factory(session,&info,&chain,surface))) return false;
        generation=gen;width=w;height=h;
        for(int eye=0;eye<2;++eye) {
            views[eye]={XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
            views[eye].subImage.swapchain=chain;
            views[eye].subImage.imageRect={{int32_t(eye*width/2),0},{int32_t(width/2),int32_t(height)}};
        }
        layer.viewCount=2;layer.views=views.data();return true;
    }
    bool prepare(XrSpace world,PcvrStereoState& state) {
        std::lock_guard<std::mutex> lock(state.mutex);
        if(!matches(state.generation,state.width,state.height) || !state.frame || std::chrono::steady_clock::now()-state.received>std::chrono::milliseconds(250)) return false;
        for(int eye=0;eye<2;++eye) {
            const float* v=state.views.data()+eye*11;
            views[eye].pose.position={v[0],v[1],v[2]};
            views[eye].pose.orientation={v[3],v[4],v[5],v[6]};
            views[eye].fov={v[7],v[8],v[9],v[10]};
        }
        layer.space=world;return true;
    }
    void destroy() {if(chain!=XR_NULL_HANDLE) xrDestroySwapchain(chain);chain=XR_NULL_HANDLE;generation=width=height=0;}
};
