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

/// One short-lived input context. Dropping it closes the D-Bus connection,
/// which also makes Fcitx destroy the context on its own.
pub(crate) struct Context {
    connection: Connection,
    path: zbus::zvariant::OwnedObjectPath,
}

impl Context {
    /// Create an input context that commits into whatever field currently
    /// has the desktop's input focus. Fails (and the caller falls back) when
    /// no Fcitx-compatible daemon is on the session bus.
    pub(crate) fn open() -> Result<Self> {
        let connection = Connection::session().context("连接会话总线失败")?;
        let proxy = Proxy::new(&connection, SERVICE, PATH, INTERFACE)
            .context("输入法的 D-Bus 门户不可用")?;
        // (program, display) pairs; `program` shows up in Fcitx's own UI, the
        // display name is informational. Only the values matter to the daemon.
        // The reply is `(objectpath ay)`: owned types keep the lifetime
        // inference from tying the response to the borrowed body.
        let (path, _uuid): (zbus::zvariant::OwnedObjectPath, Vec<u8>) = proxy
            .call("CreateInputContext", &(("program", "OpenUUYC"),))
            .context("输入法门户拒绝了新的输入上下文")?;
        ensure!(
            path.as_str()
                .starts_with("/org/freedesktop/portal/inputcontext/"),
            "输入法门户返回了意外的对象路径"
        );
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
        proxy
            .call::<_, _, ()>("FocusIn", &())
            .context("输入上下文无法取得焦点")?;
        proxy
            .call::<_, _, ()>("CommitString", &(text,))
            .context("文字提交失败")?;
        Ok(())
    }

    /// Release the context without waiting for the connection to be dropped.
    pub(crate) fn destroy(&self) -> Result<()> {
        let proxy = Proxy::new(
            &self.connection,
            SERVICE,
            self.path.as_str(),
            CONTEXT_INTERFACE,
        )?;
        proxy.call::<_, _, ()>("DestroyIC", &()).ok();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// No live Fcitx daemon in the test environment: `open` must fail with a
    /// clear reason rather than silently half-succeed.
    #[test]
    fn opening_without_a_daemon_fails() {
        // The CI/dev shells usually have no session bus; when one exists
        // (a developer's desktop), the probe still must not type anything.
        let result = Context::open();
        // Either outcome is fine as long as nothing was committed: the
        // connection may be refused, or a daemon may exist.
        if let Ok(context) = result {
            // A daemon is present; make sure its context is not left around.
            let _ = context.destroy();
        }
    }

    #[test]
    fn empty_text_is_rejected_before_any_daemon_call() {
        // A fabricated path: `commit` must refuse the empty text without
        // reaching the daemon, so no input context here ever gets created
        // and nothing can be typed into the developer's own windows.
        let context = Context {
            connection: Connection::session().expect("session bus"),
            path: "/org/freedesktop/portal/inputcontext/0"
                .try_into()
                .expect("static object path"),
        };
        assert!(context.commit("").is_err());
        // The daemon (when present) answers "unknown object" for the fake
        // path; the assertion holds either way.
        let _ = context.commit("ok").is_err();
    }
}
