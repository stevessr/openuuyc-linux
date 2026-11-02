package org.openuuyc.pico.uu

/** What tapping “附加 SteamVR 场景” must do right now. Pure so the three
 * outcomes stay testable without Android, a host or a headset. */
internal object UuPcvrMirror {
    /** The host reports this backend once the mirror is the active producer. */
    const val BACKEND="steamvr-mirror"
    /** Host-reported attach liveness while the helper still encodes. */
    const val RUNNING="running"
    enum class Action {
        /** No session yet, or the helper already ended: send a real request. */
        REQUEST,
        /** The same live mirror runs; a request would only confirm it, and
         * re-requesting would drop this side's working decoder. */
        CONFIRM,
        /** Another backend owns the session; it must be stopped first. */
        WRONG_BACKEND,
    }
    fun action(active: Boolean,matchesMirror: Boolean,mirror: String): Action = when {
        !active -> Action.REQUEST
        !matchesMirror -> Action.WRONG_BACKEND
        // "running" is a live helper; "ended" is exactly the retry case. Any
        // other value (an older host without the field) re-requests instead
        // of claiming a mirror that may no longer exist.
        mirror==RUNNING -> Action.CONFIRM
        else -> Action.REQUEST
    }
}
