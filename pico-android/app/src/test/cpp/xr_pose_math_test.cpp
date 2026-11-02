#include "xr_pose_math.h"
#include <cassert>
int main() {
    const LayoutPose initial{{0,0,0,1},{1,1.6f,-2}};
    LayoutPose layout=initial;
    for(int i=0;i<200;++i) {
        float angle=i*.031f;
        LayoutPose camera{{0,std::sin(angle/2),0,std::cos(angle/2)},{.3f,1.7f,.4f}};
        auto relative=pose_compose(pose_inverse(camera),layout);
        auto restored=pose_compose(camera,relative);
        assert(std::fabs(restored.position.x-layout.position.x)<.0001f);
        assert(std::fabs(restored.position.y-layout.position.y)<.0001f);
        assert(std::fabs(restored.position.z-layout.position.z)<.0001f);
        layout=restored;
    }
    assert(std::fabs(layout.position.x-initial.position.x)<.001f);
    assert(std::fabs(layout.position.y-initial.position.y)<.001f);
    assert(std::fabs(layout.position.z-initial.position.z)<.001f);
    LayoutPose camera{{std::sin(.3f),0,0,std::cos(.3f)},{2,1.6f,3}};
    const auto capture=pose_compose(pose_inverse(camera),initial);
    camera.position.x+=1;
    const auto followed=pose_compose(camera,capture);
    assert(std::fabs(followed.position.x-initial.position.x-1)<.0001f);
    assert(std::fabs(followed.position.y-initial.position.y)<.0001f);
}
