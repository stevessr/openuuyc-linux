//! Focused window geometry and text-input state from the compositor's own IPC.
//!
//! Wayland deliberately gives a third party no way to observe another client's
//! text focus: `zwp_text_input_v3` only ever reports the requesting client's own
//! surfaces, `zwp_input_method_v2` is exclusive with the user's input method, and
//! `ext_foreign_toplevel_list_v1` carries no focus state. What compositors do
//! publish natively is which window has focus and where it sits, so this module
//! reads that over each compositor's own channel:
//!
//! - niri: `NIRI_SOCKET`, the same IPC `window_control` uses;
//! - Hyprland: `HYPRLAND_INSTANCE_SIGNATURE`, `hyprctl -j activewindow`;
//! - KWin (Plasma): D-Bus `org.kde.kwin.VirtualKeyboard`, whose `active` and
//!   `activeClientSupportsTextInput` properties answer the text-focus question
//!   the way Plasma's own virtual keyboard asks it.
//!
//! Nothing here is required: a session without one of these channels simply
//! reports no hint and the accessibility bus keeps answering alone.
use std::{
    sync::{Mutex, MutexGuard, Once},
    thread,
    time::{Duration, Instant},
};

/// A rectangle in global logical pixels: left, top, width, height.
pub(crate) type Rect = (i32, i32, u32, u32);

/// How often the refresher probes the compositor.
const REFRESH: Duration = Duration::from_millis(250);

/// What the compositor says about focus right now.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Focus {
    /// Global rectangle of the focused window, when the compositor reports one.
    pub(crate) rect: Option<Rect>,
    /// `Some(true)` when the compositor itself says a text input is focused.
    pub(crate) text_input: Option<bool>,
}

static STARTED: Once = Once::new();
static SNAPSHOT: Mutex<Option<Focus>> = Mutex::new(None);

fn lock<T>(mutex: &'static Mutex<T>) -> MutexGuard<'static, T> {
    mutex.lock().unwrap_or_else(|error| error.into_inner())
}

/// The compositor's current focus.
///
/// The probe is compositor IPC and the cursor publisher asks at 30 Hz, so it
/// runs on a thread of its own and this only reads the latest snapshot: a slow
/// or hung compositor can never stall the cursor loop.
pub(crate) fn focused() -> Option<Focus> {
    STARTED.call_once(|| {
        let spawned = thread::Builder::new()
            .name("openuuyc-compositor-focus".to_string())
            .spawn(refresh);
        if let Err(error) = spawned {
            tracing::debug!(%error, "compositor focus thread unavailable");
        }
    });
    lock(&SNAPSHOT).clone()
}

fn refresh() {
    loop {
        *lock(&SNAPSHOT) = probe();
        thread::sleep(REFRESH);
    }
}

/// Whether two rectangles share any area. Used to reject a text rectangle that
/// belongs to a background app instead of the focused window.
pub(crate) fn overlaps((left, top, width, height): Rect, (x, y, w, h): Rect) -> bool {
    let (left, top, width, height) = (
        i64::from(left),
        i64::from(top),
        i64::from(width),
        i64::from(height),
    );
    let (x, y, w, h) = (i64::from(x), i64::from(y), i64::from(w), i64::from(h));
    x < left + width && x + w > left && y < top + height && y + h > top
}

fn probe() -> Option<Focus> {
    let mut focus = Focus {
        rect: None,
        text_input: None,
    };
    if let Some((active, supports)) = kwin_virtual_keyboard() {
        focus.text_input = Some(active && supports);
    }
    focus.rect = niri_focused_rect().or_else(hyprland_focused_rect);
    (focus.rect.is_some() || focus.text_input.is_some()).then_some(focus)
}

/// KWin's own virtual-keyboard interface: `active` is true while a client has
/// an active text input, and `activeClientSupportsTextInput` tells whether that
/// client speaks the text-input protocol at all.
fn kwin_virtual_keyboard() -> Option<(bool, bool)> {
    let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default();
    if !desktop.to_ascii_lowercase().contains("kde") {
        return None;
    }
    let connection = zbus::blocking::Connection::session().ok()?;
    let proxy = zbus::blocking::Proxy::new(
        &connection,
        "org.kde.KWin",
        "/VirtualKeyboard",
        "org.kde.kwin.VirtualKeyboard",
    )
    .ok()?;
    let active: bool = proxy.get_property("active").ok()?;
    let supports: bool = proxy.get_property("activeClientSupportsTextInput").ok()?;
    Some((active, supports))
}

/// The focused window's global rectangle from niri's IPC. niri reports tile
/// positions in the focused output's workspace view, so the output's logical
/// origin is added back in. All three requests share one connection.
fn niri_focused_rect() -> Option<Rect> {
    let replies = super::window_control::ipc_many(&[
        serde_json::json!("FocusedWindow"),
        serde_json::json!("Workspaces"),
        serde_json::json!("Outputs"),
    ])
    .ok()?;
    // niri wraps every response in its own key: {"Ok": {"FocusedWindow": ..}}.
    let window = replies.first()?.get("FocusedWindow")?;
    if window.is_null() {
        return None;
    }
    let rect = niri_rect(window)?;
    let origin = window
        .get("workspace_id")
        .and_then(serde_json::Value::as_u64)
        .and_then(|workspace| niri_output_origin(workspace, replies.get(1)?, replies.get(2)?));
    Some(match origin {
        Some((x, y)) => (
            rect.0.saturating_add(x),
            rect.1.saturating_add(y),
            rect.2,
            rect.3,
        ),
        None => rect,
    })
}

/// The logical origin of the output showing a workspace, when niri names it.
fn niri_output_origin(
    workspace_id: u64,
    workspaces: &serde_json::Value,
    outputs: &serde_json::Value,
) -> Option<(i32, i32)> {
    let output = workspaces
        .get("Workspaces")?
        .as_array()?
        .iter()
        .find(|workspace| {
            workspace.get("id").and_then(serde_json::Value::as_u64) == Some(workspace_id)
        })?
        .get("output")?
        .as_str()?;
    let logical = outputs.get("Outputs")?.get(output)?.get("logical")?;
    Some((
        i32::try_from(logical.get("x")?.as_i64()?).ok()?,
        i32::try_from(logical.get("y")?.as_i64()?).ok()?,
    ))
}

/// The workspace-view rectangle of one niri window: the tile position plus the
/// window's offset inside it, with the window's own size.
fn niri_rect(window: &serde_json::Value) -> Option<Rect> {
    let layout = window.get("layout")?;
    let tile = layout.get("tile_pos_in_workspace_view")?.as_array()?;
    let offset = layout.get("window_offset_in_tile")?.as_array()?;
    let size = layout.get("window_size")?.as_array()?;
    let x = tile.first()?.as_f64()? + offset.first()?.as_f64()?;
    let y = tile.get(1)?.as_f64()? + offset.get(1)?.as_f64()?;
    let width = u32::try_from(size.first()?.as_u64()?).ok()?;
    let height = u32::try_from(size.get(1)?.as_u64()?).ok()?;
    if width == 0 || height == 0 {
        return None;
    }
    Some((x.round() as i32, y.round() as i32, width, height))
}

/// The focused window's global rectangle from Hyprland's `activewindow`.
fn hyprland_focused_rect() -> Option<Rect> {
    std::env::var_os("HYPRLAND_INSTANCE_SIGNATURE")?;
    let output = std::process::Command::new("hyprctl")
        .args(["-j", "activewindow"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    hyprland_rect(&serde_json::from_slice(&output.stdout).ok()?)
}

fn hyprland_rect(window: &serde_json::Value) -> Option<Rect> {
    let at = window.get("at")?.as_array()?;
    let size = window.get("size")?.as_array()?;
    let x = i32::try_from(at.first()?.as_i64()?).ok()?;
    let y = i32::try_from(at.get(1)?.as_i64()?).ok()?;
    let width = u32::try_from(size.first()?.as_i64()?).ok()?;
    let height = u32::try_from(size.get(1)?.as_i64()?).ok()?;
    if width == 0 || height == 0 {
        return None;
    }
    Some((x, y, width, height))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn niri_window(tile: [f64; 2], offset: [f64; 2], size: [u64; 2]) -> serde_json::Value {
        serde_json::json!({
            "id": 7,
            "workspace_id": 3,
            "layout": {
                "tile_pos_in_workspace_view": tile,
                "window_offset_in_tile": offset,
                "window_size": size,
            }
        })
    }

    #[test]
    fn niri_rect_adds_the_offset_to_the_tile_position() {
        let window = niri_window([170.6, 44.0], [0.0, 0.0], [1365, 1019]);
        assert_eq!(niri_rect(&window), Some((171, 44, 1365, 1019)));
        // A floating or tiled window can sit inside its tile.
        let window = niri_window([100.0, 200.0], [12.4, 8.6], [800, 600]);
        assert_eq!(niri_rect(&window), Some((112, 209, 800, 600)));
    }

    #[test]
    fn niri_rect_rejects_a_window_without_a_size() {
        assert_eq!(
            niri_rect(&niri_window([0.0, 0.0], [0.0, 0.0], [0, 0])),
            None
        );
        assert_eq!(niri_rect(&serde_json::json!({"id": 1})), None);
    }

    #[test]
    fn hyprland_rect_reads_at_and_size() {
        let window = serde_json::json!({
            "class": "firefox",
            "at": [1920, 120],
            "size": [1280, 800],
        });
        assert_eq!(hyprland_rect(&window), Some((1920, 120, 1280, 800)));
        // No focused window: Hyprland answers with an empty object.
        assert_eq!(hyprland_rect(&serde_json::json!({})), None);
    }

    #[test]
    fn overlap_is_half_open_on_both_rectangles() {
        let window = (100, 100, 200, 200);
        assert!(overlaps(window, (150, 150, 10, 10)));
        assert!(overlaps(window, (100, 100, 200, 200)));
        assert!(!overlaps(window, (300, 100, 50, 50)));
        assert!(!overlaps(window, (0, 0, 100, 100)));
        assert!(!overlaps(window, (0, 0, 10, 10)));
    }

    /// Live check against the running compositor; not part of CI.
    #[test]
    #[ignore = "needs a running niri, Hyprland or KWin session"]
    fn the_compositor_answers_about_focus() {
        // The first call starts the refresher, which fills the snapshot on its
        // own thread; the cursor publisher keeps asking, so that is enough.
        let _ = focused();
        std::thread::sleep(Duration::from_millis(400));
        println!("compositor focus: {:?}", focused());
        let mut worst = Duration::ZERO;
        for _ in 0..10 {
            let start = Instant::now();
            let _ = probe();
            worst = worst.max(start.elapsed());
        }
        println!("uncached probe worst {worst:?}");
        let start = Instant::now();
        for _ in 0..1000 {
            let _ = focused();
        }
        println!("1000 snapshot reads: {:?}", start.elapsed());
    }
}
