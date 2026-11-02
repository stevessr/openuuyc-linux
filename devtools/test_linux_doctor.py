import importlib.util
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch
import subprocess

spec = importlib.util.spec_from_file_location('doctor', Path(__file__).with_name('linux_doctor.py'))
doctor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(doctor)


class DoctorTests(unittest.TestCase):
    def test_stale_display_recovers_without_mutating_environment(self):
        env = {'DISPLAY': ':9', 'XDG_SESSION_TYPE': 'wayland'}
        def run(argv, env):
            return 'ok' if env['DISPLAY'] == ':0' else 'failed'
        checks = doctor.display_checks(env, [':0'], run)
        self.assertEqual(checks[-1]['status'], 'ok')
        self.assertEqual(env['DISPLAY'], ':9')

    def test_remote_display_is_not_replaced(self):
        checks = doctor.display_checks({'DISPLAY': 'localhost:10'}, [':0'], lambda *a, **k: 'failed')
        self.assertEqual(checks[-1]['status'], 'skipped')

    def test_ambiguity_and_no_session(self):
        checks = doctor.display_checks({'XDG_SESSION_TYPE': 'wayland'}, [':0', ':2'], lambda *a, **k: 'ok')
        self.assertEqual(checks[-1]['status'], 'ambiguous')
        self.assertEqual(doctor.display_checks({}, [':0'])[0]['status'], 'unavailable')

    def test_inventory_accepts_sockets_not_files_or_symlinks(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root / 'X1').touch()
            (root / 'X2').symlink_to(root / 'X0')
            with socket.socket(socket.AF_UNIX) as sock:
                sock.bind(str(root / 'X0'))
                self.assertEqual(doctor.local_candidates(root), [':0'])
                self.assertEqual(doctor.local_candidates(root, uid=-1), [])

    def test_helper_timeout_is_bounded_and_reported(self):
        with patch.object(doctor.shutil, 'which', return_value='/bin/tool'), \
             patch.object(doctor.subprocess, 'run', side_effect=subprocess.TimeoutExpired('tool', 3)):
            self.assertEqual(doctor.probe(['tool']), 'timeout')


if __name__ == '__main__':
    unittest.main()
