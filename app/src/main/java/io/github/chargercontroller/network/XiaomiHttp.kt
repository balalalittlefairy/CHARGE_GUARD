package io.github.chargercontroller.network

import io.github.chargercontroller.data.UserFacingException
import io.github.chargercontroller.data.CloudAuthException
import io.github.chargercontroller.data.CloudPermissionException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Per-login cookie jar; never sets process-wide CookieHandler or weakens TLS checks. */
class XiaomiHttp(private val connectMs: Int = 15000, private val readMs: Int = 20000,
    private val openConnection: (URL) -> java.net.URLConnection = { it.openConnection() }) {
    private val cookies = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    data class Reply(val body: ByteArray, val url: String, val serviceToken: String? = null)
    var deviceId: String = java.util.UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
    companion object {
        const val LOGIN_UA = "APP/com.xiaomi.mihome APPV/11.3.203 iosPassportSDK/4.2.50 iOS/26.3.1 MK/aVBob25lMTcsMg== DEVT/aVBob25l DEVS/aU9T BRA/QXBwbGU= L/zh_CN"
        const val SMS_LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_loginType=ticket&_locale=zh_CN"
        const val CLOUD_UA = "iOS-14.4-6.0.103-iPhone12,3--D7744744F7AF32F0544445285880DD63E47D9BE9-8816080-84A3F44E137B71AE-iPhone"
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        fun form(values: Map<String, String>) = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        fun allowed(url: String): Boolean {
            val u = runCatching { URI(url) }.getOrNull() ?: return false
            val host = u.host?.lowercase() ?: return false
            return u.scheme == "https" && (u.port == -1 || u.port == 443) && u.userInfo == null &&
                (host == "account.xiaomi.com" || host.endsWith(".account.xiaomi.com") ||
                    host == "api.io.mi.com" || host.endsWith(".api.io.mi.com"))
        }
    }
    fun cookieHeader(url: String): String = cookies.get(URI(url), emptyMap())["Cookie"]?.joinToString("; ") ?: ""
    fun importCookie(url: String, header: String) {
        require(allowed(url))
        cookies.put(URI(url), mapOf("Set-Cookie" to listOf(header)))
    }
    fun serviceTokenAt(url: String, headers: Map<String, List<String>>): String? {
        if (!allowed(url) || !(URI(url).host == "api.io.mi.com" || URI(url).host.endsWith(".api.io.mi.com"))) return null
        return headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
            .flatMap { runCatching { java.net.HttpCookie.parse(it) }.getOrDefault(emptyList()) }
            .lastOrNull { it.name == "serviceToken" && !it.hasExpired() &&
                (it.domain.isNullOrBlank() || it.domain.trimStart('.').let { d ->
                    (d == "io.mi.com" || d == "api.io.mi.com" || d.endsWith(".api.io.mi.com")) &&
                        (URI(url).host == d || URI(url).host.endsWith(".$d"))
                }) }?.value
    }
    fun get(url: String, ua: String = LOGIN_UA): Reply = request(url, null, ua)
    fun post(url: String, fields: Map<String, String>, ua: String = LOGIN_UA, extraCookies: String = "", headers: Map<String, String> = emptyMap()): Reply =
        request(url, fields, ua, extraCookies, headers)

    private fun request(initial: String, initialFields: Map<String, String>?, ua: String, extraCookies: String = "", requestHeaders: Map<String, String> = emptyMap()): Reply {
        var url = initial
        var fields = initialFields
        var serviceToken: String? = null
        repeat(10) {
            require(allowed(url)) { "拒绝非小米 HTTPS 地址" }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val connection = openConnection(URL(url)) as HttpsURLConnection
            try {
                connection.connectTimeout = connectMs
                connection.readTimeout = readMs
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", ua)
                requestHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val baseCookies = cookieHeader(url)
                val account = URI(url).host.endsWith("account.xiaomi.com")
                val cookie = if (account) {
                    val device = if (baseCookies.split(';').any { it.trim().startsWith("deviceId=") }) "" else "; deviceId=$deviceId"
                    "$baseCookies; sdkVersion=3.9$device".trimStart(';', ' ')
                } else extraCookies
                if (cookie.isNotEmpty()) connection.setRequestProperty("Cookie", cookie)
                if (fields != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        it.write(form(fields!!).toByteArray(Charsets.UTF_8))
                    }
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val status = connection.responseCode
                val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
                cookies.put(URI(url), headers)
                serviceTokenAt(url, headers)?.let { serviceToken = it }
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (extraCookies.isNotEmpty()) throw CloudAuthException("米家云端请求要求重新登录，会话可能已失效")
                    val next = connection.getHeaderField("Location") ?: throw UserFacingException("小米登录重定向缺少地址")
                    url = URI(url).resolve(next).toString()
                    if (status != 307 && status != 308) fields = null
                } else {
                    if (!account && status == 401) throw CloudAuthException()
                    if (!account && status == 403) throw CloudPermissionException()
                    if (status == 401 || status == 403) throw UserFacingException(if (account) "小米网页登录请求被拒绝（HTTP $status）" else "米家服务凭据未通过校验（HTTP $status）；请重新登录换取服务令牌")
                    if (status !in 200..299) throw UserFacingException("小米服务请求失败：HTTP $status")
                    val body = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 4 * 1024 * 1024) throw UserFacingException("小米响应超过大小限制")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    return Reply(body, url, serviceToken)
                }
            } finally { connection.disconnect() }
        }
        throw UserFacingException("小米登录重定向过多，请稍后重试")
    }
}
