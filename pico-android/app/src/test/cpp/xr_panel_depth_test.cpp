#include "xr_panel_depth.h"
#include <cassert>
int main() {
    PanelGeometry p{0,0,1.65f};
    panel_depth_step(p,{0,0,0},{0,0,-1},1);
    assert(std::fabs(p.z+2.035f)<.0001f);
    panel_depth_step(p,{0,0,0},{0,0,-1},-1);
    assert(std::fabs(p.z+2.f)<.0001f);
    p.z=-6;panel_depth_step(p,{0,0,0},{0,0,-1},1);assert(p.z==-6);
    p.z=-.45f;panel_depth_step(p,{0,0,0},{0,0,-1},-1);assert(p.z==-.45f);
    p.x=3;p.z=-2;panel_depth_step(p,{1,0,-2},{1,0,0},1);
    assert(std::fabs(p.x-3.035f)<.0001f && p.z==-2);
}
