package io.github.chargercontroller.data

import android.content.Context
import io.github.chargercontroller.core.ControlMode

/** Presentation state only. Never stores account cookies, device Token, or credentials. */
class OnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences("first_use", Context.MODE_PRIVATE)
    private val legacyData = listOf("encrypted_credentials", "charging_protection", "control_settings")
        .any { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isNotEmpty() }

    /** Consumed before showing UI: a later ordinary launch never opens the guide automatically. */
    fun consumeFirstLaunch(isUpgrade: Boolean): Boolean {
        if (prefs.contains("handled")) return false
        val fresh = !isUpgrade && !legacyData
        check(prefs.edit().putBoolean("handled", true).putBoolean("pending", fresh).commit())
        return fresh
    }
    val pending get() = prefs.getBoolean("pending", false)
    var notificationAsked: Boolean
        get() = prefs.getBoolean("notification_asked", false)
        set(value) { check(prefs.edit().putBoolean("notification_asked", value).commit()) }
    var page: String
        get() = prefs.getString("page", "welcome")!!.takeIf { it in PAGES } ?: "welcome"
        set(value) { require(value in PAGES); check(prefs.edit().putString("page", value).commit()) }
    var low: String
        get() = prefs.getString("low", "30")!!
        set(value) { draft("low", value) }
    var high: String
        get() = prefs.getString("high", "80")!!
        set(value) { draft("high", value) }
    var mode: ControlMode
        get() = ControlMode.entries.firstOrNull { it.name == prefs.getString("mode", "AUTO") } ?: ControlMode.AUTO
        set(value) { draft("mode", value.name) }
    var manual: Boolean
        get() = prefs.getBoolean("manual", false)
        set(value) { check(prefs.edit().putBoolean("manual", value).commit()) }
    val rulesSaved get() = prefs.getBoolean("rules_saved", false)
    fun savedRules() { check(prefs.edit().putBoolean("rules_saved", true).commit()) }
    fun complete() { check(prefs.edit().putBoolean("pending", false).commit()) }
    fun review(low: Int, high: Int, mode: ControlMode, manual: Boolean) {
        check(prefs.edit().putBoolean("pending", true).putString("page", "welcome")
            .putString("low", low.toString()).putString("high", high.toString()).putString("mode", mode.name)
            .putBoolean("manual", manual).putBoolean("rules_saved", true).commit())
    }
    private fun draft(key: String, value: String) {
        if (prefs.getString(key, null) == value) return
        check(prefs.edit().putString(key, value).putBoolean("rules_saved", false).commit())
    }
    companion object { val PAGES = listOf("welcome", "account", "device", "rules", "permissions", "complete") }
}
