package org.openuuyc.pico.uu

import org.json.JSONArray
import org.json.JSONObject

/** Windows-compatible NRD endpoint requires its desktop wire platform (1),
 * not the Android OS enum. OS/hardware still describe the actual PICO device.
 * Real service returned HTTP 500/code 9999 with platform=2, and 200/code 0
 * with platform=1 for the same registration body.
 */
internal fun deviceRegistrationBody(
    identity: UuIdentity, os: String, board: String, abi: String,
    memoryMiB: Long = 0, video: List<String> = emptyList(), screen: String = "1920x1080", appVersion: String = ""
): JSONObject = JSONObject()
    .put("name", listOf("OpenUUYC PICO",appVersion).filter(String::isNotBlank).joinToString(" "))
    .put("client_id", identity.clientId)
    .put("system_id", identity.systemId)
    .put("machine_guid", identity.machineGuid)
    .put("os", os)
    .put("base_board", board)
    .put("cpu", abi)
    .put("video", JSONArray(video))
    .put("mac", "")
    .put("memory", memoryMiB)
    .put("screen", screen)
    .put("platform", 1)
    .put("controllable", false)
