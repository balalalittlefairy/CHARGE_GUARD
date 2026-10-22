package io.github.chargercontroller.network

import io.github.chargercontroller.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Live device RPC, not the cloud's cached property list. */
class XiaomiCloudClient(private val request: CloudRequest, private val pause: (Long) -> Unit = { Thread.sleep(it) }) : PlugClient {
    constructor(session: CloudSession, api: XiaomiCloudApi = XiaomiCloudApi()) :
        this(CloudRequest { path, data -> api.request(session, path, data) })
    companion object {
        private val sequence = AtomicInteger(1)
        fun validate(device: PlugDevice) {
            DeviceSupport.requireSupported(device)
            require(device.did.matches(Regex("[0-9]{1,32}"))) { "云端控制缺少有效设备 DID，请登录并从设备列表重新选择插座" }
        }
    }
    private fun prop(device: PlugDevice, siid: Int, piid: Int) = JSONObject().put("did", device.did).put("siid", siid).put("piid", piid)
    private fun rpc(device: PlugDevice, method: String, params: JSONArray): JSONArray {
        validate(device)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val id = sequence.incrementAndGet()
        val response = request.call("/home/rpc/${device.did}", JSONObject().put("id", id).put("method", method).put("params", params))
        if (response.optInt("code", -1) != 0) throw UserFacingException("云端设备请求失败（代码 ${response.optInt("code", -1)}）")
        val result = response.opt("result")
        if (result is JSONArray) return result
        if (result is JSONObject) {
            if (result.has("id") && result.optInt("id", -1) != id) throw UserFacingException("云端设备响应编号不匹配")
            if (result.has("error")) throw UserFacingException("云端设备未响应或请求被拒绝，请检查插座在线状态与权限")
            result.optJSONArray("result")?.let { return it }
        }
        throw UserFacingException("云端未返回有效设备属性，结果未确认")
    }
    override fun read(device: PlugDevice): PlugStatus {
        val values = rpc(device, "get_properties", JSONArray().put(prop(device, 2, 1)).put(prop(device, 11, 1)).put(prop(device, 11, 2)))
        var power: Boolean? = null; var watts: Double? = null; var energy: Double? = null
        var switchCode: Int? = null
        for (i in 0 until values.length()) {
            val item = values.optJSONObject(i) ?: continue
            if (item.optString("did") != device.did) continue
            val code = item.optInt("code", -1)
            if (item.optInt("siid") == 2 && item.optInt("piid") == 1) switchCode = code
            if (code != 0) continue
            when (item.optInt("siid") to item.optInt("piid")) {
                2 to 1 -> power = item.opt("value") as? Boolean
                11 to 1 -> energy = (item.opt("value") as? Number)?.toDouble()?.times(0.01)
                11 to 2 -> watts = (item.opt("value") as? Number)?.toDouble()
            }
        }
        return PlugStatus(power ?: throw UserFacingException("云端无法从设备确认开关状态（代码 ${switchCode ?: "缺失"}），请检查设备在线状态与账号权限"), watts, energy)
    }
    override fun setPower(device: PlugDevice, on: Boolean): PlugStatus {
        validate(device)
        try { rpc(device, "set_properties", JSONArray().put(prop(device, 2, 1).put("value", on))) }
        catch (e: Exception) {
            if (e is InterruptedException || e is CloudAuthException || e is CloudPermissionException) throw e
            // A lost write reply may still have switched the relay. Verify instead of guessing.
        }
        repeat(2) { attempt ->
            pause(if (attempt == 0) 700 else 1500)
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try { val actual = read(device); if (actual.power == on) return actual }
            catch (e: Exception) { if (e is InterruptedException || e is CloudAuthException || e is CloudPermissionException) throw e }
        }
        throw UserFacingException("云端开关结果未确认：请求成功不代表插座已切换，请检查网络与插座")
    }
}
