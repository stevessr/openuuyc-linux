package org.openuuyc.pico.uu

/** Browsing installed apps is distinct from permission to execute programs. */
internal object UuQuickAppsPolicy {
    fun reading(op: String)=op=="list" || op=="vr-runtime" || op=="vr-applications"
    fun permitted(op: String,catalog: Boolean,launch: Boolean)=if(reading(op)) catalog else launch
}
