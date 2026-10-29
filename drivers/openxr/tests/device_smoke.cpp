// Synthetic-fixture-only OpenXR test, not headset acceptance evidence.
#include <time.h>
#define XR_USE_TIMESPEC
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <cstdio>
#include <cstring>
#include <cmath>
#define OK(expr) do {XrResult r=(expr);if(XR_FAILED(r)) {std::fprintf(stderr,"%s: %d\n",#expr,int(r));return 2;}} while(0)
int main() {
    const char *extensions[]={"XR_MND_headless","XR_KHR_convert_timespec_time"};
    XrInstanceCreateInfo create{XR_TYPE_INSTANCE_CREATE_INFO};create.applicationInfo.apiVersion=XR_MAKE_VERSION(1,0,0);std::strcpy(create.applicationInfo.applicationName,"OpenUUYC fixture smoke");create.enabledExtensionCount=2;create.enabledExtensionNames=extensions;
    XrInstance instance;OK(xrCreateInstance(&create,&instance));
    XrSystemGetInfo get{XR_TYPE_SYSTEM_GET_INFO};get.formFactor=XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;XrSystemId system;OK(xrGetSystem(instance,&get,&system));
    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};ci.systemId=system;XrSession session;OK(xrCreateSession(instance,&ci,&session));
    XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};while(xrPollEvent(instance,&event)==XR_SUCCESS) {event={XR_TYPE_EVENT_DATA_BUFFER};}
    XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};begin.primaryViewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;OK(xrBeginSession(session,&begin));
    XrReferenceSpaceCreateInfo reference{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};reference.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;reference.poseInReferenceSpace.orientation.w=1;XrSpace space;OK(xrCreateReferenceSpace(session,&reference,&space));
    PFN_xrConvertTimespecTimeToTimeKHR convert=nullptr;OK(xrGetInstanceProcAddr(instance,"xrConvertTimespecTimeToTimeKHR",reinterpret_cast<PFN_xrVoidFunction*>(&convert)));
    struct timespec now{};clock_gettime(CLOCK_MONOTONIC,&now);XrTime time;OK(convert(instance,&now,&time));
    reference.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_VIEW;XrSpace view;OK(xrCreateReferenceSpace(session,&reference,&view));
    XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};OK(xrLocateSpace(view,space,time,&location));
    if(!(location.locationFlags&XR_SPACE_LOCATION_POSITION_VALID_BIT) || std::fabs(location.pose.position.x-1.f)>.001f || std::fabs(location.pose.position.y-2.f)>.001f || std::fabs(location.pose.position.z-3.f)>.001f) return 4;
    std::printf("{\"system\":true,\"session\":true,\"head_x\":%.6f,\"head_y\":%.6f,\"head_z\":%.6f}\n",location.pose.position.x,location.pose.position.y,location.pose.position.z);
    xrDestroySpace(view);
    xrDestroySpace(space);xrDestroySession(session);xrDestroyInstance(instance);return 0;
}
