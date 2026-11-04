//! Project fresh OpenXR gaze rays into the compositor's SBS H.264 macroblock grid.
//! No gaze is a uniform map; malformed or out-of-view samples never invent a center.

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Gaze {
    /// Position xyz and orientation xyzw in the headset's LOCAL space.
    pub(crate) pose: [f32; 7],
}

const CENTER_QP_OFFSET: i8 = -4;
const PERIPHERY_QP_OFFSET: i8 = 8;
const CENTER_RADIUS: f32 = 0.08;
const PERIPHERY_RADIUS: f32 = 0.38;

/// Fill an existing macroblock map. Positive offsets spend fewer bits; negative
/// offsets improve detail. Returns true only when at least one eye projected.
pub(crate) fn fill_qp_offsets(
    width: u16,
    height: u16,
    views: &[f32; 22],
    gaze: Option<Gaze>,
    offsets: &mut [i8],
) -> bool {
    let width = usize::from(width);
    let height = usize::from(height);
    let columns = width.div_ceil(16);
    let rows = height.div_ceil(16);
    offsets.fill(0);
    if width < 2
        || height == 0
        || width % 2 != 0
        || offsets.len() != columns * rows
        || views.iter().any(|value| !value.is_finite())
    {
        return false;
    }
    let Some(gaze) = gaze else { return false };
    if !valid_pose(&gaze.pose) {
        return false;
    }

    let eye_width = width / 2;
    let mut projected = false;
    for eye in 0..2 {
        let view = &views[eye * 11..eye * 11 + 11];
        let Some(center) = project_gaze(view, gaze.pose) else {
            continue;
        };
        projected = true;
        let x_start = eye * eye_width;
        let x_end = x_start + eye_width;
        let left = view[7].tan();
        let right = view[8].tan();
        let up = view[9].tan();
        let down = view[10].tan();
        let tan_width = right - left;
        let tan_height = up - down;
        if !tan_width.is_finite() || !tan_height.is_finite() || tan_width <= 0. || tan_height <= 0.
        {
            projected = false;
            continue;
        }
        for row in 0..rows {
            let y_pixel = row * 16 + 8;
            if y_pixel >= height {
                continue;
            }
            let y = (y_pixel as f32 / height as f32 - center[1]) * tan_height;
            for column in 0..columns {
                let x_pixel = column * 16 + 8;
                if !(x_start..x_end).contains(&x_pixel) {
                    continue;
                }
                let x = (x_pixel as f32 - x_start as f32) / eye_width as f32;
                let dx = (x - center[0]) * tan_width;
                let radius = dx.hypot(y);
                let offset = if radius <= CENTER_RADIUS {
                    CENTER_QP_OFFSET
                } else if radius >= PERIPHERY_RADIUS {
                    PERIPHERY_QP_OFFSET
                } else {
                    let t = (radius - CENTER_RADIUS) / (PERIPHERY_RADIUS - CENTER_RADIUS);
                    (f32::from(CENTER_QP_OFFSET)
                        + t * f32::from(PERIPHERY_QP_OFFSET - CENTER_QP_OFFSET))
                    .round() as i8
                };
                offsets[row * columns + column] = offset;
            }
        }
    }
    projected
}

fn valid_pose(pose: &[f32; 7]) -> bool {
    if pose.iter().any(|v| !v.is_finite()) || pose[..3].iter().any(|v| v.abs() > 1000.) {
        return false;
    }
    (0.98..=1.02).contains(&pose[3..].iter().map(|v| v * v).sum::<f32>())
}

fn project_gaze(view: &[f32], gaze: [f32; 7]) -> Option<[f32; 2]> {
    let eye_position = [view[0], view[1], view[2]];
    let eye_orientation = [view[3], view[4], view[5], view[6]];
    let inverse = [
        -eye_orientation[0],
        -eye_orientation[1],
        -eye_orientation[2],
        eye_orientation[3],
    ];
    let relative_origin = rotate(
        inverse,
        [
            gaze[0] - eye_position[0],
            gaze[1] - eye_position[1],
            gaze[2] - eye_position[2],
        ],
    );
    let direction = rotate([gaze[3], gaze[4], gaze[5], gaze[6]], [0., 0., -1.]);
    let direction = rotate(inverse, direction);
    if direction[2] >= -0.1 {
        return None;
    }
    // Intersect the real gaze ray with the eye camera's z=-1m projection plane.
    let distance = (-1. - relative_origin[2]) / direction[2];
    if !distance.is_finite() || distance <= 0. {
        return None;
    }
    let point = [
        relative_origin[0] + distance * direction[0],
        relative_origin[1] + distance * direction[1],
        relative_origin[2] + distance * direction[2],
    ];
    if point[2] >= -0.05 {
        return None;
    }
    let tan_x = point[0] / -point[2];
    let tan_y = point[1] / -point[2];
    let x = (tan_x - view[7].tan()) / (view[8].tan() - view[7].tan());
    let y = (view[9].tan() - tan_y) / (view[9].tan() - view[10].tan());
    (x.is_finite() && y.is_finite() && (0.0..=1.0).contains(&x) && (0.0..=1.0).contains(&y))
        .then_some([x, y])
}

fn rotate(q: [f32; 4], v: [f32; 3]) -> [f32; 3] {
    let uv = [
        q[1] * v[2] - q[2] * v[1],
        q[2] * v[0] - q[0] * v[2],
        q[0] * v[1] - q[1] * v[0],
    ];
    let uuv = [
        q[1] * uv[2] - q[2] * uv[1],
        q[2] * uv[0] - q[0] * uv[2],
        q[0] * uv[1] - q[1] * uv[0],
    ];
    [
        v[0] + 2. * (q[3] * uv[0] + uuv[0]),
        v[1] + 2. * (q[3] * uv[1] + uuv[1]),
        v[2] + 2. * (q[3] * uv[2] + uuv[2]),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn views() -> [f32; 22] {
        let mut views = [0.; 22];
        for (eye, view) in views.chunks_exact_mut(11).enumerate() {
            view[0] = if eye == 0 { -0.032 } else { 0.032 };
            view[6] = 1.;
            view[7] = -0.8;
            view[8] = 0.8;
            view[9] = 0.8;
            view[10] = -0.8;
        }
        views
    }

    #[test]
    fn gaze_projects_to_both_real_eye_centers_and_reduces_peripheral_bits() {
        let views = views();
        let gaze = Gaze {
            pose: [0., 0., -0.03, 0., 0., 0., 1.],
        };
        let mut map = [0; 32 * 16];
        assert!(fill_qp_offsets(512, 256, &views, Some(gaze), &mut map));
        let center_left = map[8 * 32 + 8];
        let center_right = map[8 * 32 + 23];
        let periphery_left = map[8 * 32];
        let periphery_right = map[8 * 32 + 31];
        assert!(center_left < 0 && center_right < 0);
        assert!(periphery_left > 0 && periphery_right > 0);
        assert!(center_left < periphery_left && center_right < periphery_right);
    }

    #[test]
    fn no_gaze_invalid_pose_and_out_of_fov_gaze_leave_uniform_map() {
        let views = views();
        let mut map = [7; 16 * 4];
        assert!(!fill_qp_offsets(256, 64, &views, None, &mut map));
        assert!(map.iter().all(|v| *v == 0));
        let invalid = Gaze {
            pose: [0., 0., 0., 0., 0., 0., 0.],
        };
        assert!(!fill_qp_offsets(256, 64, &views, Some(invalid), &mut map));
        // A 1.1 rad yaw lies outside the fixture's +/-0.8 rad horizontal FOV.
        let yaw = 0.55f32;
        let outside = Gaze {
            pose: [0., 0., -0.03, 0., yaw.sin(), 0., yaw.cos()],
        };
        assert!(!fill_qp_offsets(256, 64, &views, Some(outside), &mut map));
        assert!(map.iter().all(|v| *v == 0));
    }
}
