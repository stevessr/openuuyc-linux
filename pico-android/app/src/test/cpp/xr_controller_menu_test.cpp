#include "xr_controller_menu.h"
#include <cassert>
int main() {
    LayoutPose grip{{0,0,0,1},{-.3f,-.4f,-.5f}};
    PoseVector eye{0,0,0};
    const auto menu=controller_menu_panel(grip,eye,0,.42f,2.f/3.f);
    assert(std::fabs(menu.x+.36f)<.00001f);
    assert(std::fabs(menu.y-(-.4f+.035f+.14f))<.00001f);
    auto normal=panel_normal(menu);
    PanelVector to_eye{-menu.x,-menu.y,-menu.z};
    const float length=std::sqrt(panel_dot(to_eye,to_eye));
    assert(panel_dot(normal,to_eye)/length>.9999f);
    grip.position.x+=.2f;grip.position.y+=.1f;
    const auto moved=controller_menu_panel(grip,eye,0,.42f,2.f/3.f);
    assert(std::fabs(moved.x-menu.x-.2f)<.00001f);
    assert(std::fabs(moved.y-menu.y-.1f)<.00001f);
    // Layout/camera transforms are not accumulated into the grip anchor.
    const auto repeated=controller_menu_panel(grip,eye,0,.42f,2.f/3.f);
    assert(repeated.x==moved.x && repeated.y==moved.y && repeated.z==moved.z);
    const auto right=controller_menu_panel(grip,eye,1,.42f,2.f/3.f);
    assert(std::fabs(right.x-moved.x-.12f)<.00001f);
}
