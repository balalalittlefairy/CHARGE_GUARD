package io.github.chargercontroller.data

/** One source of truth for selection and all control entry points. */
object DeviceSupport {
    const val PLUG_V3 = "cuco.plug.v3"
    private val models = setOf(PLUG_V3)
    fun supports(device: PlugDevice) = device.model in models
    fun filter(devices: List<PlugDevice>) = devices.filter(::supports)
    fun requireSupported(device: PlugDevice) {
        require(supports(device)) { "本版仅支持米家智能插座 3（cuco.plug.v3）" }
    }
}

/** A saved foreign session must never be relabelled or sent to the mainland server. */
object MainlandAccount {
    const val REGION = "cn"
    const val REGION_LABEL = "中国大陆"
    const val LOGIN_REQUIRED = "旧会话属于非中国大陆地区，请重新登录中国大陆账号；局域网设备凭据和保护设置已保留"
    fun accepts(session: CloudSession) = session.region == REGION
    fun requireSession(session: CloudSession) {
        if (!accepts(session)) throw CloudAuthException(LOGIN_REQUIRED)
    }
}
