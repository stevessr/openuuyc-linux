import importlib.util
import json
import math
import random
from pathlib import Path
import socket
import struct
import subprocess
import sys
import unittest

path = Path(__file__).with_name('tracker-bridge.py')
spec = importlib.util.spec_from_file_location('tracker_bridge', path)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)

class Trackers(unittest.TestCase):
    def frame(self):
        return {'trackers': [{'id': 1, 'position': [1, 2, 3], 'rotation': [4, 5, 6]}]}

    def test_wire_values(self):
        packet = bridge.encode_frame(self.frame())
        self.assertEqual(packet[:16], b'#bundle\0' + struct.pack('>Q', 1))
        offset = 16
        for component, values in [('position', (1, 2, 3)), ('rotation', (4, 5, 6))]:
            size = struct.unpack_from('>I', packet, offset)[0]
            offset += 4
            message = packet[offset:offset+size]
            self.assertEqual(message, bridge.osc_string('/tracking/trackers/1/' + component)
                             + bridge.osc_string(',fff') + struct.pack('>fff', *values))
            offset += size
        self.assertEqual(offset, len(packet))

    def test_tracking_loss_sends_no_origin(self):
        self.assertIsNone(bridge.encode_frame({'trackers': [{'id': 1, 'tracked': False}]}))

    def test_reject_invalid_pose(self):
        for value in [float('nan'), float('inf'), True, 1e30]:
            frame = self.frame()
            frame['trackers'][0]['position'][0] = value
            with self.assertRaises(ValueError):
                bridge.encode_frame(frame)
        frame = self.frame()
        frame['trackers'] *= 2
        with self.assertRaises(ValueError):
            bridge.encode_frame(frame)

    def test_frame_age_and_order(self):
        gate = bridge.FrameGate(100)
        frame = self.frame()
        with self.assertRaises(ValueError):
            gate.encode(frame, 1_000_000_000)
        frame['monotonic_ns'] = 900_000_000
        self.assertIsNone(gate.encode(frame, 1_000_000_000))
        frame['monotonic_ns'] = 950_000_000
        self.assertIsNotNone(gate.encode(frame, 1_000_000_000))
        self.assertIsNone(gate.encode(frame, 1_000_000_000))
        frame['monotonic_ns'] = 940_000_000
        self.assertIsNone(gate.encode(frame, 1_000_000_000))
        frame['monotonic_ns'] = 1_000_000_001
        with self.assertRaises(ValueError):
            gate.encode(frame, 1_000_000_000)
        frame['monotonic_ns'] = 990_000_000
        self.assertIsNotNone(gate.encode(frame, 1_000_000_000))

    def test_untimestamped_compatibility(self):
        self.assertEqual(bridge.FrameGate().encode(self.frame(), 0), bridge.encode_frame(self.frame()))

    def test_openxr_coordinate_reflection(self):
        frame = {'trackers': [{'id': 1, 'position': [1,2,-3], 'rotation': [0,0,0,1]}]}
        converted = bridge.openxr_to_unity(frame)
        self.assertEqual(converted['trackers'][0]['position'], [1,2,3])
        self.assertEqual(converted['trackers'][0]['rotation'], [0,0,0])
        self.assertEqual(frame['trackers'][0]['position'], [1,2,-3])
        for axis, expected in [(0, [-90,0,0]), (1, [0,-90,0]), (2, [0,0,90])]:
            q = [0,0,0,math.sqrt(.5)]
            q[axis] = math.sqrt(.5)
            frame['trackers'][0]['rotation'] = q
            actual = bridge.openxr_to_unity(frame)['trackers'][0]['rotation']
            for a, b in zip(actual, expected):
                self.assertAlmostEqual(a, b, places=5)

    def test_compound_openxr_rotations_reconstruct_reflected_matrix(self):
        rng = random.Random(42)
        for _ in range(100):
            q = [rng.uniform(-1, 1) for _ in range(4)]
            norm = math.sqrt(sum(v*v for v in q))
            x,y,z,w = q = [v/norm for v in q]
            source = [[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)],
                      [2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)],
                      [2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]]
            angles = bridge.openxr_to_unity({'trackers':[{'id':'head','rotation':q}]})['trackers'][0]['rotation']
            a,b,c = map(math.radians, angles)
            rx = [[1,0,0],[0,math.cos(a),-math.sin(a)],[0,math.sin(a),math.cos(a)]]
            ry = [[math.cos(b),0,math.sin(b)],[0,1,0],[-math.sin(b),0,math.cos(b)]]
            rz = [[math.cos(c),-math.sin(c),0],[math.sin(c),math.cos(c),0],[0,0,1]]
            def multiply(left, right):
                return [[sum(left[i][k]*right[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
            actual = multiply(multiply(ry,rx),rz)
            signs = [1,1,-1]
            for i in range(3):
                for j in range(3):
                    self.assertAlmostEqual(actual[i][j], source[i][j]*signs[i]*signs[j], places=6)

    def test_openxr_rejects_invalid_quaternions_and_skips_lost_pose(self):
        for q in [[0,0,0,0], [0,0,0,2], [0,0,float('nan'),1], [0,0,1]]:
            with self.assertRaises(ValueError):
                bridge.openxr_to_unity({'trackers': [{'id':1, 'rotation':q}]})
        frame = {'trackers': [{'id':1, 'tracked':False}]}
        self.assertIsNone(bridge.encode_frame(bridge.openxr_to_unity(frame)))

    def test_cli_delivers_one_udp_bundle(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver:
            receiver.bind(('127.0.0.1', 0))
            receiver.settimeout(2)
            result = subprocess.run([sys.executable, str(path), '--port', str(receiver.getsockname()[1])],
                                    input=json.dumps(self.frame()).encode()+b'\n', capture_output=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(receiver.recv(4096), bridge.encode_frame(self.frame()))

if __name__ == '__main__':
    unittest.main()
