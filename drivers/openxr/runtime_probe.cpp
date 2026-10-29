// OpenUUYC-owned diagnostic helper. The selected runtime lives in this process.
#include <openxr/openxr.h>
#include <cstdio>
#include <cstring>
int main(int argc,char** argv) {
    const bool device_only=argc==2 && std::strcmp(argv[1],"--device-only")==0;
    const char* headless="XR_MND_headless";
    XrInstanceCreateInfo create{XR_TYPE_INSTANCE_CREATE_INFO};
    std::strncpy(create.applicationInfo.applicationName,"OpenUUYC runtime probe",XR_MAX_APPLICATION_NAME_SIZE-1);
    create.applicationInfo.apiVersion=XR_MAKE_VERSION(1,0,0);
    if(device_only) {create.enabledExtensionCount=1;create.enabledExtensionNames=&headless;}
    XrInstance instance=XR_NULL_HANDLE;
    const auto initialized=xrCreateInstance(&create,&instance);
    XrResult system_result=XR_ERROR_INITIALIZATION_FAILED;
    if(XR_SUCCEEDED(initialized)) {
        XrSystemGetInfo info{XR_TYPE_SYSTEM_GET_INFO};info.formFactor=XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        XrSystemId system=XR_NULL_SYSTEM_ID;
        system_result=xrGetSystem(instance,&info,&system);
        xrDestroyInstance(instance);
    }
    std::printf("{\"version\":1,\"instance\":%s,\"system\":%s,\"instance_result\":%d,\"system_result\":%d}\n",
        XR_SUCCEEDED(initialized)?"true":"false",XR_SUCCEEDED(system_result)?"true":"false",int(initialized),int(system_result));
    return XR_SUCCEEDED(initialized)?0:2;
}
