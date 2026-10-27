#pragma once
#include <cmath>
struct RayVector {float x,y,z;};
inline RayVector ray_cross(RayVector a,RayVector b) {return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
inline RayVector ray_unit(RayVector a) {const float n=std::sqrt(a.x*a.x+a.y*a.y+a.z*a.z);return n>.00001f?RayVector{a.x/n,a.y/n,a.z/n}:RayVector{1,0,0};}
struct RayBasis {RayVector right,up,normal;};
inline RayBasis ray_basis(RayVector direction,RayVector towards_eye) {
    auto up=ray_unit(direction);
    auto right=ray_cross(up,towards_eye);
    if(right.x*right.x+right.y*right.y+right.z*right.z<.00001f) right=ray_cross(up,{0,1,0});
    if(right.x*right.x+right.y*right.y+right.z*right.z<.00001f) right=ray_cross(up,{1,0,0});
    right=ray_unit(right);
    return {right,up,ray_unit(ray_cross(right,up))};
}
