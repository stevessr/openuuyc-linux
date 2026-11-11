package org.openuuyc.pico.uu

import org.json.JSONObject

/** Android DeviceInitData, official 4.46.0 p070ea/v serializer (17 fields).
 * This is a controller: never advertise an available Android capture service.
 */
internal fun deviceRegistrationBody(identity: UuIdentity, profile: UuAndroidRegistrationProfile): JSONObject = JSONObject()
    .put("name", profile.brand + profile.model)
    .put("client_id", identity.clientId)
    .put("system_id", identity.systemId)
    .put("system_version", profile.sdk.toString())
    .put("gaid", "")
    .put("install_id", identity.machineGuid)
    .put("build_fingerprint", profile.fingerprint)
    .put("brand", profile.brand)
    .put("manufacturer", profile.manufacturer)
    .put("model", profile.model)
    .put("product", profile.product)
    .put("rom", profile.rom)
    .put("abi", profile.abis.joinToString(", "))
    .put("resolution", profile.resolution)
    .put("screen_size", profile.screenSize)
    .put("dpi", profile.dpi)
    .put("controllable", false)
