package org.openuuyc.pico

/** Menu-level debug input. It never creates controller poses or XR calibration data. */
internal object PicoXrTestInput {
    const val ACTION = "org.openuuyc.pico.DEBUG_XR_TEST_INPUT"

    sealed interface Command {
        data class QuickMenu(val hand: Int) : Command
        data class Navigate(val direction: Int, val confirm: Boolean) : Command
        data class MenuClick(val u: Float, val v: Float) : Command
    }

    fun parse(
        action: String?,
        name: String?,
        hand: Int? = null,
        direction: Int? = null,
        confirm: Boolean? = null,
        u: Float? = null,
        v: Float? = null
    ): Command? {
        if (action != ACTION) return null
        return when (name) {
            "quick_menu" -> hand?.takeIf { it in 0..1 }?.let(Command::QuickMenu)
            "navigate" -> {
                val value = direction ?: return null
                val confirmed = confirm ?: return null
                val allowed = value in -1..1 || value == 9 || value in 100..107
                if (!allowed || confirmed && value != 0) null else Command.Navigate(value, confirmed)
            }
            "menu_click" -> {
                val x = u ?: return null
                val y = v ?: return null
                if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) null
                else Command.MenuClick(x, y)
            }
            else -> null
        }
    }
}
