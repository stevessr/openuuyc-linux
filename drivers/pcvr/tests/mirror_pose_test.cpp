#include "../mirror_pose.h"
#include <cassert>
#include <limits>
int main() {
    vr::HmdMatrix34_t head{{{1,0,0,1},{0,1,0,2},{0,0,1,3}}};
    vr::HmdMatrix34_t eye{{{1,0,0,-.032f},{0,1,0,0},{0,0,1,0}}};
    auto pose=mirror_product(head,eye);float out[11];
    assert(mirror_view(pose,-1,1,-1,1,out));
    assert(std::fabs(out[0]-.968f)<.0001f && out[1]==2 && out[2]==3);
    assert(out[6]==1 && out[7]<0 && out[8]>0 && out[9]>0 && out[10]<0);
    vr::HmdMatrix34_t yaw{{{0,0,1,0},{0,1,0,0},{-1,0,0,0}}};
    assert(mirror_view(yaw,-1,1,-1,1,out));
    assert(std::fabs(out[4]-.70710678f)<.0001f && std::fabs(out[6]-.70710678f)<.0001f);
    vr::HmdMatrix34_t roll{{{-1,0,0,0},{0,-1,0,0},{0,0,1,0}}};
    assert(mirror_view(roll,-1,1,-1,1,out));assert(std::fabs(out[5])>.999f);
    /* The exported head pose shares the rigid-pose validation and carries the
     * real compositor position/rotation, not the eye pose. */
    head.m[0][0]=1;
    float exported[7];
    assert(mirror_head(head,exported));
    assert(exported[0]==1 && exported[1]==2 && exported[2]==3);
    assert(exported[3]==0 && exported[4]==0 && exported[5]==0 && exported[6]==1);
    assert(mirror_head(yaw,exported));
    assert(std::fabs(exported[4]-.70710678f)<.0001f && std::fabs(exported[6]-.70710678f)<.0001f);
    assert(mirror_head(eye,exported) && std::fabs(exported[0]+.032f)<.0001f);
    head.m[0][0]=-1;assert(!mirror_view(head,-1,1,-1,1,out));
    assert(!mirror_head(head,exported));
    head.m[0][0]=std::numeric_limits<float>::quiet_NaN();
    assert(!mirror_view(head,-1,1,-1,1,out));
    assert(!mirror_head(head,exported));
}
