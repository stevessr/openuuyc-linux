#pragma once
#include <cstdint>
extern "C" {
#include "../openxr/stereo_capture.h"
}

inline bool mirror_native_extent(int width,int height,uint32_t &out_width,uint32_t &out_height)
{
    if(width<=0 || height<=0) return false;
    const auto native_width=static_cast<uint32_t>(width);
    const auto native_height=static_cast<uint32_t>(height);
    if(!oust_valid_eye_geometry(native_width,native_height,native_width*4u)) return false;
    out_width=native_width;
    out_height=native_height;
    return true;
}
