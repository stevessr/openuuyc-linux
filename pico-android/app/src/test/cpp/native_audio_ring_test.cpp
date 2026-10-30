#include "native_audio_ring.h"
#include <cassert>
int main(){ouaudio::Ring r;float in[240];for(auto&x:in)x=.25f;float out[240];assert(r.push(in,120));r.pull(out,120);for(auto x:out)assert(x==0);r.armed=true;r.pull(out,120);for(auto x:out)assert(x==.25f);r.pull(out,120);assert(r.underruns==1);r.reset=true;assert(!r.push(in,120));r.pull(out,120);assert(!r.reset);uint8_t p[504]{};std::memcpy(p,"OUA1",4);p[4]=1;p[6]=120;p[8]=1;p[20]=224;p[21]=1;ouaudio::Header h{};assert(ouaudio::parse(p,504,h));assert(!ouaudio::parse(p,503,h));p[5]=2;assert(!ouaudio::parse(p,504,h));}
