#pragma once
#include <cstdint>
class XrGripHold {
    int target_=-1;
    bool active_=false;
public:
    int update(bool held,int pointed_window,int64_t /*now*/) {
        if(!held) {reset();return -1;}
        if(active_) return target_;
        if(pointed_window<0 || pointed_window>5) {reset();return -1;}
        target_=pointed_window;active_=true;
        return active_?target_:-1;
    }
    void reset() {target_=-1;active_=false;}
};
