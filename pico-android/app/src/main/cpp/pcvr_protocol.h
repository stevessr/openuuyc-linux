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
    if(std::memcmp(f.magic,"OUVR",4) || (f.version!=1 && f.version!=2) || !f.sequence) return false;
    for(const auto& d:f.devices) {
        if(d.flags>(f.version==2?63u:15u) || d.buttons>3 || d.reserved) return false;
        float norm=0;
        for(float v:d.position) if(!std::isfinite(v) || std::fabs(v)>1000) return false;
        for(float v:d.orientation) {if(!std::isfinite(v)) return false;norm+=v*v;}
        for(float v:d.linear) if(!std::isfinite(v) || std::fabs(v)>1000) return false;
        for(float v:d.angular) if(!std::isfinite(v) || std::fabs(v)>1000) return false;
        if((d.flags&1) && (norm<.98f || norm>1.02f)) return false;
    }
    return true;
}
// V2 extends the exact v1 prefix; aim poses are measured independently of grip.
struct OuvrControls { uint32_t flags,buttons; float trigger,squeeze,x,y; };
struct OuvrFrameV2 { OuvrFrame base; OuvrDevice aim[2]; OuvrControls controls[2]; };
static_assert(sizeof(OuvrControls)==24 && sizeof(OuvrFrameV2)==400);
inline bool ouvr_valid(const OuvrFrameV2& f) {
    if(f.base.version!=2)return false;
    auto base=f.base;
    if(!ouvr_valid(base))return false;
    for(const auto& aim:f.aim) {base.devices[0]=aim;if(!ouvr_valid(base))return false;}
    for(const auto& c:f.controls) {
        if(c.flags>127 || c.buttons>15)return false;
        if(!std::isfinite(c.trigger)||c.trigger<0||c.trigger>1||!std::isfinite(c.squeeze)||c.squeeze<0||c.squeeze>1)return false;
        if(!std::isfinite(c.x)||std::fabs(c.x)>1||!std::isfinite(c.y)||std::fabs(c.y)>1)return false;
    }
    return true;
}
// Local UI owns buttons/axes while visible. Require neutral before returning
// control to the game, but retain real head/grip/aim tracking throughout.
inline void ouvr_route_controls(OuvrFrameV2& frame,bool local_ui,bool blocked[2]) {
    for(int hand=0;hand<2;hand++) {
        auto& c=frame.controls[hand];auto& grip=frame.base.devices[hand+1];
        const bool neutral=!c.buttons && !grip.buttons && c.trigger<.1f && c.squeeze<.1f && std::fabs(c.x)<.25f && std::fabs(c.y)<.25f;
        if(local_ui)blocked[hand]=true;
        else if(blocked[hand] && neutral)blocked[hand]=false;
        if(blocked[hand]) {c={};grip.buttons=0;}
    }
}
