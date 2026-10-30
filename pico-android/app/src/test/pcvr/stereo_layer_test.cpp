#include <jni.h>
#define XR_USE_PLATFORM_ANDROID
#include "pcvr_stereo_layer.h"
#include <cassert>
static unsigned destroyed=0;
static int failure=0;
static auto session=reinterpret_cast<XrSession>(uintptr_t(10));
static auto world=reinterpret_cast<XrSpace>(uintptr_t(20));
extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrDestroySwapchain(XrSwapchain) {++destroyed;return XR_SUCCESS;}
static XrResult XRAPI_PTR create(XrSession,const XrSwapchainCreateInfo* info,XrSwapchain* chain,jobject* surface) {
    assert(info->width==2048 && info->height==1024);
    assert(info->format==0 && info->sampleCount==0 && info->faceCount==0 && info->arraySize==0 && info->mipCount==0);
    assert(info->type==XR_TYPE_SWAPCHAIN_CREATE_INFO && !info->next);
    assert(info->usageFlags&XR_SWAPCHAIN_USAGE_SAMPLED_BIT);
    if(failure==2)return XR_ERROR_RUNTIME_FAILURE;
    *chain=reinterpret_cast<XrSwapchain>(uintptr_t(1));
    *surface=failure==1?nullptr:reinterpret_cast<jobject>(uintptr_t(30));return XR_SUCCESS;
}
int main() {
    PcvrStereoLayer layer;jobject surface=nullptr;
    assert(!layer.create(session,create,1,2049,1024,&surface));
    assert(!layer.create(XR_NULL_HANDLE,create,1,2048,1024,&surface));
    assert(!layer.create(session,nullptr,1,2048,1024,&surface));
    assert(layer.last_error()==XR_ERROR_FUNCTION_UNSUPPORTED);
    assert(!layer.create(session,create,1,2048,1024,nullptr));
    assert(layer.create(session,create,1,2048,1024,&surface));
    assert(surface!=nullptr);
    PcvrStereoState state;state.generation=1;state.width=2048;state.height=1024;state.frame=7;
    state.received=std::chrono::steady_clock::now();
    for(int eye=0;eye<2;++eye) {float* v=state.views.data()+eye*11;v[0]=eye==0?-.032f:.032f;v[6]=1;v[7]=-.8f;v[8]=.8f;v[9]=.8f;v[10]=-.8f;}
    assert(layer.prepare(world,state));assert(layer.layer.viewCount==2);
    const auto* eyes=layer.layer.views;
    assert(eyes[0].subImage.swapchain==eyes[1].subImage.swapchain);
    assert(eyes[0].subImage.imageRect.offset.x==0 && eyes[1].subImage.imageRect.offset.x==1024);
    assert(eyes[0].subImage.imageRect.extent.width==1024 && eyes[1].subImage.imageRect.extent.width==1024);
    assert(eyes[0].pose.position.x<0 && eyes[1].pose.position.x>0);
    state.received-=std::chrono::milliseconds(251);assert(!layer.prepare(world,state));
    state.received=std::chrono::steady_clock::now();state.generation=2;assert(!layer.prepare(world,state));
    state.generation=1;
    assert(!layer.prepare(XR_NULL_HANDLE,state));assert(layer.prepare(world,state));
    layer.destroy();assert(destroyed==1);
    failure=1;assert(!layer.create(session,create,1,2048,1024,&surface));assert(!surface && destroyed==2);assert(layer.last_error()==XR_ERROR_RUNTIME_FAILURE);
    failure=2;assert(!layer.create(session,create,1,2048,1024,&surface));assert(!surface && destroyed==2);
    failure=0;assert(layer.create(session,create,2,2048,1024,&surface));layer.destroy();assert(destroyed==3);
}
