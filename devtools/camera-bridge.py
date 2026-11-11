#!/usr/bin/env python3
"""Linux camera bridge over an explicitly configured OpenUUYC TCP mapping.

Only loopback sockets are used. No driver installation, camera discovery,
recording, background services, or automatic reconnect is performed.
"""
import argparse
import os
import re
import shlex
import shutil
import stat
import struct
import fcntl


def validate_capabilities(data, mode):
    """Decode Linux videodev2.h's fixed 104-byte v4l2_capability ABI."""
    if len(data) != 104:
        raise ValueError('invalid V4L2 capability response')
    capabilities, device_caps = struct.unpack_from('=II', data, 84)
    caps = device_caps if capabilities & 0x80000000 else capabilities
    required = 1 if mode in ('send', 'formats') else 2  # single-plane CAPTURE / OUTPUT
    if not caps & required:
        purpose = 'capture' if mode in ('send', 'formats') else 'output (use a free v4l2loopback node)'
        raise ValueError(f'device does not support V4L2 {purpose}')


def check_device(path, mode):
    # Open without starting streaming or allocating capture buffers.
    fd = os.open(path, os.O_RDWR | os.O_NONBLOCK | os.O_CLOEXEC)
    try:
        if not stat.S_ISCHR(os.fstat(fd).st_mode):
            raise ValueError('device is not a character device')
        data = bytearray(104)
        fcntl.ioctl(fd, 0x80685600, data, True)  # VIDIOC_QUERYCAP
        validate_capabilities(data, mode)
    finally:
        os.close(fd)


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=['send', 'receive', 'formats'])
    p.add_argument('--device', required=True, help='/dev/videoN source or loopback sink')
    p.add_argument('--port', type=int, default=19001)
    p.add_argument('--width', type=int, default=1280)
    p.add_argument('--height', type=int, default=720)
    p.add_argument('--fps', type=int, default=30)
    p.add_argument('--input-format', choices=['auto', 'mjpeg', 'yuyv422', 'nv12'], default='auto',
                   help='source format for send mode; use formats to inspect device support')
    p.add_argument('--dry-run', action='store_true', help='print only; never open camera')
    return p


def command(args):
    if not re.fullmatch(r'/dev/video[0-9]+', args.device):
        raise ValueError('device must be /dev/videoN')
    if not 1 <= args.port <= 65535:
        raise ValueError('port must be 1..65535')
    if not (64 <= args.width <= 3840 and 64 <= args.height <= 2160
            and args.width % 2 == args.height % 2 == 0 and 1 <= args.fps <= 60):
        raise ValueError('use even dimensions within 64..3840 x 64..2160 and 1..60 FPS')
    base = ['ffmpeg', '-hide_banner', '-nostdin', '-loglevel', 'warning']
    if args.mode != 'send' and args.input_format != 'auto':
        raise ValueError('input-format applies only to send mode')
    if args.mode == 'formats':
        # Enumerate only; no output URL, encoder or capture loop is started.
        return ['ffmpeg', '-hide_banner', '-nostdin', '-loglevel', 'info',
                '-f', 'v4l2', '-list_formats', 'all', '-i', args.device]
    address = f'tcp://127.0.0.1:{args.port}'
    capture_format = [] if args.input_format == 'auto' else ['-input_format', args.input_format]
    if args.mode == 'send':
        return base + capture_format + ['-f', 'v4l2', '-framerate', str(args.fps), '-video_size',
                       f'{args.width}x{args.height}', '-i', args.device,
                       '-an', '-c:v', 'libx264', '-preset', 'ultrafast',
                       '-tune', 'zerolatency', '-pix_fmt', 'yuv420p',
                       '-b:v', '4M', '-maxrate', '4M', '-bufsize', '1M',
                       '-g', str(args.fps), '-f', 'mpegts', '-flush_packets', '1',
                       address + '?tcp_nodelay=1&timeout=5000000']
    if args.mode != 'receive':
        raise ValueError('unknown mode')
    return base + ['-probesize', '32768', '-analyzeduration', '0',
                   '-f', 'mpegts', '-i', address + '?listen=1&timeout=5000000',
                   '-map', '0:v:0', '-an', '-vf',
                   f'scale={args.width}:{args.height},fps={args.fps}',
                   '-c:v', 'rawvideo', '-pix_fmt', 'yuyv422', '-f', 'v4l2', args.device]


def main():
    p = parser()
    args = p.parse_args()
    try:
        cmd = command(args)
        if args.dry_run:
            print(shlex.join(cmd))
            return
        executable = shutil.which('ffmpeg')
        if not executable:
            raise ValueError('install FFmpeg with V4L2 and libx264 support')
        check_device(args.device, args.mode)
        # exec preserves signals and FFmpeg exit status; no orphan capture process.
        os.execv(executable, cmd)
    except (OSError, ValueError) as exc:
        p.error(str(exc))


if __name__ == '__main__':
    main()
