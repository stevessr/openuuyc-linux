// OpenUUYC integrated OpenVR tracking bridge, NOT a stereo display backend yet.
#include "vendor/openvr_driver.h"
#include "../../pico-android/app/src/main/cpp/pcvr_protocol.h"
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/file.h>
#include <cerrno>
using Clock=std::chrono::steady_clock;
class Tracker final:public vr::ITrackedDeviceServerDriver {
    vr::TrackedDeviceIndex_t index=vr::k_unTrackedDeviceIndexInvalid;
    vr::DriverPose_t pose{};
public:
    const char* serial;
    explicit Tracker(const char* s):serial(s) {pose.qWorldFromDriverRotation.w=pose.qDriverFromHeadRotation.w=pose.qRotation.w=1;}
    vr::EVRInitError Activate(uint32_t i) override {
        index=i;auto p=vr::VRProperties()->TrackedDeviceToPropertyContainer(i);
        vr::VRProperties()->SetStringProperty(p,vr::Prop_ModelNumber_String,serial);
        vr::VRProperties()->SetStringProperty(p,vr::Prop_TrackingSystemName_String,"openuuyc");
        vr::VRProperties()->SetStringProperty(p,vr::Prop_SerialNumber_String,serial);
        return vr::VRInitError_None;
    }
    void Deactivate() override {index=vr::k_unTrackedDeviceIndexInvalid;}
    void EnterStandby() override {}
    void* GetComponent(const char*) override {return nullptr;}
    void DebugRequest(const char*,char* b,uint32_t n) override {if(n) b[0]=0;}
    vr::DriverPose_t GetPose() override {return pose;}
    void update(const OuvrDevice& d,bool fresh) {
        pose.deviceIsConnected=fresh;pose.poseIsValid=fresh && (d.flags&3)==3;
        pose.result=pose.poseIsValid?vr::TrackingResult_Running_OK:vr::TrackingResult_Running_OutOfRange;
        for(int i=0;i<3;++i) {
            pose.vecPosition[i]=d.position[i];
            pose.vecVelocity[i]=(d.flags&4)?d.linear[i]:0;
            pose.vecAngularVelocity[i]=(d.flags&8)?d.angular[i]:0;
        }
        pose.qRotation={d.orientation[3],d.orientation[0],d.orientation[1],d.orientation[2]};
        if(!(d.flags&1)) pose.qRotation={1,0,0,0};
        // Do not compare remote XrTime to the Linux clock or invent prediction offsets.
        pose.poseTimeOffset=0;
        if(index!=vr::k_unTrackedDeviceIndexInvalid)
            vr::VRServerDriverHost()->TrackedDevicePoseUpdated(index,pose,sizeof(pose));
    }
};
class Provider final:public vr::IServerTrackedDeviceProvider {
    int fd=-1,lock_fd=-1;std::string socket_path;
    std::array<Tracker,3> trackers{{Tracker("openuuyc-head-preview"),Tracker("openuuyc-left-preview"),Tracker("openuuyc-right-preview")}};
    OuvrFrame latest{};Clock::time_point received{};bool added=false;
public:
    vr::EVRInitError Init(vr::IVRDriverContext* context) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(context);
        const char* runtime=std::getenv("XDG_RUNTIME_DIR");
        if(!runtime) return vr::VRInitError_Driver_Failed;
        std::string directory=std::string(runtime)+"/openuuyc-pcvr";
        if(mkdir(directory.c_str(),0700) && errno!=EEXIST) return vr::VRInitError_Driver_Failed;
        struct stat st{};
        if(lstat(directory.c_str(),&st) || !S_ISDIR(st.st_mode) || st.st_uid!=getuid() || (st.st_mode&077)!=0)
            return vr::VRInitError_Driver_Failed;
        lock_fd=open((directory+"/provider.lock").c_str(),O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
        if(lock_fd<0) return vr::VRInitError_Driver_Failed;
        if(fstat(lock_fd,&st) || !S_ISREG(st.st_mode) || st.st_uid!=getuid() || (st.st_mode&077)!=0 || flock(lock_fd,LOCK_EX|LOCK_NB)) {
            close(lock_fd);lock_fd=-1;return vr::VRInitError_Driver_Failed;
        }
        socket_path=directory+"/tracking.sock";
        // Holding the provider lock makes reclaiming our own stale socket safe.
        if(lstat(socket_path.c_str(),&st)==0) {
            if(!S_ISSOCK(st.st_mode) || st.st_uid!=getuid()) {Cleanup();return vr::VRInitError_Driver_Failed;}
            if(unlink(socket_path.c_str())) {Cleanup();return vr::VRInitError_Driver_Failed;}
        }
        sockaddr_un addr{};addr.sun_family=AF_UNIX;
        if(socket_path.size()>=sizeof(addr.sun_path)) {Cleanup();return vr::VRInitError_Driver_Failed;}
        std::strcpy(addr.sun_path,socket_path.c_str());
        fd=socket(AF_UNIX,SOCK_DGRAM|SOCK_NONBLOCK|SOCK_CLOEXEC,0);
        // The provider lock prevents stealing another live instance's endpoint.
        if(fd<0 || bind(fd,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))) {
            if(fd>=0) {close(fd);} fd=-1;Cleanup();return vr::VRInitError_Driver_Failed;
        }
        if(chmod(socket_path.c_str(),0600)) {Cleanup();return vr::VRInitError_Driver_Failed;}
        return vr::VRInitError_None;
    }
    void Cleanup() override {
        if(fd>=0) {close(fd);fd=-1;unlink(socket_path.c_str());}
        if(lock_fd>=0) {close(lock_fd);lock_fd=-1;}
        for(auto& t:trackers) t.Deactivate();
        added=false;latest={};received={};
        VR_CLEANUP_SERVER_DRIVER_CONTEXT();
    }
    const char* const* GetInterfaceVersions() override {return vr::k_InterfaceVersions;}
    void RunFrame() override {
        if(fd<0) return;
        OuvrFrame frame{};
        for(int i=0;i<128;++i) {
            const auto n=recv(fd,&frame,sizeof(frame),MSG_TRUNC);
            if(n<0) break;
            if(n==sizeof(frame) && ouvr_valid(frame)) {latest=frame;received=Clock::now();}
        }
        const bool fresh=received!=Clock::time_point{} && Clock::now()-received<std::chrono::milliseconds(250);
        if(fresh && !added) {
            // Until GPU/stereo submission exists, these are diagnostic trackers,
            // never a fake HMD that steals the user's active SteamVR headset.
            for(auto& t:trackers) vr::VRServerDriverHost()->TrackedDeviceAdded(t.serial,vr::TrackedDeviceClass_GenericTracker,&t);
            added=true;
        }
        if(added) for(int i=0;i<3;++i) trackers[i].update(latest.devices[i],fresh);
    }
    bool ShouldBlockStandbyMode() override {return false;}
    void EnterStandby() override {}
    void LeaveStandby() override {}
};
extern "C" __attribute__((visibility("default"))) void* HmdDriverFactory(const char* name,int* error) {
    static Provider provider;
    if(!std::strcmp(name,vr::IServerTrackedDeviceProvider_Version)) return &provider;
    if(error) *error=vr::VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
