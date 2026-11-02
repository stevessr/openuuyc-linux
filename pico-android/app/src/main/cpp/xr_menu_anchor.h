#pragma once
#include "xr_panel_geometry.h"

// One default for launch, reset, return-home and closed-host fallback.
inline PanelGeometry default_menu_panel() {
    return {0.f,-.82f,1.65f,0.f,-2.8f,1.f/3.f,0.f};
}

// Bottom edge of popup clears the monitor's top edge in the monitor's basis.
// Use actual popup aspect so nested portrait/landscape pages stay above it.
inline PanelGeometry panel_menu_above(const PanelGeometry &host,float width,float aspect) {
    const auto point=panel_point(host,0,host.width*host.aspect*.5f+.06f+width*aspect*.5f);
    const auto n=panel_normal(host);
    return {point.x+n.x*.025f,point.y+n.y*.025f,width,host.yaw,
            point.z+n.z*.025f,aspect,host.pitch};
}
