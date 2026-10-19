package io.github.chargercontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wakes an existing task only; never starts protection after stop or process death. */
class ProtectionCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { ChargingMonitorService.requestCheck() }
}
