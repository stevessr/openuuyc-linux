#include "../../../pico-android/app/src/main/cpp/pcvr_protocol.h"
#include <cassert>
#include <limits>
int main() {
 OuvrFrameV2 frame{};std::memcpy(frame.base.magic,"OUVR",4);frame.base.version=2;frame.base.sequence=1;
 assert(ouvr_valid(frame));
 frame.aim[0].flags=51;assert(!ouvr_valid(frame));frame.aim[0].orientation[3]=1;assert(ouvr_valid(frame));
 frame.controls[0]={127,11,.7f,.4f,-.25f,.75f};assert(ouvr_valid(frame));
 frame.controls[0].trigger=1.01f;assert(!ouvr_valid(frame));frame.controls[0].trigger=.7f;
 frame.controls[1].x=std::numeric_limits<float>::quiet_NaN();assert(!ouvr_valid(frame));frame.controls[1].x=0;
 frame.controls[0].buttons=16;assert(!ouvr_valid(frame));frame.controls[0].buttons=0;
 bool blocked[2]={false,false};
 frame.base.devices[1].flags=3;frame.base.devices[1].orientation[3]=1;frame.base.devices[1].buttons=1;
 auto tracked=frame.base.devices[1];ouvr_route_controls(frame,true,blocked);
 assert(blocked[0] && !frame.controls[0].flags && !frame.base.devices[1].buttons);
 assert(frame.base.devices[1].flags==tracked.flags);
 frame.controls[0]={127,2,.7f,.4f,0,0};ouvr_route_controls(frame,false,blocked);assert(!frame.controls[0].flags&&blocked[0]);
 frame.controls[0]={127,0,0,0,0,0};ouvr_route_controls(frame,false,blocked);assert(!blocked[0]);
 frame.controls[0]={127,2,.7f,.4f,0,0};ouvr_route_controls(frame,false,blocked);assert(frame.controls[0].buttons==2);
 frame.base.version=1;assert(!ouvr_valid(frame));
 OuvrFrame old{};std::memcpy(old.magic,"OUVR",4);old.version=1;old.sequence=1;assert(ouvr_valid(old));
 old.devices[0].flags=16;assert(!ouvr_valid(old));
 return 0;
}
