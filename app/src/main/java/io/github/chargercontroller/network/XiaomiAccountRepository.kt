package io.github.chargercontroller.network

import android.util.Base64
import io.github.chargercontroller.data.MainlandAccount
import io.github.chargercontroller.data.DeviceSupport
import io.github.chargercontroller.data.CloudSession
import io.github.chargercontroller.data.PlugDevice
import io.github.chargercontroller.data.UserFacingException
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

sealed class LoginStep {
    data class Success(val session: CloudSession) : LoginStep()
    data class Verification(val url: String) : LoginStep()
    data class SmsWeb(val url: String) : LoginStep()
}

/** Xiaomi private-account flow, based on MiService. See THIRD_PARTY_NOTICES.md. */
class XiaomiAccountRepository(val http: XiaomiHttp = XiaomiHttp()) {
    private val base = "https://account.xiaomi.com"

    private fun json(reply: XiaomiHttp.Reply): JSONObject = try {
        JSONObject(reply.body.toString(Charsets.UTF_8).removePrefix("&&&START&&&"))
    } catch (_: Exception) { throw UserFacingException("小米响应格式已变化，无法解析；请稍后重试") }

    private fun serviceLogin() = json(http.get("$base/pass/serviceLogin?sid=xiaomiio&_json=true"))

    /** All phone, SMS, captcha and risk verification stays on Xiaomi's official page. */
    fun beginSms(): LoginStep = next(serviceLogin(), initial = true)

    fun resume(): LoginStep = next(serviceLogin(), initial = false)

    private fun next(response: JSONObject, initial: Boolean): LoginStep {
        val security = response.optString("ssecurity")
        if (security.isNotBlank() && security != "null") return finish(response)
        val notification = response.optString("notificationUrl")
        if (notification.isNotBlank() && notification != "null") {
            val url = URI(base).resolve(notification).toString()
            require(XiaomiHttp.allowed(url)) { "验证地址不受信任" }
            return LoginStep.Verification(url)
        }
        if (initial) return LoginStep.SmsWeb(XiaomiHttp.SMS_LOGIN_URL)
        throw UserFacingException("身份验证尚未完成，请完成网页步骤后再继续")
    }
    private fun finish(j: JSONObject): LoginStep {
        val security = j.optString("ssecurity")
        val userId = j.optString("userId")
        val location = j.optString("location")
        if (security.isBlank() || security == "null" || userId.isBlank() || location.isBlank()) {
            throw UserFacingException("登录结果缺少服务凭据，请完成验证后重试")
        }
        val exchangeUrl = if (j.has("nonce") && !j.isNull("nonce")) {
            val sign = b64(MessageDigest.getInstance("SHA-1").digest("nonce=${j.get("nonce")}&$security".toByteArray()))
            location + (if (location.contains('?')) "&" else "?") + "clientSign=" + XiaomiHttp.encode(sign)
        } else location
        val exchanged = http.get(exchangeUrl)
        val serviceToken = exchanged.serviceToken ?: throw UserFacingException("米家服务令牌交换未完成，请重新登录")
        return LoginStep.Success(CloudSession(userId, serviceToken, security, http.deviceId, MainlandAccount.REGION))
    }

    fun devices(session: CloudSession): List<PlugDevice> {
        MainlandAccount.requireSession(session)
        val data = JSONObject().put("getVirtualModel", false).put("getHuamiDevices", 0)
        val response = XiaomiCloudApi(http).request(session, "/home/device_list", data)
        val list = response.optJSONObject("result")?.optJSONArray("list")
            ?: throw UserFacingException("设备列表结构不符合预期，暂无法获取设备")
        return DeviceSupport.filter((0 until list.length()).map { PlugDevice.fromJson(list.getJSONObject(it)) })
    }
    companion object {
        private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
