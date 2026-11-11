package org.openuuyc.pico

import org.json.JSONObject

/** Metadata only: no credentials, device identity, or pose data. */
internal object PicoDiagnosticStamp {
    fun attach(report: JSONObject, session: String, pid: Int, elapsedMs: Long): JSONObject {
        require(session.isNotBlank() && pid > 0 && elapsedMs >= 0)
        return report.put("diagnosticSession", session)
            .put("processId", pid).put("elapsedRealtimeMs", elapsedMs)
    }

    fun current(report: JSONObject, session: String, pid: Int, nowMs: Long, maxAgeMs: Long): Boolean {
        val sampled = report.optLong("elapsedRealtimeMs", -1)
        return maxAgeMs >= 0 && sampled >= 0 && sampled <= nowMs &&
            nowMs - sampled <= maxAgeMs && report.optString("diagnosticSession") == session &&
            report.optInt("processId", -1) == pid
    }
}
