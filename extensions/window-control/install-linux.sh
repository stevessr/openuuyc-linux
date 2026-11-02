#!/usr/bin/env sh
# Install/remove the *per-user* OpenUUYC private window-control capability.
# It never grants root, installs a daemon, or changes compositor configuration.
set -eu
case "${1:-}" in install|remove) ;; *) echo "usage: sh install-linux.sh install|remove" >&2; exit 2;; esac
if [ "$(id -u)" -eq 0 ]; then
  echo "Run this as the desktop user, not root." >&2
  exit 1
fi
base="${XDG_DATA_HOME:-${HOME:?}/.local/share}"
case "$base" in /*) ;; *) echo "XDG_DATA_HOME must be absolute" >&2; exit 1;; esac
marker="$base/OpenUUYC/private-window-control.enabled"
if [ "$1" = install ]; then
  mkdir -p "$(dirname "$marker")"
  umask 077
  : > "$marker"
  chmod 600 "$marker"
  printf 'Enabled for this user. Restart OpenUUYC in the same niri session.\n'
else
  rm -f -- "$marker"
  printf 'Disabled. Existing sessions must reconnect.\n'
fi
