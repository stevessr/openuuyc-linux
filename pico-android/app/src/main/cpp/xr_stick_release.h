#pragma once
#include <cmath>
#include <algorithm>
// Hysteresis prevents stick noise from selecting or firing twice. Losing
// tracking/closing the menu cancels, rather than executing a stale selection.
struct XrStickRelease {
    bool armed=false;
    void reset() {armed=false;}
    bool update(float x,float y,bool active) {
        if(!active || !std::isfinite(x) || !std::isfinite(y)) {reset();return false;}
        const float radius=std::max(std::fabs(x),std::fabs(y));
        if(radius>.6f) armed=true;
        if(armed && radius<.25f) {armed=false;return true;}
        return false;
    }
};
