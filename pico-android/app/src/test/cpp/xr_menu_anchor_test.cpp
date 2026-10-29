#include "xr_menu_anchor.h"
#include <cassert>
#include <initializer_list>
int main() {
    const auto menu=default_menu_panel();
    assert(menu.z==-2.8f && menu.width==1.65f);
    assert(menu.x==0.f && menu.y==-.82f && menu.yaw==0.f && menu.pitch==0.f);
    assert(std::fabs(menu.aspect-1.f/3.f)<.00001f);
    for(float aspect: {9.f/16.f,16.f/9.f}) for(float yaw: {0.f,.6f}) for(float pitch: {-.5f,.4f})
    for(float popup: {1.f/3.f,1000.f/600.f}) {
        PanelGeometry host{.4f,.2f,1.2f,yaw,-2.f,aspect,pitch};
        auto m=panel_menu_above(host,.85f,popup);
        const auto bottom=panel_point(m,0,-m.width*m.aspect*.5f);
        const auto top=panel_point(host,0,host.width*host.aspect*.5f);
        PanelVector d{bottom.x-top.x,bottom.y-top.y,bottom.z-top.z};
        assert(std::fabs(panel_dot(d,panel_up(host))-.06f)<.00001f);
        assert(std::fabs(panel_dot(d,panel_right(host)))<.00001f);
        assert(std::fabs(panel_dot(d,panel_normal(host))-.025f)<.00001f);
        assert(m.yaw==yaw && m.pitch==pitch);
    }
}
