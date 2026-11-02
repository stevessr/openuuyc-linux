#include <initializer_list>
#include "xr_frame_hit.h"
#include <cassert>
int main() {
    for(float aspect:{9.f/16.f,16.f/9.f,1.f}) {
        const float w=1.6f,h=w*aspect;
        for(int sx:{-1,1}) for(int sy:{-1,1}) {
            for(float x:{.38f,.46f,.49f,.5f}) for(float y:{.42f,.46f,.49f,.5f}) {
                assert(xr_inside_video(sx*x*w,sy*y*h,w,h));
                assert(xr_frame_corner(sx*x*w,sy*y*h,w,h)==-1);
                float u=0,v=0;assert(xr_frame_target(2,sx*x*w,sy*y*h,w,h,true,u,v)==2);
                assert(u>=0 && u<=1 && v>=0 && v<=1);
            }
            assert(xr_frame_corner(sx*.515f*w,sy*.55f*h,w,h)>=0);
            assert(xr_frame_corner(sx*.55f*w,sy*.6f*h,w,h)==-1);
        }
    }
    // Every visible top-bar button and the compact bottom rail have exact hit parity.
    struct Control {float x,y;int target;};
    const Control controls[]={{190,32,49},{565,32,55},{821,32,67},{871,32,73},{933,32,43},{995,32,61},{1125,32,37},{600,775,7}};
    for(float aspect:{9.f/16.f,16.f/9.f,1.f}) for(int pane=0;pane<6;++pane) for(const auto& c:controls) {
        const float w=1.6f,h=w*aspect;
        const float x=(c.x/1200-.5f)*1.06f*w,y=(.5f-c.y/800)*1.16f*h;
        float u=0,v=0;assert(xr_frame_target(pane,x,y,w,h,true,u,v)==c.target+pane);
    }
}
