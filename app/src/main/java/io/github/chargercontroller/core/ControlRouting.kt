package io.github.chargercontroller.core

import java.io.IOException

enum class ControlMode(val label: String) { AUTO("自动：局域网优先，云端备选"), LAN("仅局域网"), CLOUD("仅云端") }
enum class ControlRoute(val label: String) { LAN("局域网"), CLOUD("云端") }
class LocalUnavailableException(message: String) : IOException(message)
data class Routed<T>(val value: T, val route: ControlRoute, val fallbackReason: String? = null)

/** One serial route at a time. Cancellation and validation/explicit rejection never trigger fallback. */
object ControlRouting {
    fun <T> run(mode: ControlMode, local: () -> T, cloud: () -> T,
        onAttempt: (ControlRoute, String?) -> Unit): Routed<T> {
        fun checkCancelled() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
        checkCancelled()
        if (mode == ControlMode.CLOUD) {
            onAttempt(ControlRoute.CLOUD, null); return Routed(cloud(), ControlRoute.CLOUD)
        }
        onAttempt(ControlRoute.LAN, null)
        try { return Routed(local(), ControlRoute.LAN) }
        catch (e: IOException) {
            checkCancelled()
            if (mode == ControlMode.LAN) throw e
            val reason = if (e is LocalUnavailableException) e.message ?: "局域网不可达" else "局域网连接失败"
            onAttempt(ControlRoute.CLOUD, reason)
            return Routed(cloud(), ControlRoute.CLOUD, reason)
        }
    }
}
