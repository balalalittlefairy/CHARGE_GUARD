package io.github.chargercontroller

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import io.github.chargercontroller.data.ProtectionStore

/** BOOT_COMPLETED arrives after first unlock; credentials stay in credential-encrypted storage. */
class ProtectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED ||
            !context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val state = ProtectionStore(context)
        if (state.resumeOnBoot && state.active) resume(context)
    }

    companion object {
        /** Also used when the user opens an app whose previous active task was interrupted. */
        fun resume(context: Context) {
            val state = ProtectionStore(context)
            if (!state.active || ChargingMonitorService.running) return
            ChargingMonitorService.channels(context)
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (!notifications.areNotificationsEnabled() ||
                listOf(ChargingMonitorService.RUN_CHANNEL, ChargingMonitorService.RESULT_CHANNEL).any {
                    notifications.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
                }) {
                state.resuming("通知未授权，保护尚未恢复；请在通知与后台设置中允许保护及结果通知")
                return
            }
            state.resuming("正在恢复上次电量循环保护")
            try {
                context.startForegroundService(Intent(context, ChargingMonitorService::class.java)
                    .setAction(ChargingMonitorService.RESTORE))
            } catch (_: RuntimeException) {
                val message = "系统未允许自动恢复，请打开 App 并允许自启动、后台运行"
                state.resuming(message) // Preserve active snapshot and budgets for a foreground retry.
                runCatching {
                    ChargingMonitorService.channels(context)
                    val open = PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    context.getSystemService(NotificationManager::class.java).notify(2,
                        Notification.Builder(context, ChargingMonitorService.RESULT_CHANNEL)
                            .setSmallIcon(R.drawable.ic_notification).setContentTitle("保护尚未恢复")
                            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
                            .setContentIntent(open).setAutoCancel(true).build())
                }
            }
        }
    }
}
