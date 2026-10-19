package io.github.chargercontroller.data

import android.content.Context
import io.github.chargercontroller.core.ChargingCycle

/** Status only. Device tokens remain in CredentialStore. */
class ProtectionStore(context: Context) {
    val prefs = context.getSharedPreferences("charging_protection", Context.MODE_PRIVATE)
    var threshold: Int
        get() = prefs.getInt("threshold", 80).coerceIn(2, 100)
        set(value) { require(value in 2..100); check(prefs.edit().putInt("threshold", value).commit()) }
    val active get() = prefs.getBoolean("active", false)
    val lowerThreshold get() = prefs.getInt("lower_threshold", 30).coerceIn(1, 99)
    fun configure(high: Int, low: Int) {
        require(high in 2..100 && low in 1 until high)
        check(prefs.edit().putInt("threshold", high).putBoolean("cyclic", true).apply {
            putInt("lower_threshold", low)
        }.commit())
    }
    var controlMode: io.github.chargercontroller.core.ControlMode
        get() = io.github.chargercontroller.core.ControlMode.entries.firstOrNull { it.name == prefs.getString("control_mode", "LAN") }
            ?: io.github.chargercontroller.core.ControlMode.LAN
        set(value) { check(prefs.edit().putString("control_mode", value.name).commit()) }
    val phase get() = prefs.getString("phase", "PREPARING")!!
    val roundPercent get() = prefs.getInt("round_percent", -1).takeIf { it in 0..100 }
    val offRequested get() = prefs.getBoolean("off_requested", false)
    val attempts get() = prefs.getInt("attempts", 0)
    val message get() = prefs.getString("message", "尚未开始充电保护")!!
    val updatedAt get() = prefs.getLong("updated_at", 0)
    val sampledAt get() = prefs.getLong("sampled_at", 0)
    val sampleSource get() = prefs.getString("sample_source", "尚未采样")!!
    fun sample(percent: Int?, source: String) {
        check(prefs.edit().putLong("sampled_at", System.currentTimeMillis()).putString("sample_source", source)
            .putInt("sample_percent", percent ?: -1).commit())
    }
    fun save(cycle: ChargingCycle, message: String) {
        check(prefs.edit().putInt("threshold", cycle.threshold).putBoolean("active", cycle.active)
            .putBoolean("cyclic", true).apply {
                putInt("lower_threshold", cycle.lowerThreshold)
            }
            .putInt("round_percent", cycle.roundPercent ?: -1)
            .putBoolean("off_requested", cycle.offRequested).putString("phase", cycle.phase.name)
            .putInt("percent", cycle.percent ?: -1).putInt("attempts", cycle.attempts)
            .putString("message", message).putLong("updated_at", System.currentTimeMillis()).commit())
    }
    fun interrupt() { check(prefs.edit().putBoolean("active", false).putString("phase", "INTERRUPTED")
        .putString("message", "上次保护已中断，当前未运行；请重新开始").putLong("updated_at", System.currentTimeMillis()).commit()) }
}
