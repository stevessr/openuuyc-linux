#!/usr/bin/env python3
"""Read-only desktop checks. No login data, bus addresses or auth cookies exported."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess


def probe(argv, env=None):
    """Bound every helper; availability alone is not a functioning backend."""
    if not shutil.which(argv[0]):
        return 'missing'
    try:
        result = subprocess.run(argv, env=env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=3, check=False)
        return 'ok' if result.returncode == 0 else 'failed'
    except subprocess.TimeoutExpired:
        return 'timeout'
    except OSError:
        return 'failed'


def local_candidates(directory=Path('/tmp/.X11-unix'), uid=None):
    uid = os.geteuid() if uid is None else uid
    found = []
    try:
        for path in directory.iterdir():
            match = re.fullmatch(r'X([0-9]+)', path.name)
            if not match or int(match[1]) > 59535:
                continue
            metadata = path.lstat()
            if stat.S_ISSOCK(metadata.st_mode) and metadata.st_uid == uid:
                found.append(':' + str(int(match[1])))
    except OSError:
        pass
    return sorted(set(found))[:16]


def display_checks(env, candidates, run=probe):
    requested = env.get('DISPLAY', '')
    checks = []
    if requested:
        state = run(['xdpyinfo'], env=env)
        checks.append(dict(name='X11 current DISPLAY', status=state))
        if state == 'ok':
            return checks
        if not requested.startswith((':', 'unix:', 'unix/:')):
            checks.append(dict(name='X11 fallback', status='skipped',
                               hint='远程/SSH DISPLAY 不自动切换到本机桌面'))
            return checks
    elif not (env.get('WAYLAND_DISPLAY') or env.get('XDG_SESSION_TYPE') in ('wayland', 'x11')):
        return [dict(name='display session', status='unavailable', hint='请在已登录的图形会话运行')]
    available = []
    for display in candidates:
        state = run(['xdpyinfo'], env=dict(env, DISPLAY=display))
        checks.append(dict(name='X11 candidate ' + display, status=state))
        if state == 'ok':
            available.append(display)
    state = 'ok' if len(available) == 1 else 'ambiguous' if available else 'unavailable'
    checks.append(dict(name='X11 fallback', status=state,
                       hint=('自动恢复到 ' + available[0]) if len(available) == 1 else
                       '多个会话可连接，请明确指定 DISPLAY' if available else
                       '检查图形会话；Wayland 可继续尝试原生采集/屏幕共享门户'))
    return checks


def inventory(env=None):
    env = dict(os.environ if env is None else env)
    checks = display_checks(env, local_candidates())
    for name, argv in [('Wayland connection', ['wayland-info']),
                       ('PipeWire connection', ['pw-cli', 'info', '0']),
                       ('audio server', ['pactl', 'info'])]:
        checks.append(dict(name=name, status=probe(argv, env)))
    for command in ('niri', 'wlr-randr', 'gdbus', 'vainfo', 'nvidia-smi',
                    'vulkaninfo', 'wl-copy', 'wl-paste', 'xclip', 'fcitx5', 'gio', 'xdg-open'):
        checks.append(dict(name='tool ' + command,
                           status='installed' if shutil.which(command) else 'missing'))
    for path in [Path('/dev/uinput'), *sorted(Path('/dev/dri').glob('renderD*'))]:
        checks.append(dict(name=str(path), status='accessible' if os.access(path, os.R_OK | os.W_OK)
                           else 'permission' if path.exists() else 'missing'))
    for unit in ('pipewire.service', 'wireplumber.service', 'xdg-desktop-portal.service',
                 'graphical-session.target'):
        checks.append(dict(name=unit, status=probe(['systemctl', '--user', 'is-active', '--quiet', unit], env)))
    return dict(schema=1, session=env.get('XDG_SESSION_TYPE', 'unknown'),
                checks=checks, note='只读检查；不启动服务、不申请屏幕共享、不验证真实串流或编码性能。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json', action='store_true', help='输出可用于诊断包的 JSON')
    args = parser.parse_args()
    report = inventory()
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print('Linux capability checks — session:', report['session'])
        for check in report['checks']:
            print('[{status}] {name}'.format(**check), check.get('hint', ''))
        print(report['note'])


if __name__ == '__main__':
    main()
