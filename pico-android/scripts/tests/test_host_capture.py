"""Host foundation is private, opt-in and not falsely advertised as UU online."""
import pathlib
import unittest
import xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[2]
A='{http://schemas.android.com/apk/res/android}'
class HostCaptureTest(unittest.TestCase):
    def test_private_consent_and_foreground_capture(self):
        for module in ('app','spatial'):
            root=ET.parse(ROOT/module/'src/main/AndroidManifest.xml').getroot()
            permissions={p.get(A+'name') for p in root.findall('uses-permission')}
            self.assertIn('android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION',permissions)
            app=root.find('application')
            service=next(s for s in app.findall('service') if s.get(A+'name','').endswith('UuHostCaptureService'))
            self.assertEqual(service.get(A+'exported'),'false')
            self.assertEqual(service.get(A+'foregroundServiceType'),'mediaProjection')
            activity=next(s for s in app.findall('activity') if s.get(A+'name','').endswith('UuHostCaptureActivity'))
            self.assertEqual(activity.get(A+'exported'),'false')
    def test_no_silent_restart_or_grant_persistence(self):
        source=(ROOT/'app/src/main/java/org/openuuyc/pico/uu/host/UuHostCaptureService.kt').read_text()
        self.assertIn('START_NOT_STICKY',source)
        self.assertNotIn('START_STICKY',source)
        self.assertNotIn('SharedPreferences',source)
        self.assertNotIn('WebSocket',source)
        self.assertIn('if(grant==null)',source)
