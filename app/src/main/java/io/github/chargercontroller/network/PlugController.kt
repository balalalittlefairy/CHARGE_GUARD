package io.github.chargercontroller.network

import io.github.chargercontroller.core.*
import io.github.chargercontroller.data.*

class PlugController(private val mode: ControlMode, private val local: () -> PlugClient,
    private val cloud: () -> PlugClient, private val onAttempt: (ControlRoute, String?) -> Unit = { _, _ -> }) : PlugClient {
    private fun perform(device: PlugDevice, on: Boolean?): PlugStatus {
        require(device.model == "cuco.plug.v3") { "本版仅支持 cuco.plug.v3" }
        var fallingBack = false
        val result = ControlRouting.run(mode, {
            if (mode == ControlMode.AUTO) {
                try { MiioLanClient.validate(device) }
                catch (_: IllegalArgumentException) { throw LocalUnavailableException("局域网 IP 或 Token 配置无效，使用云端备选") }
            }
            val client = local()
            if (on == null) client.read(device) else client.setPower(device, on)
        }, {
            val client = cloud()
            // LAN may already have changed the relay before timing out. Read live state first.
            if (fallingBack && on != null) {
                val actual = client.read(device)
                if (actual.power == on) actual else client.setPower(device, on)
            } else if (on == null) client.read(device) else client.setPower(device, on)
        }, { route, reason -> fallingBack = reason != null; onAttempt(route, reason) })
        return result.value.copy(route = result.route, fallbackReason = result.fallbackReason)
    }
    override fun read(device: PlugDevice) = perform(device, null)
    override fun setPower(device: PlugDevice, on: Boolean) = perform(device, on)
}
