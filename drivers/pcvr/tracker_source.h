#pragma once
#include "mirror_pose.h"
#include <map>
#include <optional>
#include <string>
#include <stdexcept>

class TrackerSlots {
    std::map<std::string,unsigned> slots;
    bool explicit_mapping=false;
public:
    void configure(const std::string& mapping) {
        if(mapping.size()<3 || mapping.size()>1025 || mapping[0]<'1' || mapping[0]>'8' || mapping[1]!='=')
            throw std::invalid_argument("tracker mapping must be ID=SERIAL, ID 1..8");
        const unsigned id=static_cast<unsigned>(mapping[0]-'0');
        const auto serial=mapping.substr(2);
        if(slots.count(serial))throw std::invalid_argument("duplicate tracker serial mapping");
        for(const auto& slot:slots)
            if(slot.second==id)throw std::invalid_argument("duplicate tracker ID mapping");
        slots.emplace(serial,id);
        explicit_mapping=true;
    }
    std::optional<unsigned> assign(const std::string& serial) {
        if(serial.empty())return std::nullopt;
        const auto found=slots.find(serial);
        if(found!=slots.end())return found->second;
        if(explicit_mapping || slots.size()>=8)return std::nullopt;
        const auto id=static_cast<unsigned>(slots.size()+1);
        slots.emplace(serial,id);
        return id;
    }
};
inline bool tracker_pose(const vr::TrackedDevicePose_t& tracked,float pose[7]) {
    return tracked.bDeviceIsConnected && tracked.bPoseIsValid &&
        tracked.eTrackingResult==vr::TrackingResult_Running_OK &&
        mirror_head(tracked.mDeviceToAbsoluteTracking,pose);
}
