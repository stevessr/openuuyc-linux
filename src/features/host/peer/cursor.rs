//! The capture owner samples the actual desktop; this task only publishes changes.
use super::{ReportRoutes, screens::Reports};
use crate::features::{
    host::lock,
    remote_cursor::{self, CursorImage},
};
use base64::Engine;
use std::{
    sync::{Arc, atomic::Ordering},
    time::Duration,
};
use tokio::sync::watch;
use tokio_util::sync::CancellationToken;

/// A live capture source, as the capture loop publishes it with each frame.
type Source = crate::features::host::capture::Screen;
/// The sampled XFixes pointer (or its Windows counterpart).
type Pointer = crate::platform::cursor_shape::Snapshot;
/// A focused text input's screen rectangle: left, top, width, height.
type TextRect = (i32, i32, u32, u32);

pub(super) async fn run(
    reports: Arc<Reports>,
    mut route: watch::Receiver<ReportRoutes>,
    cancel: CancellationToken,
    lease: crate::features::host::Lease,
    kcp: crate::transport::uu_kcp::UuKcpControl,
) {
    let mut timer = tokio::time::interval(Duration::from_millis(33));
    timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    ensure_text_focus();
    let wayland = wayland_host();
    let mut revision = None;
    let mut sent = None;
    loop {
        tokio::select! {_=cancel.cancelled()=>return,_=timer.tick()=>{},r=route.changed()=>{if r.is_err(){return}}}
        if !lease.requested() {
            return;
        }
        let routes = route.borrow_and_update().clone();
        let Some(channel) = routes.control.as_ref().and_then(std::sync::Weak::upgrade) else {
            continue;
        };
        if revision != Some(routes.revision) {
            revision = Some(routes.revision);
            sent = None;
        }
        let media = reports.media();
        let current = reports.current.load(Ordering::Acquire);
        // The rectangle the capture loop publishes with every frame, not the
        // directory catalog, which is only refreshed once a second: a window
        // source that was moved or resized is matched as soon as the next frame
        // lands.
        let sources: Vec<Source> = media
            .iter()
            .filter(|(_, m)| m.capturing && m.visible)
            .map(|(_, m)| m.screen.clone())
            .collect();
        let pointer = media
            .iter()
            .filter(|(_, m)| m.capturing && m.visible)
            .max_by_key(|(_, m)| m.screen.id == current)
            .and_then(|(index, _)| lock(&reports.pointers[*index]).clone());
        // The compositor's own focus channel: which window is focused and, on
        // Plasma, whether it has a text input. It turns the accessibility bus's
        // window-relative rectangle into a screen one and rejects focus events
        // from a background app.
        let (window, text_input) = compositor_hint();
        let text = text_rect(window, text_input);
        // A compositor that reports an active text input but hands out no
        // rectangle (Plasma) still means the watched screen has one.
        let text_active = text.is_none() && text_input == Some(true);
        let (screen, text_hint, position) = resolve(
            pointer.as_ref(),
            text,
            text_active,
            &sources,
            current,
            wayland,
        );
        let visible = pointer
            .as_ref()
            .is_some_and(|p| p.showing && p.image.is_some())
            && screen.is_some();
        let image = if visible {
            pointer.as_ref().and_then(|p| p.image.clone())
        } else {
            None
        };
        // The controller keys its virtual keyboard on the Windows I-beam type.
        // On X11/XWayland the sampled shape is authoritative, so only unknown
        // and plain-arrow shapes are upgraded (resize, move and other named
        // shapes keep their own meaning). On Wayland the sampled shape belongs
        // to XWayland and is stale over native clients, so a focused text input
        // on the watched screen is the better signal there.
        let text_focus = text_hint
            && image
                .as_ref()
                .is_some_and(|image| wayland || matches!(image.kind, 0 | 32512));
        let key = (
            image.clone(),
            screen.as_ref().map_or(-1, |s| s.id),
            screen
                .as_ref()
                .map(|s| (s.dpi_scale, s.width, s.height, s.left, s.top)),
            text_focus,
        );
        if sent.as_ref() == Some(&key) {
            continue;
        }
        let image = match image {
            Some(image) => match base64::engine::general_purpose::STANDARD.decode(&image.png) {
                Ok(png) => Some(CursorImage {
                    png,
                    width: image.width,
                    height: image.height,
                    hotspot: image.hotspot,
                    // The controller's virtual keyboard keys on the Windows
                    // I-beam type; the sampled PNG and hotspot stay untouched.
                    system_type: if text_focus { 32513 } else { image.kind },
                }),
                Err(error) => {
                    tracing::warn!(%error,"invalid sampled cursor image");
                    continue;
                }
            },
            None => None,
        };
        let payload = remote_cursor::encode_shape(image.as_ref(), key.1, position);
        let bytes = crate::features::stream_control::publisher::cursor_report(payload);
        if bytes.len() > 512 * 1024 {
            tracing::warn!(bytes = bytes.len(), "cursor exceeds signal message limit");
            continue;
        }
        let result =
            tokio::select! {_=cancel.cancelled()=>return,r=kcp.send_control(&channel,bytes)=>r};
        match result {
            Ok(_) => {
                tracing::debug!(screen=key.1,visible,size=?image.as_ref().map(|i|(i.width,i.height)),"host cursor CONTROL state published");
                sent = Some(key);
            }
            Err(error) => tracing::debug!(%error,"host cursor CONTROL publication failed"),
        }
    }
}

/// Which source the pointer is on, whether a text input is focused there, and
/// the normalized position to report.
///
/// On Wayland the sampled pointer position comes from XWayland: over a native
/// client it can be stale (the last position over an X11 window) or absent.
/// A text input focused on the screen the controller is watching is therefore
/// reported there even when the stale pointer says otherwise; the reported
/// position falls back to the input's center. On X11 the pointer stays
/// authoritative.
fn resolve(
    pointer: Option<&Pointer>,
    text: Option<TextRect>,
    text_active: bool,
    sources: &[Source],
    current: i32,
    wayland: bool,
) -> (Option<Source>, bool, [f64; 2]) {
    let pointer_screen = pointer.and_then(|p| pick(sources, current, |s| contains(s, p.x, p.y)));
    let text_screen = text.and_then(|rect| {
        let (x, y) = rect_center(rect);
        pick(sources, current, |s| contains(s, x, y))
    });
    let text_on_current = text_screen.as_ref().is_some_and(|s| s.id == current);
    let pointer_in_text = pointer
        .zip(text)
        .is_some_and(|(p, rect)| rect_contains(rect, p.x, p.y));
    // Native Wayland clients never reach XFixes, so a focused text field on the
    // watched screen is enough there. On X11 only the pointer over the field
    // counts, matching what the Windows host reports.
    let wayland_text = wayland && text_on_current;
    // Plasma answers "a text input is focused" without handing out the field's
    // rectangle. The watched screen then stands in for it: the controller is
    // looking there, and on Wayland the sampled pointer may be stale anyway.
    let compositor_text = wayland && text_active;
    let text_hint = pointer_in_text || wayland_text || compositor_text;
    let screen = if wayland_text {
        text_screen.clone().or(pointer_screen)
    } else if compositor_text {
        pointer_screen
            .clone()
            .filter(|screen| screen.id == current)
            .or_else(|| sources.iter().find(|source| source.id == current).cloned())
            .or(pointer_screen)
    } else {
        pointer_screen.or(text_screen)
    };
    let position = match (pointer, screen.as_ref()) {
        (Some(p), Some(s)) if contains(s, p.x, p.y) => normalize(p.x, p.y, s),
        (_, Some(s)) => text
            .map(rect_center)
            .map(|(x, y)| normalize(x, y, s))
            .unwrap_or_else(|| center(s)),
        _ => [0.0, 0.0],
    };
    (screen, text_hint, position)
}

/// The source containing the position, preferring the screen the controller is
/// watching when several sources overlap (a window inside a display).
fn pick(sources: &[Source], current: i32, inside: impl Fn(&Source) -> bool) -> Option<Source> {
    sources
        .iter()
        .filter(|source| inside(source))
        .max_by_key(|source| source.id == current)
        .cloned()
}

fn rect_center((left, top, width, height): TextRect) -> (i32, i32) {
    (
        left.saturating_add(i32::try_from(width / 2).unwrap_or(i32::MAX)),
        top.saturating_add(i32::try_from(height / 2).unwrap_or(i32::MAX)),
    )
}

fn rect_contains((left, top, width, height): TextRect, x: i32, y: i32) -> bool {
    i64::from(x) >= i64::from(left)
        && i64::from(x) < i64::from(left) + i64::from(width)
        && i64::from(y) >= i64::from(top)
        && i64::from(y) < i64::from(top) + i64::from(height)
}

/// The centre of a source, in the same normalized space as normalize().
fn center(screen: &Source) -> [f64; 2] {
    normalize(
        screen
            .left
            .saturating_add(i32::try_from(screen.width / 2).unwrap_or(i32::MAX)),
        screen
            .top
            .saturating_add(i32::try_from(screen.height / 2).unwrap_or(i32::MAX)),
        screen,
    )
}

fn normalize(x: i32, y: i32, screen: &Source) -> [f64; 2] {
    [
        (f64::from(x) - f64::from(screen.left)) / f64::from(screen.width.max(1)),
        (f64::from(y) - f64::from(screen.top)) / f64::from(screen.height.max(1)),
    ]
}

/// Whether a global pointer position falls inside a source's live rectangle,
/// as the capture loop publishes it with each frame.
fn contains(screen: &Source, x: i32, y: i32) -> bool {
    i64::from(x) >= i64::from(screen.left)
        && i64::from(x) < i64::from(screen.left) + i64::from(screen.width)
        && i64::from(y) >= i64::from(screen.top)
        && i64::from(y) < i64::from(screen.top) + i64::from(screen.height)
}

/// Starts the accessibility-bus listener that reports the focused text input.
/// Linux only; a session without an accessibility bus keeps the sampled XFixes
/// shape alone.
#[cfg(target_os = "linux")]
fn ensure_text_focus() {
    crate::platform::linux::text_focus::ensure_started();
}
#[cfg(not(target_os = "linux"))]
fn ensure_text_focus() {}

/// The focused window's rectangle and the compositor's own text-input answer,
/// when the session's compositor publishes them. Other platforms have neither.
#[cfg(target_os = "linux")]
fn compositor_hint() -> (Option<TextRect>, Option<bool>) {
    crate::platform::linux::compositor_focus::focused()
        .map_or((None, None), |focus| (focus.rect, focus.text_input))
}
#[cfg(not(target_os = "linux"))]
fn compositor_hint() -> (Option<TextRect>, Option<bool>) {
    (None, None)
}

/// The focused text input's screen rectangle, when the accessibility bus knows
/// one. Other platforms have no AT-SPI.
#[cfg(target_os = "linux")]
fn focused_text_rect(window: Option<TextRect>) -> Option<TextRect> {
    crate::platform::linux::text_focus::focused_text_rect(window)
}
#[cfg(not(target_os = "linux"))]
fn focused_text_rect(_window: Option<TextRect>) -> Option<TextRect> {
    None
}

/// The rectangle to report as a text input, if any.
///
/// The accessibility bus names the field itself. When it has no rectangle but
/// the compositor says a text input is focused (Plasma), the focused window is
/// where the controller's keyboard belongs.
fn text_rect(window: Option<TextRect>, text_input: Option<bool>) -> Option<TextRect> {
    match focused_text_rect(window) {
        Some(rect) => Some(rect),
        None if text_input == Some(true) => window,
        None => None,
    }
}

/// Whether this host runs a Wayland session, where XFixes only sees XWayland.
#[cfg(target_os = "linux")]
fn wayland_host() -> bool {
    crate::platform::linux::capture::wayland_session()
}
#[cfg(not(target_os = "linux"))]
fn wayland_host() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    fn source(id: i32, left: i32, top: i32, width: u32, height: u32) -> Source {
        Source {
            id,
            device_name: String::new(),
            display_name: String::new(),
            width,
            height,
            left,
            top,
            primary: id == 0,
            fps: 60,
            dpi_scale: None,
            hdr: false,
            adapter: 0,
            render_adapter: None,
            identity: None,
        }
    }

    fn pointer(x: i32, y: i32) -> Pointer {
        Pointer {
            showing: true,
            x,
            y,
            image: None,
        }
    }

    #[test]
    fn a_pointer_inside_a_source_resolves_to_it() {
        let sources = [source(0, 0, 0, 1920, 1080), source(1, 1920, 0, 1920, 1080)];
        let (screen, text, position) =
            resolve(Some(&pointer(2000, 300)), None, false, &sources, 0, false);
        assert_eq!(screen.map(|s| s.id), Some(1));
        assert!(!text);
        assert_eq!(position[0], (2000.0 - 1920.0) / 1920.0);
    }

    #[test]
    fn a_window_source_wins_over_the_display_it_sits_on() {
        // A window source is a sub-rectangle of the display source; the screen
        // the controller selected decides which one is reported.
        let sources = [source(0, 0, 0, 1920, 1080), source(7, 100, 100, 800, 600)];
        let (screen, _, _) = resolve(Some(&pointer(300, 300)), None, false, &sources, 7, false);
        assert_eq!(screen.map(|s| s.id), Some(7));
        let (screen, _, _) = resolve(Some(&pointer(300, 300)), None, false, &sources, 0, false);
        assert_eq!(screen.map(|s| s.id), Some(0));
    }

    #[test]
    fn x11_needs_the_pointer_on_the_text_field() {
        let sources = [source(0, 0, 0, 1920, 1080)];
        let rect = (100, 100, 300, 40);
        let (screen, text, _) = resolve(
            Some(&pointer(120, 110)),
            Some(rect),
            false,
            &sources,
            0,
            false,
        );
        assert!(text);
        assert_eq!(screen.map(|s| s.id), Some(0));
        // Focused text field elsewhere on the screen is not the pointer's field.
        let (_, text, _) = resolve(
            Some(&pointer(900, 900)),
            Some(rect),
            false,
            &sources,
            0,
            false,
        );
        assert!(!text);
    }

    #[test]
    fn wayland_trusts_the_focused_field_when_the_pointer_is_stale() {
        let sources = [source(0, 0, 0, 1920, 1080), source(1, 1920, 0, 1920, 1080)];
        let rect = (2000, 200, 400, 40);
        // Stale XWayland pointer still sits on the other screen.
        let (screen, text, position) = resolve(
            Some(&pointer(300, 300)),
            Some(rect),
            false,
            &sources,
            1,
            true,
        );
        assert!(text);
        assert_eq!(screen.map(|s| s.id), Some(1));
        // The reported position falls back to the focused field's center.
        assert_eq!(position[0], (2200.0 - 1920.0) / 1920.0);
    }

    #[test]
    fn wayland_without_a_focused_text_field_keeps_the_pointer_rule() {
        let sources = [source(0, 0, 0, 1920, 1080)];
        let (screen, text, _) = resolve(Some(&pointer(300, 300)), None, false, &sources, 0, true);
        assert_eq!(screen.map(|s| s.id), Some(0));
        assert!(!text);
    }

    #[test]
    fn a_focused_field_on_another_screen_does_not_steal_the_pointer() {
        let sources = [source(0, 0, 0, 1920, 1080), source(1, 1920, 0, 1920, 1080)];
        let rect = (2000, 200, 400, 40);
        let (screen, text, _) = resolve(
            Some(&pointer(300, 300)),
            Some(rect),
            false,
            &sources,
            0,
            true,
        );
        assert_eq!(screen.map(|s| s.id), Some(0));
        assert!(!text);
    }

    #[test]
    fn text_rect_edges_are_half_open() {
        let rect = (100, 200, 50, 20);
        assert!(rect_contains(rect, 100, 200));
        assert!(rect_contains(rect, 149, 219));
        assert!(!rect_contains(rect, 150, 200));
        assert!(!rect_contains(rect, 100, 220));
        assert_eq!(rect_center(rect), (125, 210));
    }

    #[test]
    fn a_compositor_hint_without_a_rectangle_names_the_watched_screen() {
        let sources = [source(0, 0, 0, 1920, 1080), source(1, 1920, 0, 1920, 1080)];
        // Stale XWayland pointer sits on screen 0, but the controller watches 1
        // and the compositor says a text input is focused there.
        let (screen, text, position) =
            resolve(Some(&pointer(300, 300)), None, true, &sources, 1, true);
        assert!(text);
        assert_eq!(screen.map(|s| s.id), Some(1));
        // No rectangle: the reported position is the screen centre, not (0,0).
        assert_eq!(position, [0.5, 0.5]);
        // On X11 the compositor hint alone never upgrades the cursor.
        let (screen, text, _) = resolve(Some(&pointer(300, 300)), None, true, &sources, 1, false);
        assert!(!text);
        assert_eq!(screen.map(|s| s.id), Some(0));
    }

    #[test]
    fn a_compositor_text_hint_falls_back_to_the_focused_window() {
        let window = (100, 100, 800, 600);
        // No accessibility rectangle, but the compositor says a text input has
        // focus: the focused window names where the keyboard belongs.
        assert_eq!(text_rect(Some(window), Some(true)), Some(window));
        // Nothing known on either side stays quiet.
        assert_eq!(text_rect(Some(window), None), None);
        assert_eq!(text_rect(None, Some(true)), None);
    }
}
