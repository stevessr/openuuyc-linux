#!/usr/bin/env bash
# Read-only capability inventory; never installs packages or changes services.
set -u
have() { if command -v "$2" >/dev/null 2>&1; then printf '[available] %s: %s\n' "$1" "$2"; else printf '[missing] %s: %s\n' "$1" "$2"; fi; }
printf 'Linux capability inventory (availability is not end-to-end validation)\n'
printf 'Session: %s; desktop: %s\n' "${XDG_SESSION_TYPE:-unknown}" "${XDG_CURRENT_DESKTOP:-unknown}"
printf '\nDisplay management / virtual displays\n'
for c in xrandr niri wlr-randr gdbus; do have display "$c"; done
if [[ -d /sys/kernel/config/vkms ]]; then echo '[available] VKMS configfs'; else echo '[unavailable] VKMS configfs (not mounted or unsupported)'; fi
printf '\nGPU capture / encode / decode\n'
for c in vainfo nvidia-smi vulkaninfo; do have GPU "$c"; done
for node in /dev/dri/renderD*; do
    [[ -e "$node" ]] || continue
    if [[ -r "$node" && -w "$node" ]]; then printf '[accessible] %s\n' "$node"; else printf '[permission] %s\n' "$node"; fi
done
printf '\nApplication launcher\n'
have applications gio
printf '\nAudio / clipboard / input / files / terminal\n'
for c in wpctl pactl pw-cli wl-copy wl-paste xclip fcitx5 xdg-open bash; do have integration "$c"; done
if [[ -w /dev/uinput ]]; then echo '[accessible] uinput'; else echo '[unavailable] uinput (portal input may still work)'; fi
printf '\nDesktop services\n'
if command -v systemctl >/dev/null 2>&1; then
    for unit in pipewire.service wireplumber.service xdg-desktop-portal.service graphical-session.target; do
        state=$(systemctl --user is-active "$unit" 2>/dev/null) || true
        printf '[%s] %s\n' "${state:-unknown}" "$unit"
    done
fi
