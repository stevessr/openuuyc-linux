#include "xr_stick_release.h"
#include <cassert>
#include <limits>
int main() {
 XrStickRelease s;
 assert(!s.update(.59f,0,true)); assert(!s.update(0,0,true));
 assert(!s.update(.61f,0,true)); assert(!s.update(.26f,0,true));
 assert(s.update(.24f,0,true)); assert(!s.update(.24f,0,true));
 assert(!s.update(0,0,true)); assert(!s.update(.8f,0,true));
 assert(!s.update(.4f,0,true)); assert(s.update(.1f,0,true));
 assert(!s.update(0,0,true)); assert(!s.update(0,-1,true));
 assert(!s.update(0,0,false)); assert(!s.update(0,0,true));
 assert(!s.update(1,1,true)); s.reset(); assert(!s.update(0,0,true));
 assert(!s.update(1,0,true)); assert(!s.update(std::numeric_limits<float>::quiet_NaN(),0,true));
 assert(!s.update(0,0,true));
}
