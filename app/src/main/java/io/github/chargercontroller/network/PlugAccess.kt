package io.github.chargercontroller.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.chargercontroller.data.*
import io.github.chargercontroller.core.*

object PlugAccess {
    /** Shared by manual control and the protection service. */
    val lock = Any()
    fun validate(context: Context, device: PlugDevice, mode: ControlMode = ControlPreferences(context).mode) {
        require(device.model == "cuco.plug.v3") { "本版仅支持 cuco.plug.v3" }
        when (mode) {
            ControlMode.LAN -> MiioLanClient.validate(device)
            ControlMode.CLOUD -> XiaomiCloudClient.validate(device)
            ControlMode.AUTO -> if (runCatching { MiioLanClient.validate(device) }.isFailure) XiaomiCloudClient.validate(device)
        }
    }
    fun controller(context: Context, mode: ControlMode = ControlPreferences(context).mode): PlugClient {
        val prefs = ControlPreferences(context)
        return PlugController(mode, { client(context) }, {
            if (prefs.cloudExpired) throw CloudAuthException()
            val saved = CredentialStore(context).get("session")?.let(CloudSession::fromJson)
                ?: throw UserFacingException("云端控制需要小米账号会话，请先登录")
            val cloud = XiaomiCloudClient(saved)
            object : PlugClient {
                private fun <T> guarded(block: () -> T): T = try { block() } catch (e: CloudAuthException) { prefs.cloudExpired = true; throw e }
                override fun read(device: PlugDevice) = guarded { cloud.read(device) }
                override fun setPower(device: PlugDevice, on: Boolean) = guarded { cloud.setPower(device, on) }
            }
        }, prefs::attempt)
    }
    fun client(context: Context): MiioLanClient {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val wifi = manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: throw LocalUnavailableException("手机未连接 Wi-Fi，局域网路线不可用")
        return MiioLanClient(wifi)
    }
}
