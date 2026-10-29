#pragma once
#include <cstdint>
#include <cmath>
#include <cstring>
// Little-endian v1. Metres, radians, OpenXR LOCAL right-handed coordinates.
// xr_time is the *remote* predicted display time, never a Linux monotonic clock.
struct OuvrDevice {
    uint32_t flags, buttons; // orientation, position, linear velocity, angular velocity
    float position[3], orientation[4], linear[3], angular[3]; // quaternion xyzw
    uint32_t reserved;
};
struct OuvrFrame {
    char magic[4]; uint32_t version;
    uint64_t sequence, xr_time, sample_time;
    OuvrDevice devices[3]; // head, left grip, right grip
};
static_assert(sizeof(OuvrDevice)==64 && sizeof(OuvrFrame)==224);
inline bool ouvr_valid(const OuvrFrame& f) {
    if(std::memcmp(f.magic,"OUVR",4) || f.version!=1 || !f.sequence) return false;
    for(const auto& d:f.devices) {
        if(d.flags>15 || d.buttons>3 || d.reserved) return false;
        float norm=0;
        for(float v:d.position) if(!std::isfinite(v) || std::fabs(v)>1000) return false;
        for(float v:d.orientation) {if(!std::isfinite(v)) return false;norm+=v*v;}
        for(float v:d.linear) if(!std::isfinite(v) || std::fabs(v)>1000) return false;
        for(float v:d.angular) if(!std::isfinite(v) || std::fabs(v)>1000) return false;
        if((d.flags&1) && (norm<.98f || norm>1.02f)) return false;
    }
    return true;
}
