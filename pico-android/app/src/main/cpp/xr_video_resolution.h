#pragma once
#include <algorithm>
#include <cstdint>
// A fixed landscape swapchain silently reduces 1080x1920 to 1080 vertical
// samples. A square budget preserves either orientation without recreating a
// live Android Surface / losing its decoder. The display quad keeps frame aspect.
inline uint32_t xr_video_edge(uint32_t max_width,uint32_t max_height) {
    return std::min(2160u,std::min(max_width,max_height));
}
