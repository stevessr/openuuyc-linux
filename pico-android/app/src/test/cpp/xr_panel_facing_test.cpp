#include "xr_panel_geometry.h"
#include <cassert>
#include <limits>
int main() {
    PanelGeometry p{.7f,-.4f,1.6f};p.z=-2;
    for(float x:{-2.f,0.f,2.f}) for(float y:{-.8f,0.f,1.2f}) {
        const PanelVector eye{x,y,.5f};
        assert(panel_face_user(p,eye));
        PanelVector d{eye.x-p.x,eye.y-p.y,eye.z-p.z};
        assert(panel_dot(panel_normal(p),d)/std::sqrt(panel_dot(d,d))>.99999f);
        assert(p.roll==0);
        float hx,hy;
        assert(panel_intersect(p,eye,{-d.x,-d.y,-d.z},hx,hy));
        assert(std::fabs(hx)<.0001f && std::fabs(hy)<.0001f);
    }
    const auto previous=p;
    assert(!panel_face_user(p,{p.x,p.y,p.z}));
    assert(!panel_face_user(p,{std::numeric_limits<float>::quiet_NaN(),0,0}));
    assert(p.yaw==previous.yaw && p.pitch==previous.pitch);
    assert(panel_face_user(p,{p.x,p.y+1,p.z}));
    assert(std::isfinite(p.yaw) && std::isfinite(p.pitch));
}
