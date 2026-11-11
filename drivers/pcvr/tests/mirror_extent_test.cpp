#include "../mirror_extent.h"
#include <cassert>
#include <cstdint>
#include <initializer_list>

int main()
{
    uint32_t width=0,height=0;
    assert(mirror_native_extent(2560,1440,width,height));
    assert(width==2560 && height==1440);
    assert(mirror_native_extent(4096,4096,width,height));
    assert(width==4096 && height==4096);
    assert(!mirror_native_extent(8192,4096,width,height));
    assert(!mirror_native_extent(4096,4095,width,height));
    assert(!mirror_native_extent(63,64,width,height));
    for (int invalid : {-1, 0, 63, 65, 4097, 2147483647}) {
        width=123; height=456;
        assert(!mirror_native_extent(invalid,64,width,height));
        assert(width==123 && height==456);
        assert(!mirror_native_extent(64,invalid,width,height));
        assert(width==123 && height==456);
    }
    assert(mirror_native_extent(64,64,width,height));
    assert(width==64 && height==64);
}
