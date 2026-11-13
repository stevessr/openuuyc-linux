package org.openuuyc.pico

import android.content.Context

/**
 * Vision Pro-inspired spatial workspace for the six XR panes (2 rows × 3 columns).
 * Pure model: the compositor gets [Mode.native] plus the viewing distance in metres,
 * so every ladder/clamping rule here stays unit-testable without a device.
 */
internal object PicoXrWorkspace {
    /** Workspace layout; [native] is the value the OpenXR compositor expects. */
    enum class Mode(val native: Int,val label: String) {
        AUTO(0,"自动"),
        FLAT(1,"并排"),
        WRAP(2,"环绕")
    }

    /** Viewing distance preset: [meters] is the pane distance in front of the viewer. */
    data class Distance(val meters: Float,val label: String) {
        val text: String get()="$label $meters m"
    }

    const val MIN_DISTANCE=1.2f
    const val MAX_DISTANCE=4.0f
    /** 近 / 标准 / 远, ascending so [nearestDistance] and the cycle follow the same order. */
    val distances=listOf(Distance(1.6f,"近"),Distance(2.2f,"标准"),Distance(3.0f,"远"))
    val default=distances[1]

    /** Clamp any distance into the supported range; non-finite input falls back to the default. */
    fun clampDistance(meters: Float): Float=if(meters.isFinite()) meters.coerceIn(MIN_DISTANCE,MAX_DISTANCE) else default.meters

    /** Preset closest to [meters]; ties keep the nearer preset so the ladder never skips a step. */
    fun nearestDistance(meters: Float): Distance {
        val target=clampDistance(meters)
        return distances.minByOrNull {kotlin.math.abs(it.meters-target)} ?: default
    }

    /** Next preset in the 近 → 标准 → 远 → 近 cycle, starting from the preset nearest to [meters]. */
    fun nextDistance(meters: Float): Distance=distances[(distances.indexOf(nearestDistance(meters))+1)%distances.size]

    /** Row subtitle for the current distance, e.g. "标准 2.2 m". */
    fun distanceText(meters: Float): String=nearestDistance(meters).text

    /** Native mode value → [Mode]; unknown values fall back to AUTO. */
    fun modeOf(native: Int): Mode=Mode.entries.firstOrNull {it.native==native} ?: Mode.AUTO

    /** Next layout in the 自动 → 并排 → 环绕 → 自动 cycle. */
    fun nextMode(mode: Mode): Mode=Mode.entries[(mode.ordinal+1)%Mode.entries.size]
}

/** Persisted workspace choice: SharedPreferences "xr_workspace" (mode:int, distance:float). */
internal class PicoXrWorkspaceSettings(context: Context) {
    private val prefs=context.getSharedPreferences("xr_workspace",Context.MODE_PRIVATE)
    var mode=PicoXrWorkspace.modeOf(prefs.getInt("mode",PicoXrWorkspace.Mode.AUTO.native)); private set
    var distance=PicoXrWorkspace.clampDistance(prefs.getFloat("distance",PicoXrWorkspace.default.meters)); private set
    fun update(mode: PicoXrWorkspace.Mode,distance: Float) {
        this.mode=mode;this.distance=PicoXrWorkspace.clampDistance(distance)
        prefs.edit().putInt("mode",mode.native).putFloat("distance",this.distance).apply()
    }
}
