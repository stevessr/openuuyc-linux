package org.openuuyc.pico.uu

import org.json.JSONObject

/** Android 4.46.0 transport RPC approval; HTTP success alone never grants viewing. */
internal class UuAndroidShareApproval(val target: String) {
    enum class Result(val message: String) {
        ACCEPTED("对方已同意，请等待系统屏幕共享授权"),
        REJECTED("对方拒绝了共享请求"), SWITCH_OFF("对方尚未开启允许本机共享屏幕"),
        NOT_FOREGROUND("请让对方打开 UU远程并保持在前台"),
        BUSY("对方正在远控或共享，请先结束当前会话"),
        REQUEST_PENDING("对方正在处理其他共享请求，请稍后重试")
    }
    @Volatile var result: Result? = null; private set
    init {require(target.matches(Regex("[a-z0-9]{16}")))}
    @Synchronized fun receive(push: JSONObject): Boolean {
        if(result!=null || push.optString("type")!="transport_rpc") return false
        val rpc=push.optJSONObject("data") ?: return false
        if(rpc.optString("p")!="android_control_result") return false
        val data=rpc.optJSONObject("data") ?: return false
        if(data.optJSONObject("device_info")?.optString("device_id")!=target) return false
        result=when(data.optString("action")) {
            "accept_control" -> Result.ACCEPTED; "reject_request" -> Result.REJECTED
            "switch_is_off" -> Result.SWITCH_OFF; "not_foreground" -> Result.NOT_FOREGROUND
            "during_control" -> Result.BUSY; "during_request" -> Result.REQUEST_PENDING
            else -> null
        }
        return result!=null
    }
    companion object {
        fun request(localId: String,alias: String,version: String,action: String="request_control"): JSONObject {
            require(localId.matches(Regex("[a-z0-9]{16}")))
            require(action=="request_control" || action=="cancel_request")
            return JSONObject().put("p","android_control_request").put("need_response",false)
                .put("data",JSONObject().put("action",action).put("device_info",JSONObject()
                    .put("device_id",localId).put("platform",2).put("alias",alias).put("version_name",version)))
        }
    }
}
