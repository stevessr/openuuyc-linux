#pragma once
#include <algorithm>
#include <cmath>

struct PanelGeometry { float x, y, width; float yaw = 0.f; float z = -2.f; float aspect = 9.f/16.f; float pitch = 0.f; float roll = 0.f; };
inline bool panel_uv(const PanelGeometry &panel, float x, float y, float &u, float &v) {
    u=(x-panel.x)/panel.width+.5f;
    v=.5f-(y-panel.y)/(panel.width*panel.aspect);
    return u>=0.f && u<=1.f && v>=0.f && v<=1.f;
}
inline void panel_drag(PanelGeometry &panel, float x, float y, float u, float v) {
    panel.x=std::clamp(x-(u-.5f)*panel.width,-5.f,5.f);
    panel.y=std::clamp(y-(.5f-v)*panel.width*panel.aspect,-3.f,3.f);
}
inline float panel_max_width(float aspect);
inline void panel_scale(PanelGeometry &panel, float axis) {
    panel.width=std::clamp(panel.width*(axis>0?1.06f:.94f),.4f,panel_max_width(panel.aspect));
}

inline float panel_max_width(float aspect) {return 2.54f/std::sqrt(1.f+aspect*aspect);}

// Face the user's horizontal viewing direction, without pitching the desktop.
inline float panel_anchor_yaw(float forward_x,float forward_z) {
    return forward_x*forward_x+forward_z*forward_z > .000001f
        ? std::atan2(-forward_x,-forward_z) : 0.f;
}

struct PanelVector {float x,y,z;};
inline PanelVector panel_right(const PanelGeometry &p) {const float c=std::cos(p.roll),s=std::sin(p.roll);return {std::cos(p.yaw)*c+std::sin(p.yaw)*std::sin(p.pitch)*s,std::cos(p.pitch)*s,-std::sin(p.yaw)*c+std::cos(p.yaw)*std::sin(p.pitch)*s};}
inline PanelVector panel_up(const PanelGeometry &p) {const float c=std::cos(p.roll),s=std::sin(p.roll);return {-std::cos(p.yaw)*s+std::sin(p.yaw)*std::sin(p.pitch)*c,std::cos(p.pitch)*c,std::sin(p.yaw)*s+std::cos(p.yaw)*std::sin(p.pitch)*c};}
inline void panel_face_view(PanelGeometry &p,float x,float y,float z,float w) {
 p.pitch=std::asin(std::clamp(2*(w*x-y*z),-1.f,1.f));
 p.yaw=std::atan2(2*(x*z+w*y),1-2*(x*x+y*y));
 p.roll=std::atan2(2*(x*y+w*z),1-2*(x*x+z*z));
}
// Billboard towards the viewer position, not the viewer's gaze direction.
// Keep world-up stable so head roll does not roll remote desktops.
inline bool panel_face_user(PanelGeometry &p,PanelVector eye) {
    const float x=eye.x-p.x,y=eye.y-p.y,z=eye.z-p.z;
    const float horizontal=std::hypot(x,z),distance=std::hypot(horizontal,y);
    if(!std::isfinite(distance) || distance<.001f) return false;
    if(horizontal>.00001f) p.yaw=std::atan2(x,z);
    p.pitch=-std::atan2(y,horizontal);
    p.roll=0;
    return true;
}
inline PanelVector panel_normal(const PanelGeometry &p) {return {std::sin(p.yaw)*std::cos(p.pitch),-std::sin(p.pitch),std::cos(p.yaw)*std::cos(p.pitch)};}
inline float panel_dot(PanelVector a,PanelVector b) {return a.x*b.x+a.y*b.y+a.z*b.z;}
inline PanelVector panel_point(const PanelGeometry &p,float x,float y) {
    const auto r=panel_right(p),u=panel_up(p);
    return {p.x+r.x*x+u.x*y,p.y+r.y*x+u.y*y,p.z+r.z*x+u.z*y};
}
inline bool panel_intersect(const PanelGeometry &p,PanelVector origin,PanelVector dir,float &x,float &y) {
    const auto n=panel_normal(p);
    const float denominator=panel_dot(dir,n);
    if(std::fabs(denominator)<.0001f) return false;
    const float t=panel_dot({p.x-origin.x,p.y-origin.y,p.z-origin.z},n)/denominator;
    if(t<=0) return false;
    const PanelVector hit{origin.x+t*dir.x-p.x,origin.y+t*dir.y-p.y,origin.z+t*dir.z-p.z};
    x=panel_dot(hit,panel_right(p));y=panel_dot(hit,panel_up(p));return true;
}
