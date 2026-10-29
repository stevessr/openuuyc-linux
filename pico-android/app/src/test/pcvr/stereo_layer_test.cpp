#include <jni.h>
#define XR_USE_PLATFORM_ANDROID
#include "pcvr_stereo_layer.h"
#include <cassert>
static unsigned destroyed=0;
extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrDestroySwapchain(XrSwapchain) {++destroyed;return XR_SUCCESS;}
static XrResult XRAPI_PTR create(XrSession,const XrSwapchainCreateInfo* info,XrSwapchain* chain,jobject* surface) {
    assert(info->width==2048 && info->height==1024);
    *chain=reinterpret_cast<XrSwapchain>(uintptr_t(1));*surface=nullptr;return XR_SUCCESS;
}
int main() {
    PcvrStereoLayer layer;jobject surface=nullptr;
    assert(!layer.create(XR_NULL_HANDLE,create,1,2049,1024,&surface));
    assert(layer.create(XR_NULL_HANDLE,create,1,2048,1024,&surface));
    PcvrStereoState state;state.generation=1;state.width=2048;state.height=1024;state.frame=7;
    state.received=std::chrono::steady_clock::now();
    for(int eye=0;eye<2;++eye) {float* v=state.views.data()+eye*11;v[0]=eye==0?-.032f:.032f;v[6]=1;v[7]=-.8f;v[8]=.8f;v[9]=.8f;v[10]=-.8f;}
    assert(layer.prepare(XR_NULL_HANDLE,state));assert(layer.layer.viewCount==2);
    const auto* eyes=layer.layer.views;
    assert(eyes[0].subImage.swapchain==eyes[1].subImage.swapchain);
    assert(eyes[0].subImage.imageRect.offset.x==0 && eyes[1].subImage.imageRect.offset.x==1024);
    assert(eyes[0].subImage.imageRect.extent.width==1024 && eyes[1].subImage.imageRect.extent.width==1024);
    assert(eyes[0].pose.position.x<0 && eyes[1].pose.position.x>0);
    state.received-=std::chrono::milliseconds(251);assert(!layer.prepare(XR_NULL_HANDLE,state));
    state.received=std::chrono::steady_clock::now();state.generation=2;assert(!layer.prepare(XR_NULL_HANDLE,state));
    layer.destroy();assert(destroyed==1);
}
