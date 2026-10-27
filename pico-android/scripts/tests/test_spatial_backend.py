"""Guard the opt-in OS 6 backend without resolving its SDK on OS 5 builds."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parents[2]
A='{http://schemas.android.com/apk/res/android}'
class SpatialBackendTest(unittest.TestCase):
    def test_os6_is_opt_in_and_does_not_raise_legacy_minimum(self):
        settings=(ROOT/'settings.gradle.kts').read_text()
        self.assertIn('if (providers.gradleProperty("picoSpatial").orNull == "true") include(":spatial")',settings)
        self.assertIn('minSdk = 29',(ROOT/'app/build.gradle.kts').read_text())
        self.assertIn('minSdk = 35',(ROOT/'spatial/build.gradle.kts').read_text())
    def test_sdk_versions_and_shared_sources_are_explicit(self):
        build=(ROOT/'spatial/build.gradle.kts').read_text()
        self.assertIn('com.pico.spatial:bom:6.1.9',build)
        self.assertIn('from("../app/src/main/java")',build)
        self.assertIn('include("org/openuuyc/pico/uu/**")',build)
        self.assertNotIn('externalNativeBuild',build)
    def test_spatial_manifest_uses_official_container_not_manual_xr_activity(self):
        root=ET.parse(ROOT/'spatial/src/main/AndroidManifest.xml').getroot()
        app=root.find('application')
        self.assertTrue(app.attrib[A+'name'].endswith('OpenUuSpatialApplication'))
        activities=[a for a in app.findall('activity') if a.attrib[A+'name'].endswith('OpenUuSpatialLaunchActivity')]
        self.assertEqual(len(activities),1)
        names={x.attrib[A+'name'] for x in activities[0].findall('meta-data')}
        self.assertIn('pico.spatial.windowcontainer.id',names)
        self.assertFalse(any('XrActivity' in a.attrib[A+'name'] for a in activities))
    def test_framework_shell_has_independent_windows_and_popup_state(self):
        text=(ROOT/'spatial/src/main/java/org/openuuyc/pico/spatial/SpatialWorkspace.kt').read_text()
        for call in ['DefaultWindowContainer {','Subwindow {','Toolbar {','SpatialPopup(','key(window.id)']:
            self.assertIn(call,text)
        self.assertNotIn('nativeRun(',text)

if __name__=='__main__': unittest.main()
