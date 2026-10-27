#pragma once
#include "xr_panel_geometry.h"
struct PanelViewVector {float x,y,z;};
inline void panel_depth_step(PanelGeometry &p,PanelViewVector origin,PanelViewVector direction,float axis) {
    if(!std::isfinite(axis) || std::fabs(axis)<=.25f) return;
    const float depth=(p.x-origin.x)*direction.x+(p.y-origin.y)*direction.y+(p.z-origin.z)*direction.z;
    const float delta=std::clamp(depth+std::clamp(axis,-1.f,1.f)*.035f,.45f,6.f)-depth;
    p.x+=direction.x*delta;p.y+=direction.y*delta;p.z+=direction.z*delta;
}
