package io.github.chargercontroller.data

import android.content.Context
import io.github.chargercontroller.core.ControlMode
import io.github.chargercontroller.core.ControlRoute

/** Non-secret preferences only. Cloud credentials stay in CredentialStore. */
class ControlPreferences(context: Context) {
    val prefs = context.getSharedPreferences("control_settings", Context.MODE_PRIVATE)
    var mode: ControlMode
        get() = ControlMode.entries.firstOrNull { it.name == prefs.getString("mode", "AUTO") } ?: ControlMode.AUTO
        set(value) { check(prefs.edit().putString("mode", value.name).commit()) }
    var cloudExpired: Boolean
        get() = prefs.getBoolean("cloud_expired", false)
        set(value) { check(prefs.edit().putBoolean("cloud_expired", value).commit()) }
    val lastRoute get() = prefs.getString("route", "尚未尝试")!!
    val fallbackReason get() = prefs.getString("fallback_reason", "").orEmpty()
    fun attempt(route: ControlRoute, reason: String?) {
        check(prefs.edit().putString("route", route.label).putString("fallback_reason", reason.orEmpty()).commit())
    }
}
