package io.github.chargercontroller.network

import android.net.Network
import io.github.chargercontroller.core.MiioCodec
import io.github.chargercontroller.core.LocalUnavailableException
import io.github.chargercontroller.data.DeviceSupport
import io.github.chargercontroller.data.PlugDevice
import io.github.chargercontroller.data.PlugStatus
import io.github.chargercontroller.data.UserFacingException
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class MiioLanClient(private val network: Network?) : PlugClient {
    companion object {
        private val sequence = AtomicInteger((System.nanoTime() and 0xffff).toInt())
        fun validate(device: PlugDevice) {
            MiioCodec.token(device.token)
            DeviceSupport.requireSupported(device)
            val parts = device.ip.split('.')
            require(parts.size == 4 && parts.all { it.isNotEmpty() && it.toIntOrNull() in 0..255 }) { "请输入有效的 IPv4 地址" }
            val numbers = parts.map { it.toInt() }
            require(numbers[0] == 10 || (numbers[0] == 172 && numbers[1] in 16..31) ||
                (numbers[0] == 192 && numbers[1] == 168)) { "局域网仅允许家庭局域网私有 IPv4 地址" }
        }
    }

    private fun receive(socket: DatagramSocket): ByteArray {
        val packet = DatagramPacket(ByteArray(65535), 65535)
        socket.receive(packet)
        return packet.data.copyOf(packet.length)
    }

    private fun request(device: PlugDevice, method: String, params: JSONArray): JSONArray {
        validate(device)
        val token = MiioCodec.token(device.token)
        val address = InetAddress.getByName(device.ip)
        repeat(2) { attempt ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                DatagramSocket().use { socket ->
                    // Bind to Wi-Fi even when Android selects mobile data for internet access.
                    network?.bindSocket(socket)
                    socket.connect(address, 54321)
                    socket.soTimeout = 2500
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    socket.send(DatagramPacket(MiioCodec.hello, 32))
                    val handshake = receive(socket)
                    val header = MiioCodec.header(handshake)
                    require(handshake.size == 32) { "握手响应无效" }
                    val id = sequence.incrementAndGet()
                    val payload = JSONObject().put("id", id).put("method", method).put("params", params)
                    val wire = MiioCodec.encode(payload.toString(), token, header.deviceId, header.timestamp + 1)
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    socket.send(DatagramPacket(wire, wire.size))
                    val reply = JSONObject(MiioCodec.decode(receive(socket), token, header.deviceId))
                    require(reply.optInt("id", -1) == id) { "响应请求编号不匹配" }
                    if (reply.has("error")) throw UserFacingException("插座返回协议错误，请查看固件兼容性")
                    return reply.optJSONArray("result") ?: throw UserFacingException("插座未返回有效属性列表")
                }
            } catch (e: SocketTimeoutException) {
                if (attempt == 1) throw LocalUnavailableException("局域网超时：检查 Wi-Fi、IP、设备在线状态和客户端隔离；Token 错误也可能导致无响应")
            }
        }
        error("unreachable")
    }

    override fun read(device: PlugDevice): PlugStatus {
        val result = request(device, "get_properties", JSONArray().put(prop("power", 2, 1))
            .put(prop("energy", 11, 1)).put(prop("watts", 11, 2)))
        var power: Boolean? = null
        var watts: Double? = null
        var energy: Double? = null
        for (i in 0 until result.length()) {
            val item = result.getJSONObject(i)
            if (item.optInt("code", -1) != 0) continue
            when (item.optInt("siid") to item.optInt("piid")) {
                2 to 1 -> power = item.opt("value") as? Boolean
                11 to 1 -> energy = (item.opt("value") as? Number)?.toDouble()?.times(0.01)
                11 to 2 -> watts = (item.opt("value") as? Number)?.toDouble()
            }
        }
        return PlugStatus(power ?: throw UserFacingException("无法读取开关属性，不能确认插座状态"), watts, energy)
    }

    override fun setPower(device: PlugDevice, on: Boolean): PlugStatus {
        // Explicit assignments are idempotent. Even a timeout may mean the relay has switched.
        var writeAccepted = false
        try {
            val result = request(device, "set_properties", JSONArray().put(prop("power", 2, 1).put("value", on)))
            writeAccepted = result.length() == 1 && result.getJSONObject(0).optInt("code", -1) == 0
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            // Always try to read back before reporting an uncertain result.
        }
        try {
            Thread.sleep(700)
            val actual = read(device)
            if (actual.power == on) return actual
            throw UserFacingException(if (writeAccepted) "请求已接受，但读回状态不一致" else "设置失败：插座仍保持原状态")
        } catch (e: Exception) {
            if (e is UserFacingException && e.message?.contains("状态") == true) throw e
            if (e is InterruptedException) throw e
            throw LocalUnavailableException("局域网操作结果未确认，请刷新状态或检查插座，勿假定已经断电")
        }
    }

    private fun prop(did: String, service: Int, property: Int) = JSONObject()
        .put("did", did).put("siid", service).put("piid", property)
}
