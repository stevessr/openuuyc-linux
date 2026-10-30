#!/usr/bin/env bash
# Read-only capability checks; never installs packages or changes services.
set -eu
exec python3 "$(dirname -- "${BASH_SOURCE[0]}")/linux_doctor.py" "$@"
