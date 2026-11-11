#include "../tracker_source.h"
#include <cassert>
#include <limits>
int main() {
    TrackerSlots slots;
    assert(!slots.assign(""));
    for(unsigned i=1;i<=8;i++)assert(slots.assign(std::to_string(i))==i);
    assert(!slots.assign("ninth"));
    assert(slots.assign("3")==3); // reconnect never allocates a new body ID
    assert(slots.assign("1")==1);
    TrackerSlots configured;
    configured.configure("7=left-foot");
    configured.configure("2=hip");
    assert(configured.assign("hip")==2);
    assert(configured.assign("left-foot")==7);
    assert(!configured.assign("unlisted"));
    TrackerSlots restarted;
    restarted.configure("2=hip");
    restarted.configure("7=left-foot");
    assert(restarted.assign("left-foot")==configured.assign("left-foot"));
    for(const std::string invalid : {"0=bad","9=bad","12=bad","1=","1:bad","2=other","3=hip"}) {
        bool rejected=false;
        try {configured.configure(invalid);}catch(const std::invalid_argument&) {rejected=true;}
        assert(rejected);
    }
    assert(configured.assign("hip")==2);
    vr::TrackedDevicePose_t tracked{};
    tracked.bDeviceIsConnected=true;tracked.bPoseIsValid=true;
    tracked.eTrackingResult=vr::TrackingResult_Running_OK;
    tracked.mDeviceToAbsoluteTracking={{{1,0,0,1},{0,1,0,2},{0,0,1,3}}};
    float pose[7]{};
    assert(tracker_pose(tracked,pose));assert(pose[0]==1 && pose[1]==2 && pose[2]==3 && pose[6]==1);
    tracked.bDeviceIsConnected=false;assert(!tracker_pose(tracked,pose));
    tracked.bDeviceIsConnected=true;tracked.bPoseIsValid=false;assert(!tracker_pose(tracked,pose));
    tracked.bPoseIsValid=true;tracked.eTrackingResult=vr::TrackingResult_Running_OutOfRange;assert(!tracker_pose(tracked,pose));
    tracked.eTrackingResult=vr::TrackingResult_Running_OK;
    tracked.mDeviceToAbsoluteTracking.m[0][0]=std::numeric_limits<float>::quiet_NaN();
    assert(!tracker_pose(tracked,pose));
}
