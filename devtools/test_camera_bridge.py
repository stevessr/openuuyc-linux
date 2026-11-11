import importlib.util
from pathlib import Path
import unittest
import struct
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('camera_bridge', Path(__file__).with_name('camera-bridge.py'))
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)

class CameraCommands(unittest.TestCase):
    def args(self, mode='send', *extra):
        return bridge.parser().parse_args([mode, '--device', '/dev/video10', *extra])

    def test_sender(self):
        cmd = bridge.command(self.args())
        self.assertIn('libx264', cmd)
        self.assertIn('zerolatency', cmd)
        self.assertEqual(cmd[-1], 'tcp://127.0.0.1:19001?tcp_nodelay=1&timeout=5000000')

    def test_explicit_capture_format_is_an_input_option(self):
        cmd = bridge.command(self.args('send', '--input-format', 'mjpeg'))
        self.assertEqual(cmd[cmd.index('-input_format') + 1], 'mjpeg')
        self.assertLess(cmd.index('-input_format'), cmd.index('-i'))
        with self.assertRaises(ValueError):
            bridge.command(self.args('receive', '--input-format', 'mjpeg'))

    def test_format_enumeration_has_no_output_or_encoder(self):
        cmd = bridge.command(self.args('formats'))
        self.assertIn('-list_formats', cmd)
        self.assertEqual(cmd[-2:], ['-i', '/dev/video10'])
        self.assertNotIn('-c:v', cmd)
        self.assertFalse(any(value.startswith('tcp:') for value in cmd))

    def test_receiver(self):
        cmd = bridge.command(self.args('receive'))
        self.assertIn('tcp://127.0.0.1:19001?listen=1&timeout=5000000', cmd)
        self.assertEqual(cmd[-3:], ['-f', 'v4l2', '/dev/video10'])
        self.assertIn('yuyv422', cmd)

    def test_reject_bad_settings(self):
        for flag, value in [('--port','0'), ('--port','65536'), ('--width','1279'),
                            ('--height','4096'), ('--fps','0'), ('--device','/tmp/output')]:
            with self.subTest(flag=flag, value=value), self.assertRaises(ValueError):
                bridge.command(self.args('send', flag, value))

class DeviceCapabilities(unittest.TestCase):
    def data(self, caps, device_caps=0):
        data = bytearray(104)
        struct.pack_into('=II', data, 84, caps, device_caps)
        return data

    def test_capture_and_output_are_not_interchangeable(self):
        bridge.validate_capabilities(self.data(1), 'send')
        bridge.validate_capabilities(self.data(2), 'receive')
        for caps, mode in [(1, 'receive'), (2, 'send'), (0x1000, 'send')]:
            with self.assertRaises(ValueError):
                bridge.validate_capabilities(self.data(caps), mode)

    def test_node_caps_override_physical_device_caps(self):
        # A metadata node can belong to a camera, but cannot supply video.
        with self.assertRaises(ValueError):
            bridge.validate_capabilities(self.data(0x80000001, 0), 'send')
        bridge.validate_capabilities(self.data(0x80000000, 2), 'receive')

    def test_truncated_response(self):
        with self.assertRaises(ValueError):
            bridge.validate_capabilities(b'', 'send')

    def test_fd_is_closed_on_ioctl_error(self):
        with patch.object(bridge.os, 'open', return_value=42), \
             patch.object(bridge.os, 'fstat') as fstat, \
             patch.object(bridge.stat, 'S_ISCHR', return_value=True), \
             patch.object(bridge.fcntl, 'ioctl', side_effect=OSError('not V4L2')), \
             patch.object(bridge.os, 'close') as close:
            with self.assertRaises(OSError):
                bridge.check_device('/dev/video0', 'send')
            close.assert_called_once_with(42)

if __name__ == '__main__':
    unittest.main()
