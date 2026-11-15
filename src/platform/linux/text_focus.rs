//! Text-input detection through the accessibility bus (AT-SPI), for native
//! Wayland clients: their pointer never reaches XFixes, so the sampled cursor
//! shape cannot say whether the pointer is over a text field.
//!
//! A lazily started background thread subscribes to the focus events of the
//! accessibility bus, asks the focused accessible object for its role, state
//! and screen extents, and remembers the rectangle of a focused text input.
//! The cursor publisher reports the Windows I-beam cursor type there, which is
//! what the controller keys its virtual keyboard on.
//!
//! The bus is only read: events are inspected, no text is read, no event
//! content is logged, and nothing is injected or enabled. A session without an
//! accessibility bus degrades silently, and `OPENUUYC_ATSPI=0` turns it off.
use anyhow::{Context, Result, ensure};
use std::{
    sync::{Mutex, MutexGuard, Once},
    thread,
};
use zbus::{
    MatchRule, Message, OwnedMatchRule,
    blocking::{Connection, MessageIterator, Proxy, connection::Builder},
    message::Type,
    zvariant::{Structure, Value},
};

/// AT-SPI roles that take text input (`atspi-constants.h`, `AtspiRole`).
const PASSWORD_TEXT: u32 = 40;
const TERMINAL: u32 = 60;
const TEXT: u32 = 61;
const EDITBAR: u32 = 77;
const ENTRY: u32 = 79;
const DOCUMENT_SPREADSHEET: u32 = 92;
const DOCUMENT_EMAIL: u32 = 96;

/// AT-SPI state bits (`atspi-constants.h`, `AtspiStateType`), indexed into the
/// 32-bit words of the `au` state set.
const EDITABLE: u32 = 7;
const FOCUSED: u32 = 12;
const SHOWING: u32 = 25;
const VISIBLE: u32 = 30;

/// Event interfaces a focus change arrives on: `object:state-changed:focused`
/// and the older `focus:focus`.
const EVENT_OBJECT: &str = "org.a11y.atspi.Event.Object";
const EVENT_FOCUS: &str = "org.a11y.atspi.Event.Focus";

/// `ATSPI_COORD_TYPE_SCREEN`: extents in the space the pointer is named in.
const COORD_TYPE_SCREEN: u32 = 0;
/// ATSPI_COORD_TYPE_WINDOW: extents relative to the toplevel, which is what a
/// native Wayland toolkit knows; the compositor supplies the window origin.
const COORD_TYPE_WINDOW: u32 = 1;

/// A text input's screen rectangle: left, top, width, height.
pub(crate) type Rect = (i32, i32, u32, u32);

/// The focused text input, keyed by the accessible object that reported it so
/// a late focus-loss event cannot clear a newer focus.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Focused {
    sender: String,
    path: String,
    /// Screen extents, when the toolkit reports them.
    screen: Option<Rect>,
    /// Window-relative extents: what a native Wayland toolkit can give, with
    /// the compositor supplying the window origin.
    window: Option<Rect>,
}

static STARTED: Once = Once::new();
static CURRENT: Mutex<Option<Focused>> = Mutex::new(None);

fn lock<T>(mutex: &'static Mutex<T>) -> MutexGuard<'static, T> {
    mutex.lock().unwrap_or_else(|error| error.into_inner())
}

/// Starts the accessibility listener the first time it is called. Idempotent;
/// a failure is a debug line once, never a panic: no accessibility bus simply
/// means the pointer shape stays the only input-field hint.
pub(crate) fn ensure_started() {
    STARTED.call_once(|| {
        if disabled() {
            tracing::debug!("AT-SPI text focus disabled by OPENUUYC_ATSPI");
            return;
        }
        if let Err(error) = thread::Builder::new()
            .name("openuuyc-atspi".to_owned())
            .spawn(run)
        {
            tracing::debug!(%error, "AT-SPI text focus thread unavailable");
        }
    });
}

/// The focused text input's screen rectangle, if one is known right now.
///
/// The window argument is the focused window's global rectangle from the
/// compositor, when it reports one. A toolkit that only knows window-relative
/// extents (native Wayland) is translated through it, and a rectangle that
/// misses the focused window belongs to a background app whose focus event
/// arrived late.
pub(crate) fn focused_text_rect(window: Option<Rect>) -> Option<Rect> {
    let focused = lock(&CURRENT);
    let focused = focused.as_ref()?;
    let rect = match (focused.screen, focused.window, window) {
        (Some(screen), _, _) => screen,
        (None, Some(inner), Some(outer)) => (
            outer.0.saturating_add(inner.0),
            outer.1.saturating_add(inner.1),
            inner.2,
            inner.3,
        ),
        _ => return None,
    };
    if let Some(window) = window
        && !super::compositor_focus::overlaps(window, rect)
    {
        return None;
    }
    Some(rect)
}

fn disabled() -> bool {
    std::env::var("OPENUUYC_ATSPI").is_ok_and(|value| value.trim() == "0")
}

fn run() {
    let connection = match connect() {
        Ok(connection) => connection,
        Err(error) => {
            tracing::debug!(error = %format!("{error:#}"), "AT-SPI text focus unavailable");
            return;
        }
    };
    tracing::debug!("AT-SPI text focus monitoring started");
    let rules = match (
        signal_rule(EVENT_OBJECT, "StateChanged"),
        signal_rule(EVENT_FOCUS, "Focus"),
    ) {
        (Ok(state), Ok(focus)) => [state, focus],
        (Err(error), _) | (_, Err(error)) => {
            tracing::debug!(error = %format!("{error:#}"), "AT-SPI event subscription unavailable");
            return;
        }
    };
    // A blocking message iterator waits on one match rule at a time, so each
    // subscription gets a worker of its own; this thread owns their lifetime.
    thread::scope(|scope| {
        let connection = &connection;
        for rule in rules {
            scope.spawn(move || {
                if let Err(error) = watch(connection, rule) {
                    tracing::debug!(error = %format!("{error:#}"), "AT-SPI event subscription ended");
                }
            });
        }
    });
}

/// Connects to the accessibility bus the session's `org.a11y.Bus` points at.
fn connect() -> Result<Connection> {
    let session = Connection::session().context("连接会话总线失败")?;
    let bus = Proxy::new(&session, "org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus")
        .context("会话总线未提供 org.a11y.Bus")?;
    let address: String = bus
        .call("GetAddress", &())
        .context("桌面未启动无障碍总线")?;
    ensure!(!address.trim().is_empty(), "无障碍总线地址为空");
    Builder::address(address.as_str())
        .context("无障碍总线地址无效")?
        .build()
        .context("连接无障碍总线失败")
}

fn signal_rule(interface: &str, member: &str) -> Result<OwnedMatchRule> {
    Ok(MatchRule::builder()
        .msg_type(Type::Signal)
        .interface(interface)?
        .member(member)?
        .build()
        .into())
}

fn watch(connection: &Connection, rule: OwnedMatchRule) -> Result<()> {
    for message in MessageIterator::for_match_rule(rule, connection, Some(64))? {
        match message {
            Ok(message) => handle(connection, &message),
            // A lagged or malformed message must not end the subscription.
            Err(_) => continue,
        }
    }
    Ok(())
}

/// Turns one event signal into the current focused text rectangle. Only the
/// event's interface, member, source and the state name are looked at; the
/// event's payload is never logged.
fn handle(connection: &Connection, message: &Message) {
    let header = message.header();
    let (Some(interface), Some(member), Some(sender), Some(path)) = (
        header.interface(),
        header.member(),
        header.sender(),
        header.path(),
    ) else {
        return;
    };
    let Some((state, enabled)) = event_args(&message.body()) else {
        return;
    };
    let Some(gained) = focus_change(interface.as_str(), member.as_str(), &state, enabled) else {
        return;
    };
    let (sender, path) = (sender.as_str(), path.as_str());
    if !gained {
        // Only the object that is currently remembered may clear it: focus
        // moves deliver the old object's loss after the new object's gain.
        let mut focused = lock(&CURRENT);
        if focused
            .as_ref()
            .is_some_and(|focused| focused.sender == sender && focused.path == path)
        {
            *focused = None;
        }
        return;
    }
    *lock(&CURRENT) = text_input(connection, sender, path).map(|(screen, window)| Focused {
        sender: sender.to_owned(),
        path: path.to_owned(),
        screen,
        window,
    });
}

/// Queries the accessible object behind an event source and returns its screen
/// and window-relative rectangles when it is a focused text input.
fn text_input(
    connection: &Connection,
    sender: &str,
    path: &str,
) -> Option<(Option<Rect>, Option<Rect>)> {
    let accessible = Proxy::new(connection, sender, path, "org.a11y.atspi.Accessible").ok()?;
    let role: u32 = accessible.call("GetRole", &()).ok()?;
    if !text_role(role) {
        return None;
    }
    let states: Vec<u32> = accessible.call("GetState", &()).ok()?;
    if !text_focused(role, &states) {
        return None;
    }
    let component = Proxy::new(connection, sender, path, "org.a11y.atspi.Component").ok()?;
    let extents: (i32, i32, i32, i32) = component.call("GetExtents", &(COORD_TYPE_SCREEN,)).ok()?;
    let screen = rect_from_extents(extents);
    // Native Wayland toolkits answer screen coordinates with garbage; their
    // window-relative extents are the ones that hold, so ask for those when the
    // screen rectangle is unusable.
    let window = match screen {
        Some(_) => None,
        None => rect_from_extents(component.call("GetExtents", &(COORD_TYPE_WINDOW,)).ok()?),
    };
    (screen.is_some() || window.is_some()).then_some((screen, window))
}

/// The event name and its enable flag from an AT-SPI event signal body
/// (`siiva{sv}`: detail name, detail flag, unused string, data, properties).
fn event_args(body: &zbus::message::Body) -> Option<(String, i32)> {
    let structure: Structure = body.deserialize().ok()?;
    match structure.fields() {
        [Value::Str(state), Value::I32(enabled), ..] => Some((state.to_string(), *enabled)),
        _ => None,
    }
}

/// Whether an event signal means a focus gain (`true`) or loss (`false`).
fn focus_change(interface: &str, member: &str, state: &str, enabled: i32) -> Option<bool> {
    match (interface, member) {
        (EVENT_OBJECT, "StateChanged") if state == "focused" => Some(enabled != 0),
        (EVENT_FOCUS, "Focus") => Some(true),
        _ => None,
    }
}

/// Whether an AT-SPI role is one that takes text input.
fn text_role(role: u32) -> bool {
    matches!(
        role,
        PASSWORD_TEXT | TERMINAL | TEXT | EDITBAR | ENTRY | DOCUMENT_SPREADSHEET..=DOCUMENT_EMAIL
    )
}

/// Whether one state bit is set. AT-SPI sends its state set as 32-bit words,
/// least significant bit first, so the bit index spans words.
fn state_set(states: &[u32], state: u32) -> bool {
    states
        .get((state / 32) as usize)
        .is_some_and(|word| word & (1 << (state % 32)) != 0)
}

/// Whether a focused accessible object is a text input the user can type in.
fn text_focused(role: u32, states: &[u32]) -> bool {
    text_role(role)
        && [FOCUSED, EDITABLE, SHOWING, VISIBLE]
            .into_iter()
            .all(|state| state_set(states, state))
}

/// The rectangle of an AT-SPI `GetExtents` reply, ignoring empty widgets.
fn rect_from_extents((left, top, width, height): (i32, i32, i32, i32)) -> Option<Rect> {
    (width > 0 && height > 0).then(|| (left, top, width as u32, height as u32))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn states(bits: &[u32]) -> Vec<u32> {
        let mut words = vec![0; 2];
        for &bit in bits {
            words[(bit / 32) as usize] |= 1 << (bit % 32);
        }
        words
    }

    /// A focused, editable, shown text field.
    fn text_states() -> Vec<u32> {
        states(&[FOCUSED, EDITABLE, SHOWING, VISIBLE])
    }

    fn event_message(
        interface: &'static str,
        member: &'static str,
        state: &str,
        enabled: i32,
    ) -> Message {
        let data: Value<'static> = Value::from(0i32);
        let properties: HashMap<String, Value<'static>> = HashMap::new();
        Message::signal("/org/a11y/atspi/accessible/1", interface, member)
            .unwrap()
            .build(&(state.to_owned(), enabled, String::new(), data, properties))
            .unwrap()
    }

    #[test]
    fn text_roles_take_text_input() {
        for role in [
            PASSWORD_TEXT,
            TERMINAL,
            TEXT,
            EDITBAR,
            ENTRY,
            DOCUMENT_SPREADSHEET,
            93,
            94,
            95,
            DOCUMENT_EMAIL,
        ] {
            assert!(text_role(role), "role {role} is a text role");
        }
        // Push buttons, labels and document frames around the text roles are not.
        for role in [0, 43, 30, 82, 91, 97] {
            assert!(!text_role(role), "role {role} is not a text role");
        }
    }

    #[test]
    fn state_bits_span_the_state_words() {
        let states = states(&[EDITABLE, VISIBLE]);
        assert!(state_set(&states, EDITABLE));
        assert!(state_set(&states, VISIBLE));
        assert!(!state_set(&states, FOCUSED));
        // Out of range words are simply absent, not a panic.
        assert!(!state_set(&states, 64));
        assert!(!state_set(&[], FOCUSED));
    }

    #[test]
    fn only_focused_editable_shown_text_is_a_text_input() {
        assert!(text_focused(TEXT, &text_states()));
        assert!(text_focused(PASSWORD_TEXT, &text_states()));
        for missing in [FOCUSED, EDITABLE, SHOWING, VISIBLE] {
            let without = states(
                &[FOCUSED, EDITABLE, SHOWING, VISIBLE]
                    .into_iter()
                    .filter(|&bit| bit != missing)
                    .collect::<Vec<_>>(),
            );
            assert!(!text_focused(TEXT, &without), "state {missing} is required");
        }
        // A read-only document, or a focused non-text widget, is not one.
        assert!(!text_focused(TEXT, &states(&[FOCUSED, SHOWING, VISIBLE])));
        assert!(!text_focused(43, &text_states()));
    }

    #[test]
    fn empty_extents_have_no_rectangle() {
        assert_eq!(
            rect_from_extents((10, 20, 300, 40)),
            Some((10, 20, 300, 40))
        );
        assert_eq!(rect_from_extents((10, 20, 0, 40)), None);
        assert_eq!(rect_from_extents((10, 20, 300, -1)), None);
    }

    #[test]
    fn focus_events_are_recognised_by_interface_and_state() {
        assert_eq!(
            focus_change(EVENT_OBJECT, "StateChanged", "focused", 1),
            Some(true)
        );
        assert_eq!(
            focus_change(EVENT_OBJECT, "StateChanged", "focused", 0),
            Some(false)
        );
        // Other state changes on the same interface are not focus changes.
        assert_eq!(
            focus_change(EVENT_OBJECT, "StateChanged", "showing", 1),
            None
        );
        assert_eq!(focus_change(EVENT_FOCUS, "Focus", "", 0), Some(true));
        assert_eq!(focus_change(EVENT_OBJECT, "Focus", "", 0), None);
        assert_eq!(focus_change(EVENT_FOCUS, "StateChanged", "", 0), None);
    }

    #[test]
    fn event_body_yields_the_state_name_and_flag() {
        let state = event_message(EVENT_OBJECT, "StateChanged", "focused", 1);
        assert_eq!(event_args(&state.body()), Some(("focused".to_owned(), 1)));
        let focus = event_message(EVENT_FOCUS, "Focus", "", 0);
        assert_eq!(event_args(&focus.body()), Some((String::new(), 0)));
    }

    /// Needs a live desktop session with the accessibility bus running
    /// (`at-spi-bus-launcher`, normally started by the desktop or on demand);
    /// a session without one simply has nothing to connect to, so this is not
    /// part of CI.
    #[test]
    #[ignore = "needs a live desktop session with AT-SPI"]
    fn accessibility_bus_is_reachable() {
        let connection = connect().expect("accessibility bus");
        let bus = Proxy::new(
            &connection,
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            "org.freedesktop.DBus",
        )
        .expect("bus proxy");
        let id: String = bus.call("GetId", &()).expect("bus id");
        println!("accessibility bus id: {id}");
        ensure_started();
        std::thread::sleep(std::time::Duration::from_millis(200));
        println!("focused text rect: {:?}", focused_text_rect(None));
    }
}
