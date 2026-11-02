#include "xr_panel_geometry.h"
#include <cassert>
int main() {
    assert(std::fabs(panel_anchor_yaw(0,-1))<.00001f);
    assert(std::fabs(panel_anchor_yaw(-1,0)-1.5707963f)<.00001f);
    assert(std::fabs(panel_anchor_yaw(1,0)+1.5707963f)<.00001f);
    assert(std::fabs(std::fabs(panel_anchor_yaw(0,1))-3.1415926f)<.00001f);
    assert(panel_anchor_yaw(0,0)==0); // looking vertically must stay finite

    PanelGeometry p{1,2,2}; float u=0,v=0;
    assert(panel_uv(p,1,2,u,v)); assert(u==.5f && v==.5f);
    assert(!panel_uv(p,-2,2,u,v));
    panel_drag(p,2,1,.25f,.75f);
    assert(panel_uv(p,2,1,u,v)); assert(std::fabs(u-.25f)<.0001f && std::fabs(v-.75f)<.0001f);
    for(int i=0;i<1000;++i) panel_scale(p,1);
    assert(p.width==panel_max_width(p.aspect));
    for(int i=0;i<1000;++i) panel_scale(p,-1);
    assert(p.width==.4f);
    panel_drag(p,100,-100,.5f,.5f); assert(p.x==5.f && p.y==-3.f);
}
