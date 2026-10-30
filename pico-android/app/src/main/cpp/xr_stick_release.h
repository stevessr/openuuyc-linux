#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
// A deliberate spring release, not merely reaching the dead zone. A cancelled
// gesture must return to centre before it can arm again.
struct XrStickRelease {
    bool armed=false,returning=false,cancelled=false;
    float peak=0,lowest=0;
    int64_t last=0,started=0,progress=0;
    void reset() {armed=returning=cancelled=false;peak=lowest=0;last=started=progress=0;}
    bool update(float x,float y,bool active,int64_t now) {
        if(!active || !std::isfinite(x) || !std::isfinite(y) || now<=0) {reset();return false;}
        const float radius=std::max(std::fabs(x),std::fabs(y));
        const auto previous=last;
        if(previous && (now<=previous || now-previous>80000000LL)) {
            reset();cancelled=true;
        }
        last=now;
        if(cancelled) {if(radius<.25f) reset();return false;}
        if(!armed) {
            if(radius>.6f) {armed=true;peak=lowest=radius;}
            return false;
        }
        if(!returning) {
            peak=std::max(peak,radius);
            if(peak-radius>=.08f) {returning=true;started=previous;progress=now;lowest=radius;}
        } else {
            if(radius>lowest+.08f || now-started>160000000LL || now-progress>65000000LL) cancelled=true;
            if(radius<lowest-.025f) {lowest=radius;progress=now;}
        }
        if(radius<.25f) {
            const bool fire=returning && !cancelled && now-started<=160000000LL;
            reset();return fire;
        }
        return false;
    }
};
