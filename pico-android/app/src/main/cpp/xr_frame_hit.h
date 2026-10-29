#pragma once
#include <cmath>
// Normalized distances from screen centre, in units of screen width/height.
// Every pixel INSIDE the video (including exact corners) belongs to the host.
inline bool xr_inside_video(float x,float y,float width,float height) {
    return width>0 && height>0 && std::fabs(x)<=width*.5f && std::fabs(y)<=height*.5f;
}
inline int xr_frame_corner(float x,float y,float width,float height) {
    if(width<=0 || height<=0 || xr_inside_video(x,y,width,height)) return -1;
    const float ax=std::fabs(x)/width,ay=std::fabs(y)/height;
    // Match the visible 1.06 x 1.16 frame atlas, not a broad interior wedge.
    if(ax<.46f || ax>.53f || ay<.46f || ay>.58f) return -1;
    return (y<0?2:0)+(x>0?1:0);
}
inline int xr_frame_target(int pane,float x,float y,float w,float h,bool chrome,float& u,float& v) {
    if(w<=0 || h<=0) return -1;
    u=x/w+.5f;v=.5f-y/h;
    if(xr_inside_video(x,y,w,h)) return pane;
    if(!chrome) return -1;
    u=x;v=y;
    const float px=600.f+x/w*1200.f/1.06f;
    const float py=400.f-y/h*800.f/1.16f;
    auto in=[&](float left,float top,float right,float bottom) {return px>=left && px<=right && py>=top && py<=bottom;};
    // Exact atlas hit rectangles from PicoXrFrameLayout. Video always wins.
    if(in(54,13,326,51)) return 49+pane;
    if(in(348,13,782,51)) return 55+pane;
    if(in(800,13,842,51)) return 67+pane;
    if(in(850,13,892,51)) return 73+pane;
    if(in(910,13,956,51)) return 43+pane;
    if(in(972,13,1018,51)) return 61+pane;
    if(in(1096,13,1154,51)) return 37+pane;
    if(in(460,755,740,795)) {u=x/w+.5f;v=.5f-y/h;return 7+pane;}
    const int corner=xr_frame_corner(x,y,w,h);
    if(corner>=0) return 13+pane*4+corner;
    return -1;
}
