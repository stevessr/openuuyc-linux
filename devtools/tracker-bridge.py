#!/usr/bin/env python3
"""Convert normalized JSONL tracker frames to VRChat OSC via a local UDP mapping.

Input uses Unity world space: metres, left-handed +Y up, Euler degrees Z-X-Y.
Each line: {"trackers":[{"id":1,"position":[0,1,0],"rotation":[0,0,0]}]}
A producer must supply real tracked poses; this tool performs no pose estimation.
"""
import argparse
import json
import math
import socket
import struct
import sys
import time


def osc_string(value):
    data = value.encode('ascii') + b'\0'
    return data + b'\0' * (-len(data) % 4)


def vector(value):
    if not isinstance(value, list) or len(value) != 3:
        raise ValueError('pose components must be arrays of three finite numbers')
    if any(type(v) not in (int, float) or not math.isfinite(v) or abs(v) > 1e6 for v in value):
        raise ValueError('invalid or out-of-range pose component')
    return struct.pack('>fff', *value)


def openxr_to_unity(frame):
    # Reflect the OpenXR right-handed space across Z. Quaternion reflection is
    # (-x,-y,z,w); decompose Ry * Rx * Rz to Unity's documented Z-X-Y Euler order.
    if not isinstance(frame, dict) or not isinstance(frame.get('trackers'), list):
        raise ValueError('frame must contain trackers')
    result = dict(frame)
    result['trackers'] = []
    for original in frame['trackers']:
        if not isinstance(original, dict):
            raise ValueError('invalid tracker')
        tracker = dict(original)
        if tracker.get('tracked', True) is not False:
            if 'position' in tracker:
                vector(tracker['position'])
                x, y, z = tracker['position']
                tracker['position'] = [x, y, -z]
            if 'rotation' in tracker:
                q = tracker['rotation']
                if not isinstance(q, list) or len(q) != 4 or any(
                    type(v) not in (int, float) or not math.isfinite(v) for v in q
                ):
                    raise ValueError('OpenXR rotation must be a finite XYZW quaternion')
                norm = sum(v*v for v in q)
                if not .98 <= norm <= 1.02:
                    raise ValueError('OpenXR quaternion must be normalized')
                scale = math.sqrt(norm)
                x, y, z, w = [-q[0]/scale, -q[1]/scale, q[2]/scale, q[3]/scale]
                m12 = 2*(y*z - x*w)
                pitch = math.asin(max(-1.0, min(1.0, -m12)))
                if abs(math.cos(pitch)) > 1e-6:
                    yaw = math.atan2(2*(x*z+y*w), 1-2*(x*x+y*y))
                    roll = math.atan2(2*(x*y+z*w), 1-2*(x*x+z*z))
                else:
                    # At gimbal lock choose zero roll, retaining the same rotation.
                    yaw = math.atan2(-2*(x*z-y*w), 1-2*(y*y+z*z))
                    roll = 0.0
                tracker['rotation'] = [math.degrees(pitch), math.degrees(yaw), math.degrees(roll)]
        result['trackers'].append(tracker)
    return result


def encode_frame(frame):
    if not isinstance(frame, dict) or 'trackers' not in frame or set(frame) - {'trackers', 'monotonic_ns'}:
        raise ValueError('frame must contain trackers and optional monotonic_ns')
    trackers = frame['trackers']
    if not isinstance(trackers, list) or len(trackers) > 9:
        raise ValueError('at most eight trackers and one head are supported')
    messages = []
    seen = set()
    for tracker in trackers:
        if not isinstance(tracker, dict) or set(tracker) - {'id', 'position', 'rotation', 'tracked'}:
            raise ValueError('invalid tracker fields')
        ident = tracker.get('id')
        if not (type(ident) is int and 1 <= ident <= 8 or ident == 'head'):
            raise ValueError('tracker id must be 1..8 or head')
        if ident in seen:
            raise ValueError('duplicate tracker id')
        seen.add(ident)
        tracked = tracker.get('tracked', True)
        if type(tracked) is not bool:
            raise ValueError('tracked must be boolean')
        if not tracked:
            continue  # Never substitute a zero pose for tracking loss.
        if not any(key in tracker for key in ('position', 'rotation')):
            raise ValueError('tracked device needs a pose')
        if ident != 'head' and not all(key in tracker for key in ('position', 'rotation')):
            raise ValueError('body trackers require both position and rotation')
        for component in ('position', 'rotation'):
            if component in tracker:
                message = (osc_string(f'/tracking/trackers/{ident}/{component}')
                           + osc_string(',fff') + vector(tracker[component]))
                messages.append(struct.pack('>I', len(message)) + message)
    # Immediate OSC bundle keeps one source frame in one UDP datagram.
    return b'#bundle\0' + struct.pack('>Q', 1) + b''.join(messages) if messages else None


class FrameGate:
    def __init__(self, max_age_ms=0):
        self.max_age_ns = max_age_ms * 1_000_000
        self.last_timestamp = None

    def encode(self, frame, now_ns):
        packet = encode_frame(frame)  # Validate the entire frame before sending.
        timestamp = frame.get('monotonic_ns')
        if timestamp is None:
            if self.max_age_ns:
                raise ValueError('monotonic_ns is required with --max-age-ms')
            return packet
        if type(timestamp) is not int or timestamp < 0:
            raise ValueError('monotonic_ns must be a nonnegative integer')
        if timestamp > now_ns:
            raise ValueError('monotonic_ns is in the future; producer must use the same host clock')
        if self.last_timestamp is not None and timestamp <= self.last_timestamp:
            return None
        if self.max_age_ns and now_ns - timestamp >= self.max_age_ns:
            return None
        self.last_timestamp = timestamp
        return packet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=9000, help='local OpenUUYC UDP listener')
    parser.add_argument('--max-age-ms', type=int, default=0,
                        help='discard old timestamped frames; 0 disables age filtering')
    parser.add_argument('--space', choices=['unity', 'openxr'], default='unity',
                        help='openxr uses metre positions and XYZW quaternions')
    args = parser.parse_args()
    if not 0 <= args.max_age_ms <= 10000:
        parser.error('max-age-ms must be 0..10000')
    gate = FrameGate(args.max_age_ms)
    if not 1 <= args.port <= 65535:
        parser.error('port must be 1..65535')
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        line_number = 0
        while True:
            line = sys.stdin.buffer.readline(65537)
            if not line:
                return
            line_number += 1
            if len(line) > 65536:
                parser.error(f'line {line_number}: frame exceeds 64 KiB')
            try:
                frame = json.loads(line)
                if args.space == 'openxr':
                    frame = openxr_to_unity(frame)
                packet = gate.encode(frame, time.monotonic_ns())
                if packet:
                    sock.sendto(packet, ('127.0.0.1', args.port))
            except (ValueError, OverflowError, OSError) as error:
                parser.error(f'line {line_number}: {error}')


if __name__ == '__main__':
    main()
