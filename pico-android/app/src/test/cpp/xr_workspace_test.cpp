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

    // Layout transitions ease instead of snapping.
    assert(workspace_ease(0.f) == 0.f && workspace_ease(1.f) == 1.f);
    assert(std::fabs(workspace_ease(.5f) - .5f) < .00001f);
    assert(workspace_ease(-1.f) == 0.f && workspace_ease(2.f) == 1.f);
    assert(workspace_ease(NAN) == 1.f);
    // Smoothstep, not linear: this catches a `return t` mutation.
    assert(workspace_ease(.25f) < .24f);
    assert(workspace_ease(.75f) > .76f);
    float previous = -1.f;
    for (int i = 0; i <= 20; ++i) {
        const float e = workspace_ease(i / 20.f);
        assert(e >= previous - .00001f && e >= 0.f && e <= 1.f);
        previous = e;
    }
    assert(workspace_transition_progress(1.0, 1.0, .26) == 0.f);
    assert(std::fabs(workspace_transition_progress(1.13, 1.0, .26) - .5f) < .0001f);
    assert(workspace_transition_progress(2.0, 1.0, .26) == 1.f);
    assert(workspace_transition_progress(1.0, 1.0, 0.0) == 1.f);
    PanelGeometry from = pane(1.65f), to = pane(1.25f);
    from.x = -1.f; from.y = .2f; from.z = -2.5f;
    to.x = 1.f; to.y = -.2f; to.z = -3.f;
    PanelGeometry mid{};
    mid.aspect=1.25f; // latest video metadata, refreshed every XR frame
    workspace_interpolate(mid, from, to, .5f);
    assert(std::fabs(mid.x) < .00001f);
    assert(std::fabs(mid.y) < .00001f);
    assert(std::fabs(mid.z + 2.75f) < .00001f);
    assert(std::fabs(mid.width - 1.45f) < .00001f);
    assert(std::fabs(mid.aspect - 1.25f) < .00001f);
    workspace_interpolate(mid, from, to, 0.f);
    assert(mid.x == from.x && mid.width == from.width);
    workspace_interpolate(mid, from, to, 1.f);
    assert(mid.x == to.x && mid.width == to.width);

    PanelGeometry p[WORKSPACE_SLOTS];

    // One desktop: centred, upright, at the chosen distance.
    fresh(p);
    workspace_layout(p, 1u << 0, 2.2f, WORKSPACE_AUTO);
    assert(std::fabs(p[0].x) < .00001f);
    assert(std::fabs(p[0].z + 2.2f) < .00001f);
    assert(std::fabs(p[0].y - WORKSPACE_EYE_OFFSET) < .00001f);
    assert(std::fabs(p[0].yaw) < .00001f);

    // Two desktops: the primary stays centred and the second sits to its right,
    // which is the layout an office user expects from a dual-monitor desk.
    fresh(p);
    workspace_layout(p, 0b011u, 2.f, WORKSPACE_FLAT);
    assert(std::fabs(p[0].x) < .00001f);
    assert(p[1].x > p[0].x);
    assert(std::fabs(p[1].x - (1.65f * .5f + WORKSPACE_GAP + 1.25f * .5f)) < .00001f);
    assert(std::fabs(p[1].z + 2.f) < .00001f);
    assert(std::fabs(p[1].y - WORKSPACE_EYE_OFFSET) < .00001f);

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
    assert(std::fabs(p[3].y - WORKSPACE_EYE_OFFSET) < .00001f);
    fresh(p);
    workspace_layout(p, 0b111111u, 2.4f, WORKSPACE_FLAT);
    const float lower_top = p[0].y + 1.65f * 9.f / 16.f * .5f;
    const float upper_bottom = p[3].y - 1.25f * 9.f / 16.f * .5f;
    assert(upper_bottom >= lower_top + WORKSPACE_ROW_GAP - .00001f);
    assert(std::fabs(p[3].x) < .00001f);
    assert(p[4].x > 0.f && p[5].x < 0.f);

    // Recenter re-anchors the layout at the current head pose: sizes and
    // pairwise distances survive, the main screen lands in front of the gaze at
    // the layout distance, and a later layout with the new anchor reproduces it.
    fresh(p);
    workspace_layout(p, 0b111u, 2.2f, WORKSPACE_FLAT);
    const float before01 = distance_between(p[0], p[1]);
    const float before02 = distance_between(p[0], p[2]);
    const float before12 = distance_between(p[1], p[2]);
    const float width0 = p[0].width, aspect0 = p[0].aspect;
    const WorkspaceHead head{1.5f, 1.7f, -2.f, .8f};
    assert(workspace_recenter(p, 0b111u, head));
    assert(std::fabs(distance_between(p[0], p[1]) - before01) < .0001f);
    assert(std::fabs(distance_between(p[0], p[2]) - before02) < .0001f);
    assert(std::fabs(distance_between(p[1], p[2]) - before12) < .0001f);
    assert(p[0].width == width0 && p[0].aspect == aspect0);
    const float gaze_x = -std::sin(head.yaw), gaze_z = -std::cos(head.yaw);
    assert(std::fabs(p[0].x - (head.x + gaze_x * 2.2f)) < .0001f);
    assert(std::fabs(p[0].z - (head.z + gaze_z * 2.2f)) < .0001f);
    assert(std::fabs(p[0].y - (head.y + WORKSPACE_EYE_OFFSET)) < .0001f);

    // Walking five metres away and recentering brings the main screen back to
    // the layout distance instead of leaving it behind (or in the user's face).
    fresh(p);
    workspace_layout(p, 0b111u, 2.2f, WORKSPACE_FLAT);
    const WorkspaceHead walked{0.f, 1.7f, -7.f, 0.f};
    assert(workspace_recenter(p, 0b111u, walked));
    assert(std::fabs(p[0].x - walked.x) < .0001f);
    assert(std::fabs(p[0].z - (walked.z - 2.2f)) < .0001f);
    assert(std::fabs(p[0].y - (walked.y + WORKSPACE_EYE_OFFSET)) < .0001f);

    // Idempotence for two panes, six panes and a wide wrap: a layout with the
    // new anchor after recenter must not move any pane. This is the P1 bug the
    // 3-pane flat case alone did not expose.
    auto assert_recentered = [&](uint32_t mask, float distance, int mode) {
        fresh(p);
        workspace_layout(p, mask, distance, mode);
        const WorkspaceHead target{2.f, 1.7f, -3.f, .8f};
        assert(workspace_recenter(p, mask, target));
        const PanelGeometry recentered[6] = {p[0], p[1], p[2], p[3], p[4], p[5]};
        workspace_layout(p, mask, distance, mode, target);
        for (int i = 0; i < 6; ++i) if (mask & (1u << i)) {
            assert(std::fabs(p[i].x - recentered[i].x) < .0001f);
            assert(std::fabs(p[i].y - recentered[i].y) < .0001f);
            assert(std::fabs(p[i].z - recentered[i].z) < .0001f);
        }
    };
    assert_recentered(1u << 0, 2.2f, WORKSPACE_FLAT);
    assert_recentered(0b011u, 2.2f, WORKSPACE_FLAT);
    assert_recentered(0b111u, 2.2f, WORKSPACE_WRAP);
    assert_recentered(0b1111u, 2.4f, WORKSPACE_FLAT);
    assert_recentered(0b11111u, 2.4f, WORKSPACE_FLAT);
    assert_recentered(0b111111u, 2.4f, WORKSPACE_FLAT);
    fresh(p);
    p[0].width = 1.65f; p[1].width = 1.25f; p[2].width = 2.214f;
    workspace_layout(p, 0b111u, 1.6f, WORKSPACE_WRAP);
    const WorkspaceHead wide{2.f, 1.7f, -3.f, .8f};
    assert(workspace_recenter(p, 0b111u, wide));
    const PanelGeometry wide_recentered[3] = {p[0], p[1], p[2]};
    workspace_layout(p, 0b111u, 1.6f, WORKSPACE_WRAP, wide);
    for (int i = 0; i < 3; ++i) {
        assert(std::fabs(p[i].x - wide_recentered[i].x) < .0001f);
        assert(std::fabs(p[i].y - wide_recentered[i].y) < .0001f);
        assert(std::fabs(p[i].z - wide_recentered[i].z) < .0001f);
    }

    // A pathological arrangement (a pane behind the viewer) must never be
    // pulled through the eyes: recenter keeps at least 40 cm clearance and
    // moves the whole group farther instead, without breaking rigidity.
    fresh(p);
    p[0] = pane(1.65f);
    p[0].z = -1.f;
    p[1] = pane(1.25f);
    p[1].z = 5.f;
    const WorkspaceHead beside{0.f, 1.7f, 0.f, 0.f};
    assert(workspace_recenter(p, 0b011u, beside));
    const float beside_gaze_x = -std::sin(beside.yaw), beside_gaze_z = -std::cos(beside.yaw);
    for (int i = 0; i < 2; ++i) {
        const float depth = (p[i].x - beside.x) * beside_gaze_x + (p[i].z - beside.z) * beside_gaze_z;
        assert(depth >= .4f - .0001f);
    }
    assert(std::fabs(distance_between(p[0], p[1]) - 6.f) < .0001f);

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

    // Degenerate input never reports success.
    assert(!workspace_recenter(p, 0u, head));
    fresh(p);
    p[0].x = NAN; p[1].y = NAN; p[2].z = NAN;
    assert(!workspace_recenter(p, 0b111u, head));
    workspace_layout(p, 0u, 2.f, WORKSPACE_AUTO); // no visible pane: no-op
}
