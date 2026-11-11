// Real SteamVR GenericTracker poses -> normalized JSONL for tracker-bridge.py.
#include "tracker_source.h"
#include <dlfcn.h>
#include <time.h>
#include <chrono>
#include <thread>
#include <map>
#include <string>
#include <cstdio>
#include <cstdlib>
#include <csignal>
#include <stdexcept>

static volatile std::sig_atomic_t stopped=0;
static void stop(int) { stopped=1; }
static void require(bool ok,const char* message) {if(!ok)throw std::runtime_error(message);}
struct Runtime {
    void* library=nullptr;
    void (*shutdown)()=nullptr;
    bool initialized=false;
    vr::IVRSystem* system=nullptr;
    ~Runtime() {if(initialized && shutdown)shutdown();if(library)dlclose(library);}
    void open(const char* path) {
        require(path && path[0]=='/',"pass an absolute installed libopenvr_api.so path");
        unsetenv("VR_OVERRIDE");unsetenv("XR_RUNTIME_JSON");
        library=dlopen(path,RTLD_NOW|RTLD_LOCAL);
        require(library,"cannot load OpenVR library");
        auto init=reinterpret_cast<uint32_t(*)(vr::EVRInitError*,vr::EVRApplicationType,const char*)>(dlsym(library,"VR_InitInternal2"));
        auto get=reinterpret_cast<void*(*)(const char*,vr::EVRInitError*)>(dlsym(library,"VR_GetGenericInterface"));
        shutdown=reinterpret_cast<void(*)()>(dlsym(library,"VR_ShutdownInternal"));
        require(init && get && shutdown,"missing OpenVR exports");
        vr::EVRInitError error=vr::VRInitError_None;
        init(&error,vr::VRApplication_Background,nullptr);
        require(error==vr::VRInitError_None,"SteamVR must already be running and available");
        initialized=true;
        system=static_cast<vr::IVRSystem*>(get(vr::IVRSystem_Version,&error));
        require(system && error==vr::VRInitError_None,"unsupported OpenVR system interface");
    }
};

int main(int argc,char** argv) {
    if(argc<2 || (argc-2)%2!=0) {std::fprintf(stderr,"Usage: %s /absolute/path/libopenvr_api.so [--tracker ID=SERIAL ...] | openuuyc-tracker-bridge --space openxr --max-age-ms 100\n",argv[0]);return 2;}
    std::signal(SIGINT,stop);std::signal(SIGTERM,stop);
    try {
        TrackerSlots slots;
        for(int arg=2;arg<argc;arg+=2) {
            require(std::string(argv[arg])=="--tracker","unknown tracker source option");
            slots.configure(argv[arg+1]);
        }
        Runtime runtime;runtime.open(argv[1]);
        // IDs stay bound to serials for this process, even through reconnects.
        // Never reuse a disappeared device's ID for a different tracker.
        while(!stopped) {
            const auto next=std::chrono::steady_clock::now()+std::chrono::milliseconds(11);
            vr::TrackedDevicePose_t poses[vr::k_unMaxTrackedDeviceCount]{};
            runtime.system->GetDeviceToAbsoluteTrackingPose(vr::TrackingUniverseStanding,0,poses,vr::k_unMaxTrackedDeviceCount);
            timespec now{};require(clock_gettime(CLOCK_MONOTONIC,&now)==0,"monotonic clock unavailable");
            std::printf("{\"monotonic_ns\":%llu,\"trackers\":[",static_cast<unsigned long long>(now.tv_sec)*1000000000ULL+now.tv_nsec);
            bool comma=false;
            for(uint32_t index=0;index<vr::k_unMaxTrackedDeviceCount;index++) {
                if(runtime.system->GetTrackedDeviceClass(index)!=vr::TrackedDeviceClass_GenericTracker)continue;
                char serial[1024]{};vr::ETrackedPropertyError error=vr::TrackedProp_Success;
                const auto length=runtime.system->GetStringTrackedDeviceProperty(index,vr::Prop_SerialNumber_String,serial,sizeof(serial),&error);
                if(error!=vr::TrackedProp_Success || length<2 || length>sizeof(serial) || serial[length-1]!='\0')continue;
                const auto id=slots.assign(serial);
                if(!id)continue;
                float pose[7]{};
                const bool valid=tracker_pose(poses[index],pose);
                if(comma)std::putchar(',');
                comma=true;
                if(!valid) {std::printf("{\"id\":%u,\"tracked\":false}",*id);continue;}
                std::printf("{\"id\":%u,\"position\":[%.9g,%.9g,%.9g],\"rotation\":[%.9g,%.9g,%.9g,%.9g]}",
                    *id,pose[0],pose[1],pose[2],pose[3],pose[4],pose[5],pose[6]);
            }
            std::puts("]}");
            if(std::fflush(stdout)!=0 || std::ferror(stdout))break;
            std::this_thread::sleep_until(next);
        }
    } catch(const std::exception& error) {
        std::fprintf(stderr,"tracker source: %s\n",error.what());return 1;
    }
    return 0;
}
