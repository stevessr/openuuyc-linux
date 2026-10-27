package org.openuuyc.pico.uu

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.opengl.GLES20
import android.hardware.display.DisplayManager
import android.view.Display
import org.webrtc.EglBase

internal data class UuAndroidDeviceProfile(val board: String,val cpu: String,val memoryMiB: Long,val video: List<String>,val screen: String,val appVersion: String) {
    companion object {
        fun read(context: Context): UuAndroidDeviceProfile {
            val memory=ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            val cpu=if(Build.VERSION.SDK_INT>=31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
            val gpu=runCatching {
                val base=EglBase.create()
                try {base.createDummyPbufferSurface();base.makeCurrent();GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()}
                finally {base.release()}
            }.getOrDefault("").takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
            val mode=(context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)?.mode
            val screen=mode?.let { "${it.physicalWidth}x${it.physicalHeight}" }.orEmpty()
            val version=runCatching {context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull().orEmpty()
            return UuAndroidDeviceProfile("${Build.MANUFACTURER} ${Build.MODEL} (${Build.BOARD})",cpu,memory.totalMem/(1024*1024),gpu,screen,version)
        }
    }
}
