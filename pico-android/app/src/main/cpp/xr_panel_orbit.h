#pragma once
#include "xr_panel_geometry.h"
struct OrbitVector {float x,y,z;};
inline float orbit_radius(const PanelGeometry &p,OrbitVector head,float u,float v,float aspect) {
    const float dx=(u-.5f)*p.width,dy=(.5f-v)*p.width*aspect;
    const auto anchor=panel_point(p,dx,dy);
    const float x=anchor.x-head.x,y=anchor.y-head.y,z=anchor.z-head.z;
    return std::sqrt(x*x+y*y+z*z);
}
inline bool orbit_drag(PanelGeometry &p,OrbitVector ray,OrbitVector dir,OrbitVector head,float radius,float u,float v,float aspect) {
    const float anchor_x=(u-.5f)*p.width,anchor_y=(.5f-v)*p.width*aspect;
    radius=std::max(radius,std::sqrt(anchor_x*anchor_x+anchor_y*anchor_y)+.25f);
    const float ox=ray.x-head.x,oy=ray.y-head.y,oz=ray.z-head.z;
    const float a=dir.x*dir.x+dir.y*dir.y+dir.z*dir.z;
    if(a<.00001f) return false;
    const float b=ox*dir.x+oy*dir.y+oz*dir.z;
    const float c=ox*ox+oy*oy+oz*oz-radius*radius;
    const float discriminant=b*b-a*c;
    if(discriminant<0) return false;
    float t=(-b-std::sqrt(discriminant))/a;
    if(t<=0) t=(-b+std::sqrt(discriminant))/a;
    if(t<=0) return false;
    const float x=ray.x+t*dir.x,y=ray.y+t*dir.y,z=ray.z+t*dir.z;
    const float dx=(u-.5f)*p.width,dy=(.5f-v)*p.width*aspect;
    // Full spherical facing, with exact local grab anchor (yaw AND pitch).
    // Iterate the center because the grabbed point may be near a corner.
    for(int i=0;i<24;++i) {
        const float hx=head.x-p.x,hy=head.y-p.y,hz=head.z-p.z;
        p.yaw=std::atan2(hx,hz);
        p.pitch=-std::atan2(hy,std::sqrt(hx*hx+hz*hz));
        const auto r=panel_right(p),up=panel_up(p);
        p.x=x-r.x*dx-up.x*dy;
        p.y=y-r.y*dx-up.y*dy;
        p.z=z-r.z*dx-up.z*dy;
    }
    return true;
}
