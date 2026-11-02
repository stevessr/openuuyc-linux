//! Text commit through the input method's D-Bus "portal" interface.
//!
//! UUWay submits the phone's Unicode text through an Fcitx5 add-on; this is
//! the same outcome reached the other way round: `org.fcitx.Fcitx.InputMethod1
//! .CreateInputContext` (the service both fcitx5 and IBus-compatible panels
//! expose as `org.freedesktop.portal.Fcitx`) creates a client input context,
//! and the text is committed to whatever input field the desktop currently
//! has focused — no clipboard, no synthetic key presses, nothing logged.
//!
//! A context created this way has no client-side focus of its own: Fcitx
//! routes its commits by which *application* input context currently holds
//! the desktop's focus, so the string lands in the window the controlled
//! user is typing in. The context is created per commit and destroyed right
//! after, so a lost connection or a cancelled permission never leaves state
//! behind. Like `zwp_virtual_keyboard`, this only exists in a Wayland (or
//! Fcitx-enabled X11) session; callers treat an error as "fall back to the
//! next path", never as a typed character.
//!
//! This module deliberately does not use UUWay's AGPL sources or its Wine
//! bridge: the protocol here is the stock Fcitx D-Bus interface, verified
//! against the local `fcitx5 5.1.23` build.
use anyhow::{Context as _, Result, ensure};
use std::sync::{Arc, LazyLock, Mutex};
use zbus::blocking::{Connection, Proxy};

/// The service every Fcitx5 session registers, including KDE's and GNOME's.
const SERVICE: &str = "org.freedesktop.portal.Fcitx";
/// Where `CreateInputContext` lives. Both the portal alias and fcitx5's own
/// name answer on it.
const PATH: &str = "/org/freedesktop/portal/inputmethod";
/// The Fcitx5 client-side input-method interface.
const INTERFACE: &str = "org.fcitx.Fcitx.InputMethod1";
/// The per-context interface returned alongside the object path.
const CONTEXT_INTERFACE: &str = "org.fcitx.Fcitx.InputContext1";

/// This client sends committed strings only: it offers no preedit, key-event,
/// or surrounding-text support to the input method.
const CAPABILITY_FLAGS: u64 = 0;

/// zbus does not share `Connection::session()` automatically. Reuse one bus
/// connection, replacing it when the underlying socket has closed.
struct ConnectionCache<T> {
    current: Mutex<Option<Arc<T>>>,
}

impl<T> Default for ConnectionCache<T> {
    fn default() -> Self {
        Self {
            current: Mutex::new(None),
        }
    }
}

impl<T> ConnectionCache<T> {
    fn get_or_try_init(
        &self,
        usable: impl FnOnce(&T) -> bool,
        create: impl FnOnce() -> Result<T>,
    ) -> Result<Arc<T>> {
        let mut current = self.current.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(connection) = current.as_ref()
            && usable(connection)
        {
            return Ok(connection.clone());
        }
        let connection = Arc::new(create()?);
        *current = Some(connection.clone());
        Ok(connection)
    }

    /// An old request must not evict a newer connection installed meanwhile.
    fn invalidate(&self, connection: &Arc<T>) {
        let mut current = self.current.lock().unwrap_or_else(|e| e.into_inner());
        if current
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(current, connection))
        {
            *current = None;
        }
    }
}

static SESSION: LazyLock<ConnectionCache<Connection>> = LazyLock::new(ConnectionCache::default);

fn session_connection() -> Result<Arc<Connection>> {
    SESSION.get_or_try_init(
        |connection| !connection.is_closed(),
        || Connection::session().context("连接会话总线失败"),
    )
}

fn invalidate_if_closed(connection: &Arc<Connection>) {
    if connection.is_closed() {
        SESSION.invalidate(connection);
    }
}

/// One short-lived input context. The cached D-Bus connection is shared, but
/// the focused input context is explicitly destroyed after each text commit.
pub(crate) struct Context {
    connection: Arc<Connection>,
    path: zbus::zvariant::OwnedObjectPath,
}

impl Context {
    /// Create an input context that commits into whatever field currently
    /// has the desktop's input focus. Fails (and the caller falls back) when
    /// no Fcitx-compatible daemon is on the session bus.
    pub(crate) fn open() -> Result<Self> {
        let connection = session_connection()?;
        let proxy = Proxy::new(&connection, SERVICE, PATH, INTERFACE)
            .context("输入法的 D-Bus 门户不可用")?;
        // (program, display) pairs; `program` shows up in Fcitx's own UI, the
        // display name is informational. Only the values matter to the daemon.
        // The reply is `(objectpath ay)`: owned types keep the lifetime
        // inference from tying the response to the borrowed body.
        let created = proxy.call("CreateInputContext", &(("program", "OpenUUYC"),));
        let (path, _uuid): (zbus::zvariant::OwnedObjectPath, Vec<u8>) = match created {
            Ok(reply) => reply,
            Err(error) => {
                invalidate_if_closed(&connection);
                return Err(error).context("输入法门户拒绝了新的输入上下文");
            }
        };
        ensure!(
            path.as_str()
                .starts_with("/org/freedesktop/portal/inputcontext/"),
            "输入法门户返回了意外的对象路径"
        );
        let context = Proxy::new(&connection, SERVICE, path.as_str(), CONTEXT_INTERFACE)
            .context("输入上下文接口不可用")?;
        if let Err(error) = context.call::<_, _, ()>("SetCapability", &(CAPABILITY_FLAGS,)) {
            let _ = context.call::<_, _, ()>("DestroyIC", &());
            invalidate_if_closed(&connection);
            return Err(error).context("无法设置输入法上下文能力");
        }
        drop(context);
        Ok(Self { connection, path })
    }

    /// Commit `text` into the currently focused input field. The daemon
    /// delivers it the way a real input method would — through the focused
    /// client's `commitString` — so undo, compose state and text prediction
    /// behave as for local typing.
    pub(crate) fn commit(&self, text: &str) -> Result<()> {
        ensure!(!text.is_empty(), "没有要提交的文字");
        // UTF-8 validity: the daemon validates too, but a rejected call
        // should say "this text was malformed", not "the daemon is broken".
        ensure!(
            std::str::from_utf8(text.as_bytes()).is_ok(),
            "提交的文字不是有效的 UTF-8"
        );
        let proxy = Proxy::new(
            &self.connection,
            SERVICE,
            self.path.as_str(),
            CONTEXT_INTERFACE,
        )
        .context("输入上下文接口不可用")?;
        if let Err(error) = proxy.call::<_, _, ()>("FocusIn", &()) {
            invalidate_if_closed(&self.connection);
            return Err(error).context("输入上下文无法取得焦点");
        }
        if let Err(error) = proxy.call::<_, _, ()>("CommitString", &(text,)) {
            invalidate_if_closed(&self.connection);
            return Err(error).context("文字提交失败");
        }
        Ok(())
    }

    /// Release the context without closing the shared session-bus connection.
    pub(crate) fn destroy(&self) -> Result<()> {
        let proxy = Proxy::new(
            &self.connection,
            SERVICE,
            self.path.as_str(),
            CONTEXT_INTERFACE,
        )?;
        if proxy.call::<_, _, ()>("DestroyIC", &()).is_err() {
            invalidate_if_closed(&self.connection);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A Fcitx-compatible daemon may or may not be present. If it is, the
    /// test creates and explicitly destroys an empty context without typing.
    #[test]
    fn opening_and_destroying_an_empty_context_is_safe() {
        if let Ok(context) = Context::open() {
            assert!(context.destroy().is_ok());
        }
    }

    #[test]
    fn cached_connection_is_reused_and_closed_connections_are_replaced() {
        use std::sync::atomic::{AtomicBool, Ordering};

        let cache: ConnectionCache<AtomicBool> = ConnectionCache::default();
        let first = cache
            .get_or_try_init(
                |connection| connection.load(Ordering::SeqCst),
                || Ok(AtomicBool::new(true)),
            )
            .unwrap();
        let reused = cache
            .get_or_try_init(
                |connection| connection.load(Ordering::SeqCst),
                || Ok(AtomicBool::new(false)),
            )
            .unwrap();
        assert!(Arc::ptr_eq(&first, &reused));

        first.store(false, Ordering::SeqCst);
        let replacement = cache
            .get_or_try_init(
                |connection| connection.load(Ordering::SeqCst),
                || Ok(AtomicBool::new(true)),
            )
            .unwrap();
        assert!(!Arc::ptr_eq(&first, &replacement));
        cache.invalidate(&first);
        let still_cached = cache
            .get_or_try_init(
                |connection| connection.load(Ordering::SeqCst),
                || Ok(AtomicBool::new(false)),
            )
            .unwrap();
        assert!(Arc::ptr_eq(&replacement, &still_cached));
    }

    #[test]
    fn empty_text_is_rejected_before_any_daemon_call() {
        // A fabricated path: `commit` must refuse the empty text without
        // reaching the daemon, so no input context here ever gets created
        // and nothing can be typed into the developer's own windows.
        let context = Context {
            connection: session_connection().expect("session bus"),
            path: "/org/freedesktop/portal/inputcontext/0"
                .try_into()
                .expect("static object path"),
        };
        assert!(context.commit("").is_err());
    }
}
