#pragma once
#include "xr_panel_geometry.h"
#include "xr_pose_math.h"
// All coordinates are in the same current layout space used by ray hit tests.
inline PanelGeometry controller_menu_panel(LayoutPose grip,PoseVector eye,int hand,float width,float aspect) {
    const auto offset=pose_rotate(grip.rotation,{hand==0?-.06f:.06f,.035f+width*aspect*.5f,-.10f});
    PanelGeometry panel{grip.position.x+offset.x,grip.position.y+offset.y,width,0,grip.position.z+offset.z,aspect,0};
    const float dx=eye.x-panel.x,dy=eye.y-panel.y,dz=eye.z-panel.z;
    panel.yaw=std::atan2(dx,dz);
    panel.pitch=-std::atan2(dy,std::sqrt(dx*dx+dz*dz));
    return panel;
}
