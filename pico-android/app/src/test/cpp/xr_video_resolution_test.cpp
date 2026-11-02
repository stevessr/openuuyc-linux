#include "xr_video_resolution.h"
#include <cassert>
int main() {
    assert(xr_video_edge(4096,4096)==2160);
    assert(xr_video_edge(1920,1920)==1920);
    assert(xr_video_edge(4096,1440)==1440);
    assert(xr_video_edge(0,4096)==0);
    assert(xr_video_edge(4096,4096)>=1920); // both portrait and landscape FHD preserved
}
