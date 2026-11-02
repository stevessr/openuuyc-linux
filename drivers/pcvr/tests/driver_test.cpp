#include "../driver.cpp"
#include <cassert>
#include <limits>
#include <thread>
#include <filesystem>
struct Properties:vr::IVRProperties {
    vr::ETrackedPropertyError ReadPropertyBatch(uint64_t,vr::PropertyRead_t*,uint32_t) override {return vr::TrackedProp_Success;}
    vr::ETrackedPropertyError WritePropertyBatch(uint64_t,vr::PropertyWrite_t*,uint32_t) override {return vr::TrackedProp_Success;}
    const char* GetPropErrorNameFromEnum(vr::ETrackedPropertyError) override {return "ok";}
    uint64_t TrackedDeviceToPropertyContainer(uint32_t n) override {return n+1;}
};
struct Host:vr::IVRServerDriverHost {
    unsigned added=0,updated=0;vr::DriverPose_t poses[3]{};
    bool TrackedDeviceAdded(const char*,vr::ETrackedDeviceClass c,vr::ITrackedDeviceServerDriver* d) override {
        assert(c==vr::TrackedDeviceClass_GenericTracker);d->Activate(added++);return true;
    }
    void TrackedDevicePoseUpdated(uint32_t i,const vr::DriverPose_t& p,uint32_t size) override {assert(size==sizeof(p));assert(i<3);poses[i]=p;++updated;}
    void VsyncEvent(double) override {}
    void VendorSpecificEvent(uint32_t,vr::EVREventType,const vr::VREvent_Data_t&,double) override {}
    bool IsExiting() override {return false;}
    bool PollNextEvent(vr::VREvent_t*,uint32_t) override {return false;}
    void GetRawTrackedDevicePoses(float,vr::TrackedDevicePose_t*,uint32_t) override {}
    void RequestRestart(const char*,const char*,const char*,const char*) override {}
    uint32_t GetFrameTimings(vr::Compositor_FrameTiming*,uint32_t) override {return 0;}
    void SetDisplayEyeToHead(uint32_t,const vr::HmdMatrix34_t&,const vr::HmdMatrix34_t&) override {}
    void SetDisplayProjectionRaw(uint32_t,const vr::HmdRect2_t&,const vr::HmdRect2_t&) override {}
    void SetRecommendedRenderTargetSize(uint32_t,uint32_t,uint32_t) override {}
};
struct Context:vr::IVRDriverContext {
    Host host;Properties properties;int dummy=0;
    void* GetGenericInterface(const char* name,vr::EVRInitError* e) override {
        if(e) *e=vr::VRInitError_None;
        if(!std::strcmp(name,vr::IVRServerDriverHost_Version)) return &host;
        if(!std::strcmp(name,vr::IVRProperties_Version)) return &properties;
        // The provider only checks the remaining interfaces for presence.
        return &dummy;
    }
    uint64_t GetDriverHandle() override {return 1;}
};
int main() {
    char path[]="/tmp/openuuyc-pcvr-test-XXXXXX";assert(mkdtemp(path));setenv("XDG_RUNTIME_DIR",path,1);
    Context context;Provider provider;
    assert(provider.Init(&context)==vr::VRInitError_None);
    auto socket_path=std::string(path)+"/openuuyc-pcvr/tracking.sock";
    struct stat metadata{};assert(stat(socket_path.c_str(),&metadata)==0 && (metadata.st_mode&077)==0);
    // A second provider cannot steal the endpoint.
    Provider second;assert(second.Init(&context)!=vr::VRInitError_None);
    OuvrFrame frame{};std::memcpy(frame.magic,"OUVR",4);frame.version=1;frame.sequence=1;
    for(auto& d:frame.devices) {d.flags=15;d.position[1]=1.6f;d.orientation[3]=1;d.linear[0]=.25f;}
    assert(ouvr_valid(frame));
    int sender=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0);assert(sender>=0);
    sockaddr_un addr{};addr.sun_family=AF_UNIX;std::strcpy(addr.sun_path,socket_path.c_str());
    auto transmit=[&] {assert(sendto(sender,&frame,sizeof(frame),0,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))==sizeof(frame));provider.RunFrame();};
    transmit();assert(context.host.added==3 && context.host.updated==3);
    assert(context.host.poses[0].poseIsValid && context.host.poses[0].deviceIsConnected);
    assert(context.host.poses[0].vecPosition[1]>1.59 && context.host.poses[0].vecVelocity[0]==.25);
    frame.devices[0].orientation[3]=std::numeric_limits<float>::quiet_NaN();assert(!ouvr_valid(frame));
    transmit();assert(context.host.poses[0].poseIsValid); // invalid packet did not replace last pose
    std::this_thread::sleep_for(std::chrono::milliseconds(275));provider.RunFrame();
    assert(!context.host.poses[0].poseIsValid && !context.host.poses[0].deviceIsConnected);
    close(sender);provider.Cleanup();assert(access(socket_path.c_str(),F_OK)!=0);
    assert(provider.Init(&context)==vr::VRInitError_None);provider.Cleanup();
    // A crashed previous provider leaves a socket; the new lock owner can reclaim it.
    sender=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0);assert(bind(sender,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))==0);close(sender);
    assert(provider.Init(&context)==vr::VRInitError_None);provider.Cleanup();
    std::filesystem::remove_all(path);
    std::puts("PASS: pose IPC, packet validation, staleness, exclusive ownership, cleanup and stale recovery");
}
