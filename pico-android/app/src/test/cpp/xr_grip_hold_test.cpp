#include "xr_grip_hold.h"
#include <cassert>
int main() {
    XrGripHold grip;
    assert(grip.update(true,1,1)==1); // immediate, first input frame
    assert(grip.update(true,2,2)==1); // captured until release
    assert(grip.update(true,-1,3)==1);
    assert(grip.update(false,-1,4)==-1);
    assert(grip.update(true,0,4)==0); // no cooldown after release
    grip.reset();
    assert(grip.update(true,-1,5)==-1);
    assert(grip.update(true,6,6)==-1);
    assert(grip.update(true,5,7)==5);
}
