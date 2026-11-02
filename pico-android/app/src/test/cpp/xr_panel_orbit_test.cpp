#include "xr_panel_orbit.h"
#include "xr_pose_math.h"
#include <cassert>
#include <initializer_list>
int main() {
    for(float width:{1.65f,1.25f}) {
        PanelGeometry p{0,0,width};OrbitVector head{0,0,0};
        float r=orbit_radius(p,head,.5f,.5f,9.f/16.f);
        assert(orbit_drag(p,head,{1,0,0},head,r,.5f,.5f,9.f/16.f));
        assert(std::fabs(p.x-2)<.0001f && std::fabs(p.z)<.0001f);
        assert(std::fabs(p.yaw+1.5707963f)<.0001f);
        assert(orbit_drag(p,head,{0,0,1},head,r,.5f,.5f,9.f/16.f));
        assert(std::fabs(p.z-2)<.0001f); // not constrained to world's negative Z
    }
    PanelGeometry menu{0,-.82f,1.65f};OrbitVector head{1,0,1};
    const float r=orbit_radius(menu,head,.5f,.5f,1.f/3.f);
    assert(orbit_drag(menu,head,{1,0,0},head,r,.5f,.5f,1.f/3.f));
    assert(std::fabs(menu.x-(1+r))<.0001f && menu.z==1);
    for(float dy:{-1.f,1.f}) {
        PanelGeometry p{0,0,1.65f};OrbitVector eye{0,0,0};
        assert(orbit_drag(p,{0,0,0},{0,dy,-1},eye,2,.5f,.5f,p.aspect));
        assert(std::fabs(std::sqrt(p.x*p.x+p.y*p.y+p.z*p.z)-2)<.0001f);
        const auto n=panel_normal(p);
        assert(panel_dot(n,{-p.x,-p.y,-p.z})>1.999f);
        assert(dy*p.pitch>0);
        float x=0,y=0;
        assert(panel_intersect(p,{0,0,0},{0,dy,-1},x,y));
        assert(std::fabs(x)<.0001f && std::fabs(y)<.0001f);
    }
    for(float u:{.15f,.85f}) for(float v:{.15f,.85f}) {
        PanelGeometry p{0,0,1.65f};OrbitVector eye{.3f,1.2f,.7f};
        const float radius=orbit_radius(p,eye,u,v,p.aspect);
        assert(orbit_drag(p,eye,{.3f,1,-1},eye,radius,u,v,p.aspect));
        const auto anchor=panel_point(p,(u-.5f)*p.width,(.5f-v)*p.width*p.aspect);
        const float norm=std::sqrt(2.09f);
        assert(std::fabs(anchor.x-eye.x-radius*.3f/norm)<.001f);
        assert(std::fabs(anchor.y-eye.y-radius/norm)<.001f);
        assert(std::fabs(anchor.z-eye.z+radius/norm)<.001f);
    }
    {
        PanelGeometry p{0,0,2.f};OrbitVector viewer{1,.8f,.6f};
        assert(orbit_drag(p,{1,.8f,.6f},{.4f,.7f,-1.f},viewer,2.f,.5f,.5f,p.aspect));
        const auto n=panel_normal(p);PanelVector towards{viewer.x-p.x,viewer.y-p.y,viewer.z-p.z};
        assert(panel_dot(n,towards)/std::sqrt(panel_dot(towards,towards))>.999f);
        assert(orbit_drag(p,{1,.8f,.6f},{0,0,-1},viewer,.45f,.98f,.98f,p.aspect));
        assert(std::isfinite(p.yaw) && std::isfinite(p.pitch));
    }
    // Dragged panels stay parallel to the current VIEW, including head roll,
    // while keeping an off-centre grab point exactly on the pointer sphere.
    for(float yaw:{-.8f,.7f}) for(float pitch:{-.6f,.5f}) for(float roll:{-.4f,.3f}) {
        const auto q=pose_product(pose_product({0,std::sin(yaw/2),0,std::cos(yaw/2)},
            {std::sin(pitch/2),0,0,std::cos(pitch/2)}),{0,0,std::sin(roll/2),std::cos(roll/2)});
        PanelGeometry p{0,0,1.5f};panel_face_view(p,q.x,q.y,q.z,q.w);
        const auto normal=pose_rotate(q,{0,0,1}),right=pose_rotate(q,{1,0,0}),up=pose_rotate(q,{0,1,0});
        assert(panel_dot(panel_normal(p),{normal.x,normal.y,normal.z})>.9999f);
        assert(panel_dot(panel_right(p),{right.x,right.y,right.z})>.9999f);
        assert(panel_dot(panel_up(p),{up.x,up.y,up.z})>.9999f);
        assert(orbit_drag(p,{0,0,0},{0,0,-1},{0,0,0},2,.85f,.1f,p.aspect,true));
        assert(panel_dot(panel_up(p),{up.x,up.y,up.z})>.9999f);
        const auto anchor=panel_point(p,.35f*p.width,.4f*p.width*p.aspect);
        assert(std::fabs(anchor.x)<.0001f && std::fabs(anchor.y)<.0001f && std::fabs(anchor.z+2)<.0001f);
    }
}
