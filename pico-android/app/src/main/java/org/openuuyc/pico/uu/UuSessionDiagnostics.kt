package org.openuuyc.pico.uu

import android.content.Context

/** Bounded local-only trace of app-generated protocol stages and numeric stats.
 * Never pass request/response bodies, SDP, candidates, tokens, or user identifiers.
 */
internal class UuSessionDiagnostics(context: Context) {
    private val prefs = context.getSharedPreferences("uu_stream_diagnostics", Context.MODE_PRIVATE)
    @Synchronized fun record(event: String) {
        val rows = prefs.getString("stages", "").orEmpty().lines().filter { it.isNotBlank() }.takeLast(63)
        val safe = event.replace('\n', ' ').take(512)
        prefs.edit().putString("stages", (rows + "${System.currentTimeMillis()} $safe").joinToString("\n")).apply()
    }
}
