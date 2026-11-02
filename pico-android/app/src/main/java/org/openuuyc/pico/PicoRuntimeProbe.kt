package org.openuuyc.pico

import android.content.Context

/** Loader-only capability inventory; does not create a session or request tracking. */
internal object PicoRuntimeProbe {
    init { System.loadLibrary("openuuyc_pico") }
    external fun extensions(context: Context): String
}
