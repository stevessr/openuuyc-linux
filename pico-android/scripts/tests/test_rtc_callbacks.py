"""Guard the specific native-callback/session-monitor ANR regression."""
import pathlib,re,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class RtcCallbackTest(unittest.TestCase):
    def setUp(self): self.code=(ROOT/'app/src/main/java/org/openuuyc/pico/uu/UuDirectSession.kt').read_text()
    def test_native_observers_dispatch_before_session_monitor(self):
        for name in ['onIceCandidate','onIceConnectionChange','onDataChannel','onAddTrack','onTrack','onStateChange']:
            self.assertRegex(self.code,r'override fun '+name+r'\([^\n]*\)\s*\{\s*postRtc\s*\{',name)
    def test_channel_buffer_is_copied_before_deferred_processing(self):
        body=self.code.split('override fun onMessage(buffer: DataChannel.Buffer)',1)[1]
        self.assertLess(body.index('data.get(bytes)'),body.index('postRtc {'))
        self.assertLess(body.index('postRtc {'),body.index('synchronized(this@UuDirectSession)'))
    def test_panel_surface_callbacks_do_not_wait_for_egl_release_under_monitor(self):
        body=self.code.split('fun attachMainSurface',1)[1].split('fun detachWindowSurface',1)[0]
        self.assertNotIn('releaseEglSurface',body)
        self.assertIn('rendererExecutor.execute',body)
if __name__=='__main__':unittest.main()
