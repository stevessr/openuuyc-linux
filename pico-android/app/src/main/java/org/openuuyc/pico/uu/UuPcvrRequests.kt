package org.openuuyc.pico.uu

import org.json.JSONObject

/** Built-in PCVR control-channel requests; one place decides the wire shape. */
internal object UuPcvrRequests {
    /** Explicit attach to a running SteamVR scene. The host must never turn
     * this into the tracking-only fallback, so it fails with a reason. */
    const val BACKEND_STEAMVR="steamvr"
    fun control(id: Int,start: Boolean,calibration: JSONObject?,backend: String?=null): JSONObject {
        val request=JSONObject().put("id",id).put("op",if(start) "start" else "stop")
        if(start) {
            // Attaching to SteamVR uses the headset's tracked head pose for
            // alignment, not its eye calibration; only a device-bridge start
            // needs the calibration payload.
            if(backend==null) calibration?.let {request.put("calibration",it)}
            backend?.let {request.put("backend",it)}
        }
        return request
    }
}
