#include "pcvr_protocol.h"
#include <cassert>
#include <limits>
#include <cmath>
#include <cstring>

int main() {
    OuvrFrame legacy{};
    std::memcpy(legacy.magic, "OUVR", 4);
    legacy.version = 1;
    legacy.sequence = 1;
    assert(ouvr_valid(legacy));
    legacy.version = 3;
    assert(!ouvr_valid(legacy));

    OuvrFrameV2 v2{};
    std::memcpy(v2.base.magic, "OUVR", 4);
    v2.base.version = 2;
    v2.base.sequence = 1;
    assert(ouvr_valid(v2));

    OuvrFrameV3 v3{};
    std::memcpy(v3.tracking.base.magic, "OUVR", 4);
    v3.tracking.base.version = 3;
    v3.tracking.base.sequence = 1;
    v3.tracking.base.xr_time = 1'000'000'000;
    assert(ouvr_valid(v3)); // No sample means uniform encoding.

    v3.gaze.flags = 1;
    v3.gaze.sample_time = 995'000'000;
    v3.gaze.pose[2] = -0.03f;
    v3.gaze.pose[6] = 1.f;
    assert(ouvr_valid(v3));

    auto invalid = v3;
    invalid.gaze.sample_time = invalid.tracking.base.xr_time + 100'000'001;
    assert(!ouvr_valid(invalid));
    invalid = v3;
    invalid.gaze.pose[6] = 0.f;
    assert(!ouvr_valid(invalid));
    invalid = v3;
    invalid.gaze.pose[0] = std::numeric_limits<float>::quiet_NaN();
    assert(!ouvr_valid(invalid));
    invalid = v3;
    invalid.gaze.flags = 0;
    assert(!ouvr_valid(invalid));
    invalid = v3;
    invalid.gaze.flags = 2;
    assert(!ouvr_valid(invalid));
    invalid = v3;
    invalid.tracking.base.version = 2;
    assert(!ouvr_valid(invalid));
}
