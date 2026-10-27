#include "xr_ray_geometry.h"
#include <cassert>
#include <initializer_list>
int main() {
    for(auto eye:{RayVector{0,1,0},RayVector{0,0,-1},RayVector{1,0,0}}) {
        const auto b=ray_basis({0,0,-1},eye);
        assert(std::isfinite(b.normal.x) && std::isfinite(b.normal.y) && std::isfinite(b.normal.z));
        assert(std::fabs(b.right.x*b.up.x+b.right.y*b.up.y+b.right.z*b.up.z)<.0001f);
        assert(std::fabs(b.normal.x*b.up.x+b.normal.y*b.up.y+b.normal.z*b.up.z)<.0001f);
        assert(std::fabs(b.normal.x*b.normal.x+b.normal.y*b.normal.y+b.normal.z*b.normal.z-1)<.0001f);
    }
}
