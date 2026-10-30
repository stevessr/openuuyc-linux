#include "xr_stick_release.h"
#include <cassert>
#include <limits>
int main() {
 XrStickRelease s; int64_t t=1000000000;
 auto step=[&](float r,int ms=16,bool active=true) {t+=ms*1000000LL;return s.update(r,0,active,t);};
 assert(!step(.59f));assert(!step(0)); // below activation
 assert(!step(.9f));assert(!step(.6f));assert(!step(.35f));assert(step(.1f));assert(!step(0));
 assert(!step(.9f));for(int i=0;i<30;++i) assert(!step(.9f));assert(step(0)); // holding is fine
 assert(!step(.9f));assert(!step(.7f));assert(!step(.5f,70));assert(!step(.1f)); // pause
 assert(!step(.9f));assert(!step(.7f));assert(!step(.85f));assert(!step(0)); // reversal
 assert(!step(.9f));for(int i=8;i>=0;--i) assert(!step(i*.1f,40)); // hesitant slow return
 assert(!step(.9f));assert(!step(0,100));assert(!step(0)); // frame gap
 assert(!step(.9f));assert(!step(0,16,false));assert(!step(0)); // loss
 assert(!step(.9f));s.reset();assert(!step(0)); // menu close
 assert(!step(.9f));assert(!step(std::numeric_limits<float>::quiet_NaN()));assert(!step(0));
 assert(!step(.9f));assert(step(0)); // re-arm after cancellation
 assert(!step(-.9f));assert(!step(-.45f));assert(step(0));
 t+=16000000;assert(!s.update(0,.9f,true,t));
 t+=16000000;assert(s.update(0,0,true,t));
 assert(!step(.9f));assert(!step(.6f));assert(!step(.6f,70));
 assert(!step(.95f));assert(!step(.5f));assert(!step(0)); // cannot re-arm before centre
}
