#include "xr_workspace.h"
#include <cassert>
#include <cmath>

static PanelGeometry pane(float width, float aspect = 9.f / 16.f) {
    PanelGeometry p{0.f, 0.f, width, 0.f, -2.f, aspect, 0.f, 0.f};
    return p;
}

static void fresh(PanelGeometry *panels) {
    for (int i = 0; i < WORKSPACE_SLOTS; ++i) panels[i] = pane(i == 0 ? 1.65f : 1.25f);
}

static float distance_between(const PanelGeometry &a, const PanelGeometry &b) {
    return std::hypot(a.x - b.x, a.z - b.z);
}

int main() {
    assert(workspace_clamp_distance(.1f) == WORKSPACE_MIN_DISTANCE);
    assert(workspace_clamp_distance(9.f) == WORKSPACE_MAX_DISTANCE);
    assert(workspace_clamp_distance(NAN) == 2.2f);
    assert(workspace_clamp_mode(-1) == WORKSPACE_AUTO);
    assert(workspace_clamp_mode(3) == WORKSPACE_AUTO);
    assert(workspace_clamp_mode(WORKSPACE_WRAP) == WORKSPACE_WRAP);

    PanelGeometry p[WORKSPACE_SLOTS];

    // One desktop: centred, upright, at the chosen distance.
    fresh(p);
    workspace_layout(p, 1u << 0, 2.2f, WORKSPACE_AUTO);
    assert(std::fabs(p[0].x) < .00001f);
    assert(std::fabs(p[0].z + 2.2f) < .00001f);
    assert(std::fabs(p[0].y) < .00001f);
    assert(std::fabs(p[0].yaw) < .00001f);

    // Two desktops: the primary stays centred and the second sits to its right,
    // which is the layout an office user expects from a dual-monitor desk.
    fresh(p);
    workspace_layout(p, 0b011u, 2.f, WORKSPACE_FLAT);
    assert(std::fabs(p[0].x) < .00001f);
    assert(p[1].x > p[0].x);
    assert(std::fabs(p[1].x - (1.65f * .5f + WORKSPACE_GAP + 1.25f * .5f)) < .00001f);
    assert(std::fabs(p[1].z + 2.f) < .00001f);
    assert(std::fabs(p[1].y) < .00001f);

    // Three desktops, flat: primary centre, first extra right, second left.
    fresh(p);
    workspace_layout(p, 0b111u, 2.f, WORKSPACE_FLAT);
    assert(std::fabs(p[0].x) < .00001f);
    assert(p[1].x > 0.f && p[2].x < 0.f);
    assert(std::fabs(p[1].x - (1.65f * .5f + WORKSPACE_GAP + 1.25f * .5f)) < .00001f);
    assert(std::fabs(p[2].x + (1.65f * .5f + WORKSPACE_GAP + 1.25f * .5f)) < .00001f);
    assert(std::fabs(p[1].z + 2.f) < .00001f && std::fabs(p[2].z + 2.f) < .00001f);

    // AUTO wraps three or more desktops on a cylinder: every pane centre keeps
    // the same focal distance and the arc never exceeds the comfort limit.
    fresh(p);
    workspace_layout(p, 0b111u, 2.2f, WORKSPACE_AUTO);
    const float chord = 1.65f + 1.25f + 1.25f + 2.f * WORKSPACE_GAP;
    const float radius = std::max(2.2f, chord * .5f / std::sin(WORKSPACE_MAX_ARC * .5f));
    assert(radius > 2.2f);
    for (int i = 0; i < 3; ++i) {
        const float r = std::hypot(p[i].x, p[i].z);
        assert(std::fabs(r - radius) < .0001f);
        assert(std::fabs(p[i].width - (i == 0 ? 1.65f : 1.25f)) < .00001f);
    }
    assert(std::fabs(p[0].x) < .00001f);
    assert(p[1].x > 0.f && p[2].x < 0.f);
    const float left_angle = std::atan2(p[2].x, -p[2].z);
    const float right_angle = std::atan2(p[1].x, -p[1].z);
    assert(right_angle - left_angle <= WORKSPACE_MAX_ARC + .0001f);
    assert(std::fabs(right_angle - 1.53f / radius) < .0001f);

    // Two desktops stay flat even in AUTO.
    fresh(p);
    workspace_layout(p, 0b011u, 2.f, WORKSPACE_AUTO);
    assert(std::fabs(p[0].z + 2.f) < .00001f);
    assert(std::fabs(p[1].z + 2.f) < .00001f);
    assert(std::fabs(p[1].yaw) < .00001f);

    // A row without its primary is centred as a block, never pushed to one side.
    fresh(p);
    workspace_layout(p, 0b110u, 2.f, WORKSPACE_FLAT);
    const float pair_chord = 1.25f + 1.25f + WORKSPACE_GAP;
    assert(std::fabs(p[1].x - p[1].width * .5f + pair_chord * .5f) < .00001f);
    assert(std::fabs(p[2].x + p[2].width * .5f - pair_chord * .5f) < .00001f);

    // The upper row (3..5) is centred on the eye line when it is alone and is
    // stacked above the lower row when both exist.
    fresh(p);
    workspace_layout(p, 1u << 3, 2.f, WORKSPACE_FLAT);
    assert(std::fabs(p[3].x) < .00001f);
    assert(std::fabs(p[3].y) < .00001f);
    fresh(p);
    workspace_layout(p, 0b111111u, 2.4f, WORKSPACE_FLAT);
    const float lower_top = p[0].y + 1.65f * 9.f / 16.f * .5f;
    const float upper_bottom = p[3].y - 1.25f * 9.f / 16.f * .5f;
    assert(upper_bottom >= lower_top + WORKSPACE_ROW_GAP - .00001f);
    assert(std::fabs(p[3].x) < .00001f);
    assert(p[4].x > 0.f && p[5].x < 0.f);

    // Recenter is rigid: sizes and pairwise distances survive, the centroid
    // lands in front of the gaze at the configured distance, slightly below eye
    // level.
    fresh(p);
    workspace_layout(p, 0b111u, 2.f, WORKSPACE_FLAT);
    const float before01 = distance_between(p[0], p[1]);
    const float before02 = distance_between(p[0], p[2]);
    const float before12 = distance_between(p[1], p[2]);
    const float width0 = p[0].width, aspect0 = p[0].aspect;
    const WorkspaceHead head{1.5f, 1.7f, -2.f, .8f};
    assert(workspace_recenter(p, 0b111u, head, 2.2f));
    assert(std::fabs(distance_between(p[0], p[1]) - before01) < .0001f);
    assert(std::fabs(distance_between(p[0], p[2]) - before02) < .0001f);
    assert(std::fabs(distance_between(p[1], p[2]) - before12) < .0001f);
    assert(p[0].width == width0 && p[0].aspect == aspect0);
    float cx = (p[0].x + p[1].x + p[2].x) / 3.f;
    float cy = (p[0].y + p[1].y + p[2].y) / 3.f;
    float cz = (p[0].z + p[1].z + p[2].z) / 3.f;
    const float bearing = std::atan2(cx - head.x, cz - head.z);
    const float gaze = std::atan2(-std::sin(head.yaw), -std::cos(head.yaw));
    assert(std::fabs(std::remainder(bearing - gaze, 6.2831853f)) < .0001f);
    assert(std::fabs(std::hypot(cx - head.x, cz - head.z) - 2.2f) < .0001f);
    assert(std::fabs(cy - (head.y - .05f)) < .0001f);

    // Walking five metres away and recentering restores the comfortable
    // distance instead of leaving the panes behind (or in the user's face).
    fresh(p);
    workspace_layout(p, 0b111u, 2.f, WORKSPACE_FLAT);
    const WorkspaceHead walked{0.f, 1.7f, -7.f, 0.f};
    assert(workspace_recenter(p, 0b111u, walked, 2.2f));
    cx = (p[0].x + p[1].x + p[2].x) / 3.f;
    cy = (p[0].y + p[1].y + p[2].y) / 3.f;
    cz = (p[0].z + p[1].z + p[2].z) / 3.f;
    assert(std::fabs(std::hypot(cx - walked.x, cz - walked.z) - 2.2f) < .0001f);
    assert(std::fabs(cy - (walked.y - .05f)) < .0001f);

    // Explicit layout actions anchor the arrangement at the head pose that was
    // current when the user picked the layout/distance (not the session origin).
    const WorkspaceHead anchor{2.f, 1.7f, -3.f, .5f};
    fresh(p);
    workspace_layout(p, 1u << 0, 2.2f, WORKSPACE_FLAT, anchor);
    assert(std::fabs(p[0].x - (2.f - 2.2f * std::sin(.5f))) < .00001f);
    assert(std::fabs(p[0].z - (-3.f - 2.2f * std::cos(.5f))) < .00001f);
    assert(std::fabs(p[0].y - 1.7f) < .00001f);
    assert(std::fabs(p[0].yaw - .5f) < .00001f);
    fresh(p);
    workspace_layout(p, 0b111u, 2.2f, WORKSPACE_FLAT);
    const float flat01 = distance_between(p[0], p[1]);
    const float flat02 = distance_between(p[0], p[2]);
    const float flat12 = distance_between(p[1], p[2]);
    fresh(p);
    workspace_layout(p, 0b111u, 2.2f, WORKSPACE_FLAT, anchor);
    assert(std::fabs(distance_between(p[0], p[1]) - flat01) < .0001f);
    assert(std::fabs(distance_between(p[0], p[2]) - flat02) < .0001f);
    assert(std::fabs(distance_between(p[1], p[2]) - flat12) < .0001f);

    // A very wide, off-centre group must never be pulled through the viewer:
    // recenter keeps at least 40 cm between the nearest pane and the eyes.
    for (int i = 0; i < WORKSPACE_SLOTS; ++i) p[i] = pane(2.214f); // 100-inch 16:9
    workspace_layout(p, 0b111u, 1.6f, WORKSPACE_WRAP);
    const WorkspaceHead beside{3.f, 1.7f, 0.f, 1.5707963f};
    assert(workspace_recenter(p, 0b111u, beside, 1.6f));
    const float gaze_x = -std::sin(beside.yaw), gaze_z = -std::cos(beside.yaw);
    for (int i = 0; i < 3; ++i) {
        const float depth = (p[i].x - beside.x) * gaze_x + (p[i].z - beside.z) * gaze_z;
        assert(depth >= .4f - .0001f);
    }
    // The clamp must actually move the group farther than the requested 1.6 m.
    const float clamped_distance = std::hypot(
        (p[0].x + p[1].x + p[2].x) / 3.f - beside.x,
        (p[0].z + p[1].z + p[2].z) / 3.f - beside.z);
    assert(clamped_distance > 1.6f);

    // A malformed video size or drag must not poison the whole arrangement.
    fresh(p);
    p[1].width = NAN;
    p[2].aspect = NAN;
    workspace_layout(p, 0b111u, 2.2f, WORKSPACE_AUTO);
    for (int i = 0; i < 3; ++i) {
        assert(std::isfinite(p[i].x) && std::isfinite(p[i].y) && std::isfinite(p[i].z));
        assert(std::isfinite(p[i].width) && p[i].width > 0.f);
        assert(std::isfinite(p[i].aspect) && p[i].aspect > 0.f);
    }

    // Degenerate input never moves a pane or reports success.
    assert(!workspace_recenter(p, 0u, head, 2.2f));
    WorkspaceHead inside{0.f, 0.f, -2.f, 0.f};
    fresh(p);
    workspace_layout(p, 1u << 0, 2.f, WORKSPACE_FLAT);
    assert(!workspace_recenter(p, 1u << 0, inside, 2.2f));
    workspace_layout(p, 0u, 2.f, WORKSPACE_AUTO); // no visible pane: no-op
}
