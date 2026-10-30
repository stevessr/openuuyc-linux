//! Register an external compositor's coordinate space into the headset's own
//! LOCAL space and carry its rendering views across. SteamVR's compositor
//! reports absolute-space poses; the headset can only project views expressed
//! in its LOCAL space, so an external frame is never encoded before this
//! alignment exists. The registration is one rigid transform from the two
//! heads' reported poses: both spaces are room-anchored, so a single fresh
//! sample registers them (reattach re-registers).
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Align {
    /// Rotation of the external space into LOCAL (xyzw).
    rotation: [f32; 4],
    /// Translation of the external space into LOCAL.
    position: [f32; 3],
}
fn sane(position: &[f32; 3]) -> bool {
    position.iter().all(|v| v.is_finite() && v.abs() <= 1000.)
}
fn normalize(rotation: &[f32; 4]) -> [f32; 4] {
    let norm: f32 = rotation.iter().map(|v| v * v).sum();
    let inverse = 1.0 / norm.sqrt();
    [
        rotation[0] * inverse,
        rotation[1] * inverse,
        rotation[2] * inverse,
        rotation[3] * inverse,
    ]
}
/// Normalize a real orientation; strips float noise, rejects what is not one.
fn unit(rotation: &[f32; 4]) -> Option<[f32; 4]> {
    let norm: f32 = rotation.iter().map(|v| v * v).sum();
    (rotation.iter().all(|v| v.is_finite()) && (0.98..=1.02).contains(&norm))
        .then(|| normalize(rotation))
}
/// Hamilton product, xyzw.
fn multiply(a: &[f32; 4], b: &[f32; 4]) -> [f32; 4] {
    [
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    ]
}
fn rotate(rotation: &[f32; 4], point: &[f32; 3]) -> [f32; 3] {
    let half = [
        rotation[1] * point[2] - rotation[2] * point[1],
        rotation[2] * point[0] - rotation[0] * point[2],
        rotation[0] * point[1] - rotation[1] * point[0],
    ];
    [
        point[0] + 2. * (rotation[3] * half[0] + rotation[1] * half[2] - rotation[2] * half[1]),
        point[1] + 2. * (rotation[3] * half[1] + rotation[2] * half[0] - rotation[0] * half[2]),
        point[2] + 2. * (rotation[3] * half[2] + rotation[0] * half[1] - rotation[1] * half[0]),
    ]
}
impl Align {
    /// Transform that takes the external space into LOCAL, from the headset
    /// head pose (LOCAL) and the external rendering head pose, taken at
    /// (approximately) the same moment. `None` on a pose that cannot register.
    pub(crate) fn register(local: &[f32; 7], external: &[f32; 7]) -> Option<Self> {
        let local_position: [f32; 3] = local[..3].try_into().ok()?;
        let external_position: [f32; 3] = external[..3].try_into().ok()?;
        if !sane(&local_position) || !sane(&external_position) {
            return None;
        }
        let local_rotation = unit(local[3..7].try_into().ok()?)?;
        let external_rotation = unit(external[3..7].try_into().ok()?)?;
        let inverse = [
            -external_rotation[0],
            -external_rotation[1],
            -external_rotation[2],
            external_rotation[3],
        ];
        let rotation = normalize(&multiply(&local_rotation, &inverse));
        let rotated = rotate(&rotation, &external_position);
        let position = [
            local_position[0] - rotated[0],
            local_position[1] - rotated[1],
            local_position[2] - rotated[2],
        ];
        sane(&position).then_some(Self { rotation, position })
    }
    /// Carry each eye pose of an external frame into LOCAL; FOV angles belong
    /// to the rendering view and stay unchanged.
    pub(crate) fn apply(&self, views: &mut [f32; 22]) {
        for eye in views.chunks_exact_mut(11) {
            let position = rotate(&self.rotation, &[eye[0], eye[1], eye[2]]);
            for (axis, value) in position.into_iter().enumerate() {
                eye[axis] = value + self.position[axis];
            }
            let orientation = normalize(&multiply(&self.rotation, &eye[3..7].try_into().unwrap()));
            eye[3..7].copy_from_slice(&orientation);
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn view(position: [f32; 3]) -> [f32; 22] {
        let mut views = [0f32; 22];
        for eye in 0..2 {
            views[eye * 11..eye * 11 + 3].copy_from_slice(&position);
            views[eye * 11 + 6] = 1.;
            views[eye * 11 + 7..eye * 11 + 11].copy_from_slice(&[-1., 1., 1., -1.]);
        }
        views
    }
    #[test]
    fn identical_spaces_are_the_identity() {
        let head = [1., 2., 3., 0., 0., 0., 1.];
        let align = Align::register(&head, &head).unwrap();
        assert_eq!(align.position, [0., 0., 0.]);
        assert_eq!(align.rotation, [0., 0., 0., 1.]);
        let mut views = view([0.5, 1.5, 2.5]);
        let original = views;
        align.apply(&mut views);
        assert_eq!(views, original);
    }
    #[test]
    fn a_different_head_position_carries_the_whole_view_over() {
        // The external head sits 4 m away from the headset's own head; every
        // external pose moves by the same rigid offset.
        let local = [5., 0., 0., 0., 0., 0., 1.];
        let external = [1., 0., 0., 0., 0., 0., 1.];
        let align = Align::register(&local, &external).unwrap();
        let mut views = view([1., 0., 0.]);
        align.apply(&mut views);
        assert_eq!(&views[..3], &[5., 0., 0.]);
        assert_eq!(&views[11..14], &[5., 0., 0.]);
    }
    #[test]
    fn a_yaw_difference_rotates_positions_and_orientations() {
        // Headset head yawed +90 degrees from the external head at the same
        // place: the external +x view must land on LOCAL -z.
        let yaw = std::f32::consts::FRAC_1_SQRT_2;
        let local = [0., 0., 0., 0., yaw, 0., yaw];
        let external = [0., 0., 0., 0., 0., 0., 1.];
        let align = Align::register(&local, &external).unwrap();
        let mut views = view([1., 0., 0.]);
        align.apply(&mut views);
        assert!(views[0].abs() < 1e-5 && views[1].abs() < 1e-5);
        assert!((views[2] + 1.).abs() < 1e-5);
        assert!((views[4] - yaw).abs() < 1e-5 && (views[6] - yaw).abs() < 1e-5);
        let norm: f32 = views[3..7].iter().map(|v| v * v).sum();
        assert!((0.98..=1.02).contains(&norm));
    }
    #[test]
    fn unusable_poses_never_register() {
        let identity = [0., 0., 0., 0., 0., 0., 1.];
        assert!(Align::register(&identity, &[0., 0., 0., 0., 0., 0., 0.5]).is_none());
        assert!(Align::register(&[f32::NAN, 0., 0., 0., 0., 0., 1.], &identity).is_none());
        assert!(Align::register(&identity, &[2000., 0., 0., 0., 0., 0., 1.]).is_none());
    }
}
