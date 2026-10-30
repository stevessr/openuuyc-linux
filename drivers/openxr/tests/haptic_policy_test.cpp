#include "../../../pico-android/app/src/main/cpp/pcvr_haptics.h"
#include <cassert>
int main(){using D=PcvrHapticDecision;
 assert(pcvr_haptic_decision(true,true,false,false,0)==D::Apply);
 assert(pcvr_haptic_decision(true,true,false,false,250000000)==D::Apply);
 assert(pcvr_haptic_decision(true,true,false,false,250000001)==D::None);
 assert(pcvr_haptic_decision(true,true,false,false,-1)==D::None);
 assert(pcvr_haptic_decision(true,false,true,false,0)==D::None);
 assert(pcvr_haptic_decision(false,false,true,false,0)==D::Stop);
 assert(pcvr_haptic_decision(false,true,false,false,0)==D::Stop);
 assert(pcvr_haptic_decision(false,false,false,false,0)==D::None);
 assert(pcvr_haptic_decision(true,true,true,true,999999999)==D::Stop);
}
