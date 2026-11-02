package org.openuuyc.pico.uu
import org.json.JSONObject
internal object UuSmsWire {
    const val CODE="/api/v1/security/mobile/code"
    const val LOGIN="/api/v1/login/by_mobile"
    fun request(country: String,mobile: String)=JSONObject().put("country_code",country).put("mobile",mobile).put("type","login")
    fun login(country: String,mobile: String,code: String)=JSONObject().put("country_code",country).put("mobile",mobile).put("code",code)
}
