#include "xr_panel_orbit.h"
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
}
