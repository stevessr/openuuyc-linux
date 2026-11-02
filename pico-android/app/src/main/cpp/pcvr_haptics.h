#pragma once
#include <cstdint>
enum class PcvrHapticDecision {None,Stop,Apply};
inline PcvrHapticDecision pcvr_haptic_decision(bool allowed,bool pending,bool active,bool stop,int64_t age_ns) {
    if(!allowed)return active||pending?PcvrHapticDecision::Stop:PcvrHapticDecision::None;
    if(!pending)return PcvrHapticDecision::None;
    if(stop)return PcvrHapticDecision::Stop;
    if(age_ns<0 || age_ns>250000000)return PcvrHapticDecision::None;
    return PcvrHapticDecision::Apply;
}
