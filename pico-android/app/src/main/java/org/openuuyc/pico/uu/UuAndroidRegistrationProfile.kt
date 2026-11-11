package org.openuuyc.pico.uu

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.roundToInt

/** Values match E9/c, D0/p and L9/s in official Android 4.46.0.
 * screen_size is physical size expressed at 160 pixels/inch, not diagonal inches.
 */
internal data class UuAndroidRegistrationProfile(
    val sdk: Int, val fingerprint: String, val brand: String, val manufacturer: String,
    val model: String, val product: String, val rom: String, val abis: List<String>,
    val resolution: String, val screenSize: String, val dpi: Int
) {
    companion object {
        @Suppress("DEPRECATION")
        fun read(context: Context): UuAndroidRegistrationProfile {
            val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            val metrics = DisplayMetrics().also(display::getRealMetrics)
            val density = context.resources.displayMetrics.densityDpi
            return UuAndroidRegistrationProfile(
                Build.VERSION.SDK_INT, Build.FINGERPRINT, Build.BRAND, Build.MANUFACTURER,
                Build.MODEL, Build.PRODUCT, Build.DISPLAY, Build.SUPPORTED_ABIS.toList(),
                landscapeSize(metrics.widthPixels, metrics.heightPixels),
                landscapeSize(physicalPixels(metrics.widthPixels, metrics.xdpi), physicalPixels(metrics.heightPixels, metrics.ydpi)),
                density
            )
        }

        internal fun landscapeSize(width: Int, height: Int): String = "${maxOf(width, height)}x${minOf(width, height)}"
        internal fun physicalPixels(pixels: Int, dpi: Float): Int =
            if (dpi.isFinite() && dpi > 0) (pixels * 160f / dpi).roundToInt() else pixels
    }
}
