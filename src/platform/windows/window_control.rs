//! Per-user Windows toplevel inventory; no injection or foreground-lock bypass.
use crate::features::window_control::WindowInfo;
use anyhow::{Context, Result, ensure};
use windows::Win32::Foundation::{HWND, LPARAM};
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GW_OWNER, GetWindow, GetWindowTextLengthW, GetWindowTextW, IsWindow,
    IsWindowVisible, SetForegroundWindow,
};
use windows::core::BOOL;

unsafe extern "system" fn enumerate(hwnd: HWND, state: LPARAM) -> BOOL {
    let items = unsafe { &mut *(state.0 as *mut Vec<WindowInfo>) };
    if items.len() >= 128
        || !unsafe { IsWindowVisible(hwnd).as_bool() }
        || unsafe { GetWindow(hwnd, GW_OWNER).is_ok() }
    {
        return BOOL(1);
    }
    let len = unsafe { GetWindowTextLengthW(hwnd) };
    if len <= 0 {
        return BOOL(1);
    }
    let mut buffer = vec![0u16; (len as usize).min(512) + 1];
    let copied = unsafe { GetWindowTextW(hwnd, &mut buffer) };
    if copied <= 0 {
        return BOOL(1);
    }
    items.push(WindowInfo {
        id: format!("win:{:x}", hwnd.0 as usize),
        title: String::from_utf16_lossy(&buffer[..copied as usize])
            .replace(['\n', '\r'], " ")
            .chars()
            .take(crate::features::window_control::MAX_WINDOW_TITLE_CHARS)
            .collect(),
        application: String::new(),
        focused: false,
    });
    BOOL(1)
}

pub(crate) fn list() -> Result<Vec<WindowInfo>> {
    let mut items = Vec::new();
    unsafe {
        EnumWindows(
            Some(enumerate),
            LPARAM(&mut items as *mut Vec<WindowInfo> as isize),
        )
        .context("enumerate top-level windows")?;
    }
    Ok(items)
}

pub(crate) fn focus(id: &str) -> Result<()> {
    let hex = id.strip_prefix("win:").context("not a Windows window ID")?;
    let handle = usize::from_str_radix(hex, 16).context("invalid window handle")?;
    ensure!(handle != 0, "invalid window handle");
    ensure!(
        list()?.iter().any(|w| w.id == id),
        "window is no longer available"
    );
    let hwnd = HWND(handle as *mut core::ffi::c_void);
    ensure!(
        unsafe { IsWindow(Some(hwnd)).as_bool() && IsWindowVisible(hwnd).as_bool() },
        "window closed"
    );
    ensure!(
        unsafe { SetForegroundWindow(hwnd).as_bool() },
        "Windows prevented foreground activation; interact locally with the target window"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_non_window_handles() {
        assert!(focus("niri:12").is_err());
        assert!(focus("win:0").is_err());
        assert!(focus("win:nothex").is_err());
    }
}
