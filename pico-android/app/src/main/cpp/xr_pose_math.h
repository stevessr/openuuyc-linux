#pragma once
#include <cmath>
struct PoseVector {float x=0,y=0,z=0;};
struct PoseRotation {float x=0,y=0,z=0,w=1;};
struct LayoutPose {PoseRotation rotation;PoseVector position;};
inline PoseRotation pose_product(PoseRotation a,PoseRotation b) {
    PoseRotation q{a.w*b.x+a.x*b.w+a.y*b.z-a.z*b.y,a.w*b.y-a.x*b.z+a.y*b.w+a.z*b.x,a.w*b.z+a.x*b.y-a.y*b.x+a.z*b.w,a.w*b.w-a.x*b.x-a.y*b.y-a.z*b.z};
    const float n=std::sqrt(q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w);
    if(n>0) {q.x/=n;q.y/=n;q.z/=n;q.w/=n;}return q;
}
inline PoseVector pose_rotate(PoseRotation q,PoseVector v) {
    const PoseVector t{2*(q.y*v.z-q.z*v.y),2*(q.z*v.x-q.x*v.z),2*(q.x*v.y-q.y*v.x)};
    return {v.x+q.w*t.x+q.y*t.z-q.z*t.y,v.y+q.w*t.y+q.z*t.x-q.x*t.z,v.z+q.w*t.z+q.x*t.y-q.y*t.x};
}
inline LayoutPose pose_compose(LayoutPose a,LayoutPose b) {
    auto p=pose_rotate(a.rotation,b.position);
    return {pose_product(a.rotation,b.rotation),{a.position.x+p.x,a.position.y+p.y,a.position.z+p.z}};
}
inline LayoutPose pose_inverse(LayoutPose a) {
    PoseRotation q{-a.rotation.x,-a.rotation.y,-a.rotation.z,a.rotation.w};
    return {q,pose_rotate(q,{-a.position.x,-a.position.y,-a.position.z})};
}
