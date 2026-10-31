#!/usr/bin/env python3
"""Local VRChat/Proton compatibility probe: synthetic device, real game, real export.

Not a headset test and not acceptance. It answers one question: do VRChat, the
Steam-proton OpenVR path (xrizer) and the owned OpenXR runtime initialize and
render together on this machine, with a synthetic head/floor standing in for the
PICO device bridge.

It publishes a synthetic OUXD v2 + OUVR v2 snapshot (calibration, STAGE floor,
head and two hands) into the private PCVR runtime directory, creates the private
stereo export there, launches the game through the Steam client so the app's
saved launch options apply, watches the compositor export, and then stops only
the instance it started and removes only the files it created. It never claims
the host publisher lock, edits Steam configuration, or touches game data.

    python3 drivers/openxr/tests/vrchat_local_probe.py \
        --appid 438100 --runtime-dir "$XDG_RUNTIME_DIR/openuuyc-pcvr/openxr" \
        --prefix "$HOME/.steam/steam/steamapps/compatdata/438100/pfx"

Success means: the game reports its VR SDK initialized, xrizer reaches a focused
OpenXR session, and complete OUST v2 exports keep arriving. It does not prove
distinct-eye content, audio, haptics, input behaviour, or headset decode and
projection.
"""
import argparse
import json
import mmap
import os
import pathlib
import signal
import struct
import subprocess
import threading
import time


def emit(event, **fields):
    print(json.dumps({"t": time.strftime("%H:%M:%S"), "event": event, **fields},
                     ensure_ascii=False), flush=True)


def game_pids():
    """Every process whose cmdline names the game executable (wine included)."""
    found = []
    for entry in pathlib.Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            cmdline = (entry / "cmdline").read_bytes()
        except OSError:
            continue
        if b"VRChat.exe" in cmdline:
            found.append(int(entry.name))
    return found


def device_snapshot():
    """One OUCD v2 snapshot with a valid head pose, hands and a 1.6 m floor."""
    data = bytearray(568)
    data[:4] = b"OUXD"
    struct.pack_into("<I", data, 4, 2)
    struct.pack_into("<Q", data, 16, 1)
    struct.pack_into("<IIf", data, 32, 320, 256, 72.0)
    struct.pack_into("<I", data, 44, 1)
    views = []
    for x in (-0.031, 0.031):
        views.extend([x, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, -0.8, 0.8, 0.8, -0.8])
    struct.pack_into("<22f", data, 48, *views)
    data[136:140] = b"OUVR"
    struct.pack_into("<I", data, 140, 2)
    struct.pack_into("<Q", data, 144, 1)
    poses = [(0.0, 0.0, 0.0), (-0.2, -0.25, -0.3), (0.2, -0.25, -0.3),
             (-0.2, -0.25, -0.3), (0.2, -0.25, -0.3)]
    for index, (x, y, z) in enumerate(poses):
        base = 136 + 32 + index * 64
        struct.pack_into("<I", data, base, 51)
        struct.pack_into("<13f", data, base + 8, x, y, z, 0.0, 0.0, 0.0, 1.0,
                         0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    for hand in range(2):
        struct.pack_into("<II4f", data, 136 + 352 + hand * 24, 127, 0,
                         0.0, 0.0, 0.0, 0.0)
    struct.pack_into("<7f", data, 536, 0.0, -1.6, 0.0, 0.0, 0.0, 0.0, 1.0)
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--appid", type=int, default=438100)
    parser.add_argument("--runtime-dir", required=True)
    parser.add_argument("--prefix", required=True,
                        help="the game's Proton prefix (compatdata/<appid>/pfx)")
    parser.add_argument("--display", default=":0")
    parser.add_argument("--deadline", type=float, default=420.0)
    parser.add_argument("--observe", type=float, default=150.0)
    options = parser.parse_args()

    runtime = pathlib.Path(options.runtime_dir)
    prefix = pathlib.Path(options.prefix)
    device_path = runtime / "device.shm"
    stereo_path = runtime / "stereo.shm"
    lock = runtime.parent / "publisher.lock"
    log_dir = prefix / "drive_c/users/steamuser/AppData/LocalLow/VRChat/VRChat"
    xrizer = pathlib.Path.home() / ".local/state/xrizer/xrizer.txt"

    if device_path.exists() or stereo_path.exists():
        raise SystemExit(f"refusing to overwrite an existing export in {runtime}")
    if lock.exists() and subprocess.run(["flock", "-n", str(lock), "-c", "true"],
                                        capture_output=True).returncode != 0:
        raise SystemExit("a PCVR session owns publisher.lock")
    if game_pids():
        raise SystemExit("the game is already running; this probe never adopts it")

    runtime.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(runtime, 0o700)
    device_fd = os.open(device_path, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
    os.ftruncate(device_fd, 4096)
    device = mmap.mmap(device_fd, 4096)
    stereo_fd = os.open(stereo_path, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
    os.ftruncate(stereo_fd, 256 + 2 * 1024 * 1024 * 4)
    stereo = mmap.mmap(stereo_fd, 256 + 2 * 1024 * 1024 * 4)

    data = device_snapshot()
    stop = threading.Event()
    failures = []

    def writer():
        sequence = 0
        try:
            while not stop.is_set():
                sequence += 2
                struct.pack_into("<Q", device, 8, sequence | 1)
                struct.pack_into("<q", data, 24, time.monotonic_ns())
                device[:8] = data[:8]
                device[16:568] = data[16:568]
                struct.pack_into("<Q", device, 8, sequence)
                stop.wait(0.005)
        except BaseException as error:  # noqa: BLE001 - reported, never swallowed
            failures.append(repr(error))

    worker = threading.Thread(target=writer, daemon=True)
    worker.start()
    xrizer_start = xrizer.stat().st_size if xrizer.exists() else 0
    known_logs = {p.name for p in log_dir.glob("output_log_*.txt")} if log_dir.is_dir() else set()

    env = os.environ.copy()
    env["DISPLAY"] = options.display
    env.setdefault("XDG_RUNTIME_DIR", "/run/user/1000")
    env["XRT_COMPOSITOR_FORCE_XCB"] = "1"
    # The steam wrapper stays in the foreground for as long as the client runs,
    # so it must never be waited on; the game is tracked through /proc instead.
    subprocess.Popen(["steam", "-applaunch", str(options.appid)], env=env,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)
    emit("launching", appid=options.appid, display=options.display)

    started = False
    frames = 0
    last_sequence = 0
    first_at = None
    last_at = None
    pixels = None
    try:
        start = time.monotonic()
        while time.monotonic() - start < options.deadline:
            time.sleep(0.5)
            pids = game_pids()
            if pids and not started:
                started = True
                emit("game-started", pids=pids)
            elif started and not pids:
                emit("game-exited")
                break
            if first_at is not None and time.monotonic() - first_at > options.observe:
                break
            guard, = struct.unpack_from("<Q", stereo, 0)
            if not guard or guard & 1:
                continue
            magic, version, sequence, _, width, height, fmt, _ = struct.unpack_from(
                "<4sIQQIIII", stereo, 8)
            if magic != b"OUST" or version != 2 or fmt != 1 \
                    or not 64 <= width <= 1024 or not 64 <= height <= 1024 \
                    or sequence == last_sequence:
                continue
            last_sequence = sequence
            frames += 1
            last_at = time.monotonic()
            if first_at is None:
                first_at = last_at
                emit("export-first", sequence=sequence, width=width, height=height)
            if frames % 40 == 0:
                pixels = [list(stereo[256 + eye * 1024 * 1024 * 4
                                      + ((height // 2) * width + width // 2) * 4:
                                      256 + eye * 1024 * 1024 * 4
                                      + ((height // 2) * width + width // 2) * 4 + 4])
                          for eye in range(2)]
                emit("frames", count=frames, sequence=sequence,
                     center_rgba=pixels)
        game_lines = []
        if log_dir.is_dir():
            logs = sorted(log_dir.glob("output_log_*.txt"))
            fresh = [p for p in logs if p.name not in known_logs]
            if logs:
                source = fresh[0] if fresh else logs[-1]
                for line in source.read_text(errors="replace").splitlines():
                    lowered = line.lower()
                    if any(word in lowered for word in
                           ("openvr", "steamvr", "vrsdk", "xr failed",
                            "not initialized", "initializing xr")):
                        game_lines.append(line.strip())
        xrizer_lines = []
        if xrizer.exists():
            with xrizer.open(errors="replace") as handle:
                handle.seek(xrizer_start)
                xrizer_lines = [line.strip() for line in handle
                                if any(word in line.lower()
                                       for word in ("focused", "session state",
                                                    "error", "panic", "texture"))]
        emit("summary", game_started=started, export_frames=frames,
             export_sequence=last_sequence, center_rgba=pixels,
             writer_errors=failures)
        print(json.dumps({"game_log": game_lines[-20:],
                          "xrizer_log": xrizer_lines[-20:]},
                         ensure_ascii=False, indent=1))
    finally:
        stop.set()
        worker.join(timeout=2)
        if started:
            for pid in game_pids():
                try:
                    os.kill(pid, signal.SIGTERM)
                except OSError:
                    pass
            for _ in range(40):
                if not game_pids():
                    break
                time.sleep(0.5)
            if game_pids():
                wineserver = prefix.parent.parent / "Proton - Experimental/files/bin/wineserver"
                if (prefix.parent.parent / "Proton - Experimental").is_dir():
                    subprocess.run([str(wineserver), "-k"],
                                   env={**os.environ, "WINEPREFIX": str(prefix)},
                                   capture_output=True, timeout=20)
        for path in (device_path, stereo_path):
            try:
                path.unlink()
            except OSError:
                pass
        emit("cleaned", leftovers=[str(p) for p in (device_path, stereo_path)
                                   if p.exists()])


if __name__ == "__main__":
    main()
