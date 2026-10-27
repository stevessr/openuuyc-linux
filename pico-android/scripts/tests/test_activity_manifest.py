"""Guard against PICO suppressing ordinary Android login views as VR content."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
MANIFEST = pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/AndroidManifest.xml'


class ActivityManifestTest(unittest.TestCase):
    def test_webrtc_network_monitor_has_required_permission(self):
        root = ET.parse(MANIFEST).getroot()
        permissions = {p.get(ANDROID + 'name') for p in root.findall('uses-permission')}
        self.assertIn('android.permission.INTERNET', permissions)
        self.assertIn('android.permission.ACCESS_NETWORK_STATE', permissions)

    def test_default_viewer_is_resizable_nonimmersive(self):
        root = ET.parse(MANIFEST).getroot()
        viewer = next(a for a in root.findall('./application/activity') if a.get(ANDROID + 'name') == '.PanelViewerActivity')
        self.assertEqual('false', viewer.get(ANDROID + 'exported'))
        self.assertEqual('true', viewer.get(ANDROID + 'resizeableActivity'))
        self.assertFalse(viewer.findall('intent-filter'))
        self.assertFalse(viewer.findall('meta-data'))

    def test_launcher_is_panel_and_xr_is_private_immersive_activity(self):
        root = ET.parse(MANIFEST).getroot()
        activities = {a.get(ANDROID + 'name'): a for a in root.findall('./application/activity')}
        launcher = activities['.PicoActivity']
        xr = activities['.XrActivity']
        self.assertIsNotNone(launcher.find("./intent-filter/category[@" + ANDROID + "name='android.intent.category.LAUNCHER']"))
        self.assertNotIn('org.khronos.openxr.intent.category.IMMERSIVE_HMD',
                         [c.get(ANDROID + 'name') for c in launcher.findall('./intent-filter/category')])
        self.assertFalse(any(m.get(ANDROID + 'name') == 'pvr.app.type' and
                             m.get(ANDROID + 'value') == 'vr' for m in launcher.findall('meta-data')))
        self.assertEqual(xr.get(ANDROID + 'exported'), 'false')
        self.assertTrue(any(m.get(ANDROID + 'name') == 'pvr.app.type' and
                            m.get(ANDROID + 'value') == 'vr' for m in xr.findall('meta-data')))


if __name__ == '__main__':
    unittest.main()
