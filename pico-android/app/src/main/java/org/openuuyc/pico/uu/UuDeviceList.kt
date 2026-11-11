package org.openuuyc.pico.uu

import org.json.JSONObject

/** Only the connectable list grants access; account-management groups do not. */
internal object UuDeviceList {
    fun parse(response: JSONObject): List<UuDevice> {
        val list = response.optJSONArray("my_binded_devices") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("device_id")
            if (!id.matches(Regex("[a-z0-9]{16}"))) return@mapNotNull null
            UuDevice(id, item.optString("alias").ifBlank { id },
                item.optString("status") == "CONNECTED", item.optBoolean("controllable", false),
                item.optJSONArray("participants_info")?.length() ?: 0,
                item.optInt("platform", 0), item.optString("version_name"))
        }.distinctBy { it.id }
    }
}
