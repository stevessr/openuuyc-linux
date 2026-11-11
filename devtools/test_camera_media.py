"""Real FFmpeg/TCP codec smoke test; no camera or kernel module required.

This replaces only device edges with a synthetic source / frame checksums.
It does not assert OpenUUYC tunnel or V4L2 device interoperability.
"""
import importlib.util
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest

spec = importlib.util.spec_from_file_location('camera_bridge', Path(__file__).with_name('camera-bridge.py'))
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


@unittest.skipUnless(shutil.which('ffmpeg'), 'FFmpeg is required')
class CameraMedia(unittest.TestCase):
    def test_tcp_h264_frames_arrive(self):
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        def args(mode):
            return bridge.parser().parse_args([
                mode, '--device', '/dev/video10', '--port', str(port),
                '--width', '320', '--height', '240', '--fps', '10'])
        send = bridge.command(args('send'))
        # Keep production encoder and transport options unchanged.
        start = send.index('-f')
        end = send.index('-i') + 2
        send[start:end] = ['-re', '-f', 'lavfi', '-i', 'testsrc2=size=320x240:rate=10']
        send[send.index('-an'):send.index('-an')] = ['-frames:v', '20']
        receive = bridge.command(args('receive'))
        # Keep production demux, decoder, scale, FPS and pixel format options.
        receive[-3:] = ['-f', 'framemd5', '-']
        with tempfile.TemporaryFile() as frames, tempfile.TemporaryFile() as errors:
            process = subprocess.Popen(receive, stdout=frames, stderr=errors)
            try:
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        self.fail('receiver exited before listening')
                    # Probe bind, not connect: a connection would consume the
                    # single-client listener and corrupt the test session.
                    with socket.socket() as probe:
                        try:
                            probe.bind(('127.0.0.1', port))
                        except OSError:
                            break
                    time.sleep(.02)
                else:
                    self.fail('receiver never listened')
                sender = subprocess.run(send, capture_output=True, timeout=15)
                self.assertEqual(sender.returncode, 0, sender.stderr.decode())
                code = process.wait(timeout=15)
                errors.seek(0)
                self.assertEqual(code, 0, errors.read().decode())
                frames.seek(0)
                rows = [line for line in frames.read().decode().splitlines()
                        if line and not line.startswith('#')]
                self.assertGreaterEqual(len(rows), 18, rows)
                self.assertGreater(len({row.rsplit(',', 1)[-1] for row in rows}), 1)
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait()


if __name__ == '__main__':
    unittest.main()
