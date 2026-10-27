#pragma once
#include <initializer_list>
struct XrPointerIntent {bool tracked=false,hit=false,menu=false,pressed=false,rising=false;};
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
    for(int i:{first,second}) if(h[i].tracked && h[i].menu) return i;
    for(int i:{first,second}) if(h[i].tracked && h[i].hit) return i;
    return h[first].tracked?first:(h[second].tracked?second:-1);
}
