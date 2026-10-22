package io.github.chargercontroller.network

import io.github.chargercontroller.core.CloudRc4
import io.github.chargercontroller.data.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

fun interface CloudRequest { fun call(path: String, data: JSONObject): JSONObject }

/** Reuses the proven RC4 session transport. Never logs payloads, cookies or decrypted responses. */
class XiaomiCloudApi(private val http: XiaomiHttp = XiaomiHttp(8000, 10000)) {
    fun request(session: CloudSession, path: String, data: JSONObject): JSONObject {
        MainlandAccount.requireSession(session)
        require(path == "/home/device_list" || path.matches(Regex("/home/rpc/[0-9]{1,32}"))) { "不支持的云端请求" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        require(session.userId.matches(Regex("[0-9]+")) &&
            listOf(session.serviceToken, session.deviceId).all { it.isNotBlank() && it.none { c -> c == ';' || c == '\r' || c == '\n' } }) { "云端凭据格式无效，请重新登录" }
        val nonce = Base64.getEncoder().encodeToString(ByteArray(8).also { SecureRandom().nextBytes(it) } +
            ByteBuffer.allocate(4).putInt((System.currentTimeMillis() / 60000).toInt()).array())
        val fields = try { CloudRc4.fields(path, data.toString(), session.security, nonce) }
            catch (_: IllegalArgumentException) { throw CloudAuthException("保存的云端加密凭据无效，请重新登录") }
        val host = "api.io.mi.com"
        val cookies = "userId=${session.userId}; serviceToken=${session.serviceToken}; yetAnotherServiceToken=${session.serviceToken}; locale=zh_CN; PassportDeviceId=${session.deviceId}"
        val reply = http.post("https://$host/app$path", fields, XiaomiHttp.CLOUD_UA, cookies,
            mapOf("MIOT-ENCRYPT-ALGORITHM" to "ENCRYPT-RC4", "Accept-Encoding" to "identity",
                "X-XIAOMI-PROTOCAL-FLAG-CLI" to "PROTOCAL-HTTP2", "Cache-Control" to "no-cache"))
        return decode(reply.body, session.security, nonce)
    }
    companion object {
        fun decode(body: ByteArray, security: String, nonce: String): JSONObject {
            val response = try {
                val text = body.toString(Charsets.UTF_8).trim()
                JSONObject(if (text.startsWith("{")) text else CloudRc4.decrypt(text, security, nonce).toString(Charsets.UTF_8))
            } catch (_: Exception) { throw UserFacingException("云端响应格式或加密数据无效，无法确认设备状态") }
            val code = response.optInt("code", Int.MIN_VALUE)
            if (code != 0) {
                val message = response.optString("message").lowercase()
                if (code == 401 || message in listOf("auth err", "invalid token", "unauthorized", "token expired")) throw CloudAuthException()
                if (code == 403) throw CloudPermissionException()
                throw UserFacingException("米家云端请求失败（代码 $code），请检查账号地区、权限与设备在线状态")
            }
            return response
        }
    }
}
