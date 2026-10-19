package io.github.chargercontroller.network

import android.util.Base64
import io.github.chargercontroller.data.CloudSession
import io.github.chargercontroller.data.PlugDevice
import io.github.chargercontroller.data.UserFacingException
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

sealed class LoginStep {
    data class Success(val session: CloudSession) : LoginStep()
    data class Captcha(val image: ByteArray) : LoginStep()
    data class Verification(val url: String) : LoginStep()
    data class Otp(val method: String) : LoginStep()
    data class SmsWeb(val url: String) : LoginStep()
}

/** Xiaomi private-account flow, based on MiService. See THIRD_PARTY_NOTICES.md. */
class XiaomiAccountRepository {
    val http = XiaomiHttp()
    private var parameters = JSONObject()
    private var username = ""
    private var passwordHash = ""
    private var region = "cn"
    private var verificationUrl = ""
    private var otpMethod = "Phone"
    private var otpFlag = 4
    private var otpSent = false
    private val base = "https://account.xiaomi.com"

    private fun json(reply: XiaomiHttp.Reply): JSONObject = try {
        JSONObject(reply.body.toString(Charsets.UTF_8).removePrefix("&&&START&&&"))
    } catch (_: Exception) { throw UserFacingException("小米响应格式已变化，无法解析；请稍后重试") }

    fun begin(user: String, password: String, selectedRegion: String): LoginStep {
        require(user.isNotBlank() && password.isNotEmpty()) { "请输入小米账号和密码" }
        require(selectedRegion in REGIONS)
        username = user
        passwordHash = MessageDigest.getInstance("MD5").digest(password.toByteArray()).joinToString("") { "%02X".format(it) }
        region = selectedRegion
        parameters = serviceLogin()
        return if (parameters.has("ssecurity")) finish(parameters) else authenticate("")
    }
    private fun serviceLogin() = json(http.get("$base/pass/serviceLogin?sid=xiaomiio&_json=true"))

    /** User enters phone and SMS code on Xiaomi's own page; no password required. */
    fun beginSms(selectedRegion: String): LoginStep {
        require(selectedRegion in REGIONS)
        region = selectedRegion
        username = ""
        passwordHash = ""
        parameters = serviceLogin()
        return if (parameters.optString("ssecurity").isNotBlank() && parameters.optString("ssecurity") != "null")
            finish(parameters) else LoginStep.SmsWeb(XiaomiHttp.SMS_LOGIN_URL)
    }

    fun authenticate(captcha: String): LoginStep {
        if (username.isBlank() || passwordHash.isBlank()) throw UserFacingException("登录会话已结束，请重新输入账号")
        val data = linkedMapOf("_json" to "true", "user" to username, "hash" to passwordHash)
        for (key in listOf("qs", "sid", "_sign", "callback")) {
            if (!parameters.has(key)) throw UserFacingException("登录初始化失败，请重新登录（参数缺失）")
            data[key] = parameters.getString(key)
        }
        if (captcha.isNotBlank()) data["captCode"] = captcha
        val response = json(http.post("$base/pass/serviceLoginAuth2", data))
        val notification = response.optString("notificationUrl")
        if (notification.isNotBlank()) {
            verificationUrl = URI(base).resolve(notification).toString()
            require(XiaomiHttp.allowed(verificationUrl))
            return LoginStep.Verification(verificationUrl)
        }
        val captchaUrl = response.optString("captchaUrl")
        if (captchaUrl.isNotBlank() && captchaUrl != "null") {
            return LoginStep.Captcha(http.get(URI(base).resolve(captchaUrl).toString()).body)
        }
        if (response.optInt("code", -1) != 0) {
            throw UserFacingException("小米登录未通过（代码 ${response.optInt("code", -1)}），请检查账号密码或稍后重试，避免连续触发风控")
        }
        return finish(response)
    }

    /** Optional native OTP path. No automatic resending or authentication retry loop. */
    fun sendOtp(): LoginStep {
        check(verificationUrl.isNotEmpty()) { "请先登录" }
        if (otpSent) throw UserFacingException("验证码已发送，请先提交或完成网页验证；不要反复发送")
        http.get(verificationUrl)
        val query = URI(verificationUrl).rawQuery.orEmpty().split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8") else null
        }.toMap()
        val methods = json(http.get("$base/identity/list?" + XiaomiHttp.form(mapOf(
            "sid" to query.getOrDefault("sid", "xiaomiio"), "supportedMask" to "0",
            "_locale" to "zh_CN", "context" to query.getOrDefault("context", "")))))
        otpFlag = methods.optInt("flag", 4)
        if (otpFlag != 4 && otpFlag != 8) throw UserFacingException("该账号需要网页完成身份验证")
        otpMethod = if (otpFlag == 8) "Email" else "Phone"
        checked(http.get("$base/identity/auth/verify$otpMethod?_flag=$otpFlag&_json=true"))
        if (otpMethod == "Phone") checked(http.post("$base/identity/auth/sendPhoneTicket",
            mapOf("retry" to "0", "icode" to "", "_json" to "true")))
        otpSent = true
        return LoginStep.Otp(if (otpMethod == "Phone") "短信" else "邮件")
    }
    private fun checked(reply: XiaomiHttp.Reply): JSONObject {
        val j = json(reply)
        if (j.has("code") && j.optInt("code", -1) != 0) throw UserFacingException("验证未通过（代码 ${j.optInt("code", -1)}），可改用网页验证")
        return j
    }
    fun submitOtp(code: String): LoginStep {
        require(otpSent && code.matches(Regex("[0-9]{4,8}"))) { "请输入当前会话收到的验证码" }
        val response = checked(http.post("$base/identity/auth/verify$otpMethod?_dc=${System.currentTimeMillis()}",
            mapOf("_flag" to otpFlag.toString(), "ticket" to code, "trust" to "false", "_json" to "true")))
        val location = response.optString("location")
        if (location.isBlank()) throw UserFacingException("验证码验证未完成，可改用网页验证")
        val reply = http.get(URI(base).resolve(location).toString())
        val confirm = URI(reply.url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("confirmUrl=") }
        if (confirm != null) http.get(java.net.URLDecoder.decode(confirm.substringAfter('='), "UTF-8"))
        return resume()
    }
    fun resume(): LoginStep {
        val response = serviceLogin()
        if (!response.has("ssecurity")) throw UserFacingException("身份验证尚未完成，请完成网页步骤后再继续")
        return finish(response)
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
        passwordHash = ""
        return LoginStep.Success(CloudSession(userId, serviceToken, security, http.deviceId, region))
    }

    fun devices(session: CloudSession): List<PlugDevice> {
        require(session.region in REGIONS)
        val data = JSONObject().put("getVirtualModel", false).put("getHuamiDevices", 0)
        val response = XiaomiCloudApi(http).request(session, "/home/device_list", data)
        val list = response.optJSONObject("result")?.optJSONArray("list")
            ?: throw UserFacingException("设备列表结构不符合预期，暂无法获取设备")
        return (0 until list.length()).map { PlugDevice.fromJson(list.getJSONObject(it)) }
            .sortedBy { if (it.model == "cuco.plug.v3") 0 else 1 }
    }
    companion object {
        val REGIONS = listOf("cn", "de", "i2", "ru", "sg", "us")
        private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
