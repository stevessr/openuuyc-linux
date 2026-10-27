package org.openuuyc.pico.spatial

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

/** Entry/lifecycle follows PICO's Spatial UI reference app, not the legacy JNI loop. */
class OpenUuSpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::openUuSpatialApp)
    }
}
class OpenUuSpatialLaunchActivity : SpatialLaunchActivity() {
    override fun onResume() {super.onResume();org.openuuyc.pico.uu.UuDirectSession.setApplicationFocused(true)}
    override fun onPause() {org.openuuyc.pico.uu.UuDirectSession.setApplicationFocused(false);super.onPause()}
}
