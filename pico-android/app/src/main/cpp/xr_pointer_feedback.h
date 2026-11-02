#pragma once
#include <initializer_list>
struct XrPointerIntent {bool tracked=false,hit=false,menu=false,pressed=false,rising=false,moved=false;};
inline bool xr_stream_content(bool hit,int pane) {return hit && pane>=0 && pane<6;}
inline int xr_control_owner(int pane) {
    if(pane>=0 && pane<6) return pane;
    if(pane>=7 && pane<=12) return pane-7;
    if(pane>=13 && pane<=36) return (pane-13)/4;
    if(pane>=37 && pane<=78) return (pane-37)%6;
    return -1;
}
inline int xr_choose_pointer(const XrPointerIntent (&h)[2],int captured,int preferred) {
    if(captured>=0 && captured<2 && h[captured].tracked) return captured;
    const int first=preferred==0?0:1,second=1-first;
    for(int i:{first,second}) if(h[i].tracked && h[i].pressed && h[i].rising) return i;
    for(int i:{first,second}) if(h[i].tracked && h[i].pressed) return i;
    for(int i:{first,second}) if(h[i].tracked && h[i].moved) return i;
    for(int i:{first,second}) if(h[i].tracked && h[i].menu) return i;
    for(int i:{first,second}) if(h[i].tracked && h[i].hit) return i;
    return h[first].tracked?first:(h[second].tracked?second:-1);
}

// Accumulate sub-threshold motion rather than allowing idle-controller jitter
// to steal the control center hover. Direction is the aim ray, not grip pose.
class XrPointerMotion {
    bool valid=false;
    float old[6]{};
public:
    void reset() {valid=false;}
    bool update(float x,float y,float z,float dx,float dy,float dz) {
        const float now[6]{x,y,z,dx,dy,dz};
        if(!valid) {for(int i=0;i<6;++i) old[i]=now[i];valid=true;return false;}
        float position=0,direction=0;
        for(int i=0;i<3;++i) {const float a=now[i]-old[i],b=now[i+3]-old[i+3];position+=a*a;direction+=b*b;}
        if(position<.004f*.004f && direction<.005f*.005f) return false;
        for(int i=0;i<6;++i) old[i]=now[i];
        return true;
    }
};
