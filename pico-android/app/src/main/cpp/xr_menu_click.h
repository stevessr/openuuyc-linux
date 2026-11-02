#pragma once
// A menu can move under a held ray, but that must never synthesize a new click.
inline bool xr_menu_click(int pane, bool down, bool changed_since_sync) {
    return pane == 6 && down && changed_since_sync;
}
