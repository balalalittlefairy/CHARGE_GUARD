package io.github.chargercontroller.data

import org.json.JSONObject

data class PlugDevice(val name: String, val model: String, val did: String, val ip: String, val token: String) {
    fun json() = JSONObject().put("name", name).put("model", model).put("did", did).put("ip", ip).put("token", token)
    companion object {
        fun fromJson(j: JSONObject) = PlugDevice(j.optString("name", "米家插座"), j.optString("model"),
            j.optString("did"), j.optString("ip", j.optString("localip")), j.optString("token"))
    }
}

data class CloudSession(val userId: String, val serviceToken: String, val security: String, val deviceId: String, val region: String) {
    fun json() = JSONObject().put("userId", userId).put("serviceToken", serviceToken)
        .put("security", security).put("deviceId", deviceId).put("region", region)
    companion object {
        fun fromJson(j: JSONObject) = CloudSession(j.getString("userId"), j.getString("serviceToken"),
            j.getString("security"), j.getString("deviceId"), j.getString("region"))
    }
}

data class PlugStatus(val power: Boolean, val watts: Double? = null, val kwh: Double? = null,
    val route: io.github.chargercontroller.core.ControlRoute? = null, val fallbackReason: String? = null)

open class UserFacingException(message: String) : Exception(message)

class CloudAuthException(message: String = "米家云端会话已失效，请重新登录小米账号") : UserFacingException(message)
class CloudPermissionException(message: String = "云端访问被拒绝，请检查账号设备权限、地区或重新登录") : UserFacingException(message)
