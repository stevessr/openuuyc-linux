#include "xr_panel_geometry.h"
#include <cassert>
#include <initializer_list>
int main() {
    for(float aspect:{9.f/16.f,16.f/9.f,1.f,9.f/32.f}) {
        const float width=panel_max_width(aspect),height=width*aspect;
        assert(std::fabs(std::sqrt(width*width+height*height)-2.54f)<.00001f);
        assert(std::fabs(height/width-aspect)<.00001f);
    }
}
