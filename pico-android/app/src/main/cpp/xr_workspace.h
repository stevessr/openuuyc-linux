#pragma once
#include "xr_panel_geometry.h"
#include <algorithm>
#include <cmath>
#include <cstdint>

// Vision Pro-inspired spatial workspace for the six desktop panes.
//
// Slots are laid out in two rows of three: 0..2 are the eye-level row and
// 3..5 sit above it. The workspace only computes placement; every frame still
// billboards each pane towards the viewer (panel_face_user), so the cylinder
// mode below is about *position*, giving every pane the same focal distance
// instead of stretching a flat wall across the field of view.
//
// The first slot of each row is the primary desktop. It stays in front of the
// viewer and the remaining desktops alternate to its right and left, matching a
// physical multi-monitor desk. When the primary is absent the row is centred as
// a block so no pane jumps to an arbitrary side.
enum WorkspaceMode { WORKSPACE_AUTO = 0, WORKSPACE_FLAT = 1, WORKSPACE_WRAP = 2 };
constexpr int WORKSPACE_SLOTS = 6;
constexpr int WORKSPACE_ROWS = 2;
constexpr int WORKSPACE_ROW_SLOTS = 3;
constexpr float WORKSPACE_MIN_DISTANCE = 1.2f;
constexpr float WORKSPACE_MAX_DISTANCE = 4.0f;
constexpr float WORKSPACE_GAP = 0.08f;
constexpr float WORKSPACE_ROW_GAP = 0.12f;
// Rows sit slightly below eye level, the comfortable place to read from.
constexpr float WORKSPACE_EYE_OFFSET = -0.05f;
// Total horizontal arc a wrap workspace may cover. Three 75-inch desktops are
// already very wide; beyond 120 degrees the outer panes leave comfortable
// vision, so the cylinder radius grows instead of pushing panes further aside.
constexpr float WORKSPACE_MAX_ARC = 2.0943951f;

struct WorkspaceHead { float x = 0.f, y = 0.f, z = 0.f; float yaw = 0.f; };

// Layout/recenter transitions: Vision Pro moves windows instead of snapping.
constexpr float WORKSPACE_TRANSITION_SECONDS = 0.26f;

inline float workspace_ease(float t) {
    if (!std::isfinite(t)) return 1.f;
    t = std::clamp(t, 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}

inline float workspace_transition_progress(double now, double start, double duration) {
    if (!(duration > 0.0) || !std::isfinite(now) || !std::isfinite(start)) return 1.f;
    return std::clamp(float((now - start) / duration), 0.f, 1.f);
}

// Interpolate position and width; aspect follows the target immediately because
// the video size is not an animation property.
inline void workspace_interpolate(PanelGeometry &out, const PanelGeometry &from,
                                  const PanelGeometry &to, float t) {
    const float e = workspace_ease(t);
    out.x = from.x + (to.x - from.x) * e;
    out.y = from.y + (to.y - from.y) * e;
    out.z = from.z + (to.z - from.z) * e;
    out.width = from.width + (to.width - from.width) * e;
    out.yaw = from.yaw + (to.yaw - from.yaw) * e;
    out.pitch = from.pitch + (to.pitch - from.pitch) * e;
    out.roll = from.roll + (to.roll - from.roll) * e;
    // Deliberately leave aspect untouched: the XR loop refreshes it from the
    // latest video metadata before animation each frame.
}

inline int workspace_clamp_mode(int mode) {
    return mode < WORKSPACE_AUTO || mode > WORKSPACE_WRAP ? WORKSPACE_AUTO : mode;
}

inline float workspace_clamp_distance(float distance) {
    return std::isfinite(distance)
        ? std::clamp(distance, WORKSPACE_MIN_DISTANCE, WORKSPACE_MAX_DISTANCE)
        : 2.2f;
}

inline int workspace_row_panes(uint32_t visible, int row, int out[WORKSPACE_ROW_SLOTS]) {
    const int base = row * WORKSPACE_ROW_SLOTS;
    int count = 0;
    for (int i = 0; i < WORKSPACE_ROW_SLOTS; ++i)
        if (visible & (1u << (base + i))) out[count++] = base + i;
    return count;
}

// Width of a row including the gaps between panes.
inline float workspace_row_chord(const PanelGeometry *panels, const int *indices, int count) {
    if (count <= 0) return 0.f;
    float chord = 0.f;
    for (int i = 0; i < count; ++i) chord += panels[indices[i]].width;
    return chord + WORKSPACE_GAP * float(count - 1);
}

inline float workspace_row_height(const PanelGeometry *panels, const int *indices, int count) {
    float height = 0.f;
    for (int i = 0; i < count; ++i)
        height = std::max(height, panels[indices[i]].width * panels[indices[i]].aspect);
    return height;
}

inline void workspace_clear_rotation(PanelGeometry &panel) {
    panel.yaw = panel.pitch = panel.roll = 0.f;
}

// A drag/resize or a malformed video size must never poison the whole layout.
inline void workspace_sanitize(PanelGeometry &panel) {
    if (!std::isfinite(panel.width) || panel.width <= 0.f) panel.width = 1.25f;
    if (!std::isfinite(panel.aspect) || panel.aspect <= 0.f) panel.aspect = 9.f / 16.f;
    panel.width = std::min(panel.width, panel_max_width(panel.aspect));
}

inline void workspace_place_row_flat(PanelGeometry *panels, const int *indices, int count,
                                     float distance, float y, bool primary_centered) {
    if (!primary_centered) {
        const float chord = workspace_row_chord(panels, indices, count);
        float cursor = -chord * .5f;
        for (int i = 0; i < count; ++i) {
            PanelGeometry &panel = panels[indices[i]];
            panel.x = cursor + panel.width * .5f;
            panel.y = y;
            panel.z = -distance;
            workspace_clear_rotation(panel);
            cursor += panel.width + WORKSPACE_GAP;
        }
        return;
    }
    PanelGeometry &primary = panels[indices[0]];
    primary.x = 0.f;
    primary.y = y;
    primary.z = -distance;
    workspace_clear_rotation(primary);
    float right = primary.width * .5f + WORKSPACE_GAP;
    float left = right;
    for (int i = 1; i < count; ++i) {
        PanelGeometry &panel = panels[indices[i]];
        if (i % 2 == 1) {
            panel.x = right + panel.width * .5f;
            right += panel.width + WORKSPACE_GAP;
        } else {
            panel.x = -(left + panel.width * .5f);
            left += panel.width + WORKSPACE_GAP;
        }
        panel.y = y;
        panel.z = -distance;
        workspace_clear_rotation(panel);
    }
}

// Place pane centres on a circle around the viewer. Each pane keeps its real
// aspect and width; the radius only grows when the row would exceed the arc.
inline void workspace_place_row_wrap(PanelGeometry *panels, const int *indices, int count,
                                     float radius, float y, bool primary_centered) {
    auto place = [&](PanelGeometry &panel, float arc) {
        const float angle = arc / radius;
        panel.x = radius * std::sin(angle);
        panel.z = -radius * std::cos(angle);
        panel.y = y;
        // Tangent orientation; the per-frame billboard keeps it aimed at the
        // viewer's eye even after they lean or stand up.
        panel.yaw = -angle;
        panel.pitch = panel.roll = 0.f;
    };
    if (!primary_centered) {
        const float chord = workspace_row_chord(panels, indices, count);
        float cursor = -chord * .5f;
        for (int i = 0; i < count; ++i) {
            PanelGeometry &panel = panels[indices[i]];
            place(panel, cursor + panel.width * .5f);
            cursor += panel.width + WORKSPACE_GAP;
        }
        return;
    }
    place(panels[indices[0]], 0.f);
    float right = panels[indices[0]].width * .5f + WORKSPACE_GAP;
    float left = right;
    for (int i = 1; i < count; ++i) {
        PanelGeometry &panel = panels[indices[i]];
        if (i % 2 == 1) {
            place(panel, right + panel.width * .5f);
            right += panel.width + WORKSPACE_GAP;
        } else {
            place(panel, -(left + panel.width * .5f));
            left += panel.width + WORKSPACE_GAP;
        }
    }
}

inline float workspace_wrap_radius(float chord, float distance) {
    if (chord <= 0.f) return distance;
    const float required = chord * .5f / std::sin(WORKSPACE_MAX_ARC * .5f);
    return std::max(distance, required);
}

// Arrange every visible pane. AUTO keeps one or two panes flat and wraps three
// or more; FLAT always keeps a straight row; WRAP always uses the cylinder.
// Panes in the upper row (3..5) are stacked above the eye-level row, never on
// top of it, and the upper row is centred on the eye line when it is alone.
// [anchor] is the head pose that explicit layout actions were performed at.
// Auto re-fits (window open/close, aspect, scale) reuse the same anchor, so a
// recentered workspace does not jump back to the session origin; an explicit
// layout/distance change re-anchors in front of the current gaze.
inline void workspace_layout(PanelGeometry *panels, uint32_t visible, float distance, int mode,
                             WorkspaceHead anchor = {}) {
    visible &= (1u << WORKSPACE_SLOTS) - 1u;
    distance = workspace_clamp_distance(distance);
    mode = workspace_clamp_mode(mode);
    for (int i = 0; i < WORKSPACE_SLOTS; ++i)
        if (visible & (1u << i)) workspace_sanitize(panels[i]);
    int lower[WORKSPACE_ROW_SLOTS], upper[WORKSPACE_ROW_SLOTS];
    const int lower_count = workspace_row_panes(visible, 0, lower);
    const int upper_count = workspace_row_panes(visible, 1, upper);
    if (lower_count + upper_count == 0) return;

    const float lower_height = workspace_row_height(panels, lower, lower_count);
    const float upper_height = workspace_row_height(panels, upper, upper_count);
    float lower_y = WORKSPACE_EYE_OFFSET, upper_y = WORKSPACE_EYE_OFFSET;
    if (lower_count > 0 && upper_count > 0)
        upper_y = lower_y + lower_height * .5f + WORKSPACE_ROW_GAP + upper_height * .5f;

    const bool wrap = mode == WORKSPACE_WRAP ||
        (mode == WORKSPACE_AUTO && lower_count + upper_count >= 3);
    const float radius = wrap
        ? workspace_wrap_radius(std::max(workspace_row_chord(panels, lower, lower_count),
                                         workspace_row_chord(panels, upper, upper_count)), distance)
        : distance;
    if (lower_count > 0) {
        const bool primary = lower[0] % WORKSPACE_ROW_SLOTS == 0;
        if (wrap) workspace_place_row_wrap(panels, lower, lower_count, radius, lower_y, primary);
        else workspace_place_row_flat(panels, lower, lower_count, distance, lower_y, primary);
    }
    if (upper_count > 0) {
        const bool primary = upper[0] % WORKSPACE_ROW_SLOTS == 0;
        if (wrap) workspace_place_row_wrap(panels, upper, upper_count, radius, upper_y, primary);
        else workspace_place_row_flat(panels, upper, upper_count, distance, upper_y, primary);
    }
    if (anchor.x != 0.f || anchor.y != 0.f || anchor.z != 0.f || anchor.yaw != 0.f) {
        const float c = std::cos(anchor.yaw), s = std::sin(anchor.yaw);
        for (int i = 0; i < WORKSPACE_SLOTS; ++i) if (visible & (1u << i)) {
            PanelGeometry &panel = panels[i];
            const float lx = panel.x, lz = panel.z;
            panel.x = anchor.x + lx * c + lz * s;
            panel.z = anchor.z - lx * s + lz * c;
            panel.y += anchor.y;
            panel.yaw += anchor.yaw;
        }
    }
}

// Vision Pro's "recenter": re-anchor the whole arrangement at the viewer's
// current head pose. The layout frame (anchor) moves to the head, so the main
// screen ends up in front of the gaze at the layout's distance, sizes and
// pairwise distances stay intact, and a later workspace_layout(..., head)
// reproduces exactly the same positions for any pane count or layout.
inline bool workspace_recenter(PanelGeometry *panels, uint32_t visible, WorkspaceHead head,
                               WorkspaceHead anchor = {}) {
    visible &= (1u << WORKSPACE_SLOTS) - 1u;
    const float delta = std::remainder(head.yaw - anchor.yaw, 6.2831853f);
    const float c = std::cos(delta), s = std::sin(delta);
    int moved = 0;
    for (int i = 0; i < WORKSPACE_SLOTS; ++i) if (visible & (1u << i)) {
        PanelGeometry &panel = panels[i];
        if (!std::isfinite(panel.x) || !std::isfinite(panel.y) || !std::isfinite(panel.z)) continue;
        const float x = panel.x - anchor.x, z = panel.z - anchor.z;
        panel.x = head.x + x * c + z * s;
        panel.z = head.z - x * s + z * c;
        panel.y = head.y + (panel.y - anchor.y);
        ++moved;
    }
    if (moved == 0) return false;

    // Never pull a pane through the viewer, even for a very wide or manually
    // placed group: keep 40 cm of clearance and move the whole group farther.
    const float gaze_x = -std::sin(head.yaw), gaze_z = -std::cos(head.yaw);
    float nearest_depth = 1e30f;
    for (int i = 0; i < WORKSPACE_SLOTS; ++i) if (visible & (1u << i)) {
        const PanelGeometry &panel = panels[i];
        if (!std::isfinite(panel.x) || !std::isfinite(panel.z)) continue;
        nearest_depth = std::min(nearest_depth,
            (panel.x - head.x) * gaze_x + (panel.z - head.z) * gaze_z);
    }
    if (nearest_depth < 1e29f && nearest_depth < .4f) {
        const float extra = .4f - nearest_depth;
        for (int i = 0; i < WORKSPACE_SLOTS; ++i) if (visible & (1u << i)) {
            PanelGeometry &panel = panels[i];
            if (!std::isfinite(panel.x) || !std::isfinite(panel.z)) continue;
            panel.x += gaze_x * extra;
            panel.z += gaze_z * extra;
        }
    }
    for (int i = 0; i < WORKSPACE_SLOTS; ++i) if (visible & (1u << i))
        workspace_clear_rotation(panels[i]);
    return true;
}
