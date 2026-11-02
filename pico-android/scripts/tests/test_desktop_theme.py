"""Keep native Android presentation aligned with the Linux source of truth."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]

class DesktopThemeTest(unittest.TestCase):
    def test_palette_and_layout_tokens_match_linux(self):
        rust = (ROOT / 'src/ui/theme.rs').read_text()
        kotlin = (ROOT / 'pico-android/app/src/main/java/org/openuuyc/pico/PicoTheme.kt').read_text()
        rust_colors = dict(re.findall(r'pub const (\w+): Color32 = Color32::from_rgb\(([^)]*)\)', rust))
        android_colors = dict(re.findall(r'val (\w+) = Color.rgb\(([^)]*)\)', kotlin))
        self.assertGreaterEqual(len(android_colors), 12)
        for name, rgb in android_colors.items():
            self.assertEqual(rgb, rust_colors[name], name)
        for name in ['CONTROL_RADIUS', 'PANEL_RADIUS', 'SIDEBAR_WIDTH', 'AUTH_QR_SIZE']:
            expected = float(re.search(r'pub const ' + name + r': \w+ = ([\d.]+)', rust)[1])
            actual = int(re.search(r'const val ' + name + r' = (\d+)', kotlin)[1])
            self.assertEqual(expected, actual, name)

    def test_brand_is_exact_desktop_artwork(self):
        self.assertEqual((ROOT / 'assets/icon-256.png').read_bytes(),
                         (ROOT / 'pico-android/app/src/main/res/drawable-nodpi/openuuyc_brand.png').read_bytes())

if __name__ == '__main__':
    unittest.main()
