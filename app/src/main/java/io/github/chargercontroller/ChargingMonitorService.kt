package io.github.chargercontroller

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import io.github.chargercontroller.core.ControlMode
import io.github.chargercontroller.core.LocalUnavailableException
import io.github.chargercontroller.core.ChargingCycle
import io.github.chargercontroller.core.ChargingCycle.Command
import io.github.chargercontroller.core.ChargingCycle.Phase
import io.github.chargercontroller.data.*
import io.github.chargercontroller.network.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ChargingMonitorService : Service() {
    companion object {
        const val START = "io.github.chargercontroller.START_PROTECTION"
        const val RESTORE = "io.github.chargercontroller.RESTORE_PROTECTION"
        const val STOP = "io.github.chargercontroller.STOP_PROTECTION"
        const val RUN_CHANNEL = "charging_running"
        const val RESULT_CHANNEL = "charging_results"
        @Volatile var running = false; private set
        private var instance: ChargingMonitorService? = null
        fun requestCheck() { instance?.let { if (it.cycle?.active == true) { it.checkBattery("唤醒复查"); it.scheduleAlarm() } } }
        fun channels(context: Context) {
            context.getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(NotificationChannel(RUN_CHANNEL, "电量循环保护运行", NotificationManager.IMPORTANCE_LOW))
                createNotificationChannel(NotificationChannel(RESULT_CHANNEL, "电量循环保护结果", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var future: Future<*>? = null
    private var generation = 0
    private var cycle: ChargingCycle? = null
    private var device: PlugDevice? = null
    private lateinit var status: ProtectionStore
    private lateinit var credentials: CredentialStore
    private lateinit var wake: PowerManager.WakeLock
    private var registered = false
    private var restoring = false
    private var waitForRestore = false
    private var restoreOff = false
    private var restoreAttempts = 0
    private var restorePhase: String? = null
    private var restoreRound: Int? = null
    private var lastPercent: Int? = null
    private var controlMode = ControlMode.AUTO
    private lateinit var controls: ControlPreferences
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            checkBattery("电量广播", intent.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED })
        }
    }
    override fun onCreate() {
        super.onCreate()
        status = ProtectionStore(this); credentials = CredentialStore(this); controls = ControlPreferences(this)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:plug-command")
            .apply { setReferenceCounted(false) }
        channels(this)
        instance = this
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (cycle == null && status.active) { status.interrupt() }
            stopCycle()
            return START_NOT_STICKY
        }
        if (cycle?.active == true) return START_STICKY
        // Sticky restarts and explicit resume use the saved snapshot, never today's UI draft.
        // Force-stopped apps receive no boot broadcast; opening the app is a user action.
        val restore = (intent == null || intent.action == RESTORE) && status.active
        if (intent?.action != START && !restore) { stopSelf(); return START_NOT_STICKY }
        waitForRestore = restore && (intent?.action == RESTORE || status.resumePending)
        running = true
        getSystemService(NotificationManager::class.java).cancel(2)
        val initial = notification("正在准备电量循环保护…", true)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1, initial)
            val config = credentials.get(if (restore) "protection_device" else "device")?.let(PlugDevice::fromJson)
                ?: throw UserFacingException("尚未保存目标插座，请先选择设备")
            controlMode = if (restore) status.controlMode else controls.mode
            PlugAccess.validate(this, config, controlMode)
            status.controlMode = controlMode
            device = config
            val threshold = if (restore) status.threshold else intent!!.getIntExtra("threshold", 80)
            val low = if (restore) status.lowerThreshold else intent!!.getIntExtra("lower_threshold", 30)
            require(threshold in 2..100 && low in 1 until threshold)
            restoreOff = restore && status.offRequested
            restoreAttempts = if (restore) status.attempts else 0
            restorePhase = if (restore) status.phase else null
            restoreRound = if (restore) status.roundPercent else null
            cycle = ChargingCycle(threshold, low)
            credentials.put("protection_device", config.json())
            restoring = true
            if (waitForRestore) status.resuming("正在恢复上次保护：核对当前电量与插座状态")
            else status.clearResume()
            publish(if (restoring) "正在核对电量与插座状态" else "准备中：读取手机电量")
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            val sticky = if (Build.VERSION.SDK_INT >= 33) registerReceiver(battery, filter, Context.RECEIVER_NOT_EXPORTED)
                else registerReceiver(battery, filter)
            registered = true
            sticky?.let { battery.onReceive(this, it) }
            if (sticky == null) checkBattery("启动复查")
            if (restoring) restoreTask()
            if (cycle?.active == true) { handler.postDelayed(poll, 30_000); scheduleAlarm() }
            handler.postDelayed({
                if (cycle?.phase == Phase.PREPARING && !restoring) fail("无法取得有效电量，保护未启动")
            }, 30_000)
        } catch (e: Exception) { fail(safeFailure(e)) }
        return if (cycle?.active == true) START_STICKY else START_NOT_STICKY
    }
    private val poll = object : Runnable {
        override fun run() {
            if (cycle?.active != true) return
            checkBattery("定时复查")
            if (cycle?.active == true) handler.postDelayed(this, 30_000)
        }
    }
    private fun checkBattery(source: String, event: Intent? = null) {
        val current = cycle ?: return
        if (!current.active) return
        val sample = event ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Sticky system state also respects Android's battery test overrides.
        lastPercent = sample?.let { ChargingCycle.percentage(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) }
            ?: getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        status.sample(lastPercent, source)
        if (restoring) return
        val command = current.battery(lastPercent)
        if (command != Command.NONE) execute(command)
        else if (current.phase == Phase.FAILED) fullBatteryFailure()
        else publish(describe(current))
    }
    private fun alarmIntent() = PendingIntent.getBroadcast(this, 3, Intent(this, ProtectionCheckReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun scheduleAlarm() {
        if (cycle?.active == true) getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60_000, alarmIntent())
    }
    private fun cancelAlarm() { getSystemService(AlarmManager::class.java).cancel(alarmIntent()) }
    private fun restoreTask() {
        val version = generation
        holdWake(120_000)
        future = worker.submit {
            val outcome = runCatching { synchronized(PlugAccess.lock) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                PlugAccess.controller(this, controlMode).read(device!!)
            } }
            handler.post {
                if (version != generation || cycle?.active != true) return@post
                releaseWake()
                val error = outcome.exceptionOrNull()
                if (waitForRestore && error != null) {
                    // AUTO may have reached an expired cloud fallback only because Wi-Fi is
                    // still starting. Valid LAN credentials can recover without that account.
                    val canWaitForLan = controlMode == ControlMode.AUTO &&
                        runCatching { MiioLanClient.validate(device!!) }.isSuccess
                    val rejectedCloud = error is CloudAuthException || error is CloudPermissionException
                    if (rejectedCloud && !canWaitForLan || error is IllegalArgumentException || error is InterruptedException) {
                        fail(safeFailure(error)); return@post
                    }
                    val message = "恢复等待：${safeFailure(error)}；30 秒后重新确认，尚未发送开关命令"
                    status.resuming(message)
                    getSystemService(NotificationManager::class.java).notify(1, notification(routeMessage(message), true))
                    handler.postDelayed({ if (version == generation && cycle?.active == true && restoring) restoreTask() }, 30_000)
                    return@post
                }
                restoring = false; waitForRestore = false; status.clearResume()
                val command = cycle!!.recover(lastPercent, outcome.getOrNull()?.power, restoreOff,
                    restoreAttempts, restorePhase, restoreRound)
                if (command != Command.NONE) execute(command)
                else if (cycle!!.phase == Phase.FAILED) {
                    if (lastPercent == 100 && restoreOff) fullBatteryFailure()
                    else fail("无法确认有效电量或区间内的插座状态，保护未启动，请检查所选路线、网络和插座后重新开始")
                } else {
                    publish(describe(cycle!!))
                    if (cycle!!.phase in listOf(Phase.WAIT_RETRY_ON, Phase.WAIT_RETRY_OFF)) roundAlert()
                }
            }
        }
    }
    private fun execute(command: Command) {
        val current = cycle ?: return
        val version = generation
        publish(describe(current)) // Persist OFF intent before sending any relay command.
        holdWake(120_000)
        future = worker.submit {
            val outcome = runCatching { synchronized(PlugAccess.lock) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                PlugAccess.controller(this, controlMode).setPower(device!!, command == Command.ON)
            } }
            handler.post {
                if (version != generation || cycle?.active != true) return@post
                releaseWake()
                if (outcome.isSuccess) getSystemService(NotificationManager::class.java).cancel(2)
                val next = current.result(outcome.isSuccess)
                if (next != Command.NONE) { execute(next); return@post }
                when (current.phase) {
                    Phase.RETRY_ON, Phase.RETRY_OFF -> {
                        val delay = if (current.attempts == 1) 10_000L else 30_000L
                        publish("${if (current.offRequested) "断电" else "开启"}结果未确认，${delay / 1000} 秒后重试（${current.attempts}/3）；${outcome.exceptionOrNull()?.let(::safeFailure).orEmpty()}")
                        holdWake(delay + 120_000)
                        handler.postDelayed({
                            if (version == generation && current.active) {
                                val retry = current.retry()
                                if (retry != Command.NONE) execute(retry)
                                else if (current.phase == Phase.FAILED) fullBatteryFailure()
                            }
                        }, delay)
                    }
                    Phase.WAIT_RETRY_ON, Phase.WAIT_RETRY_OFF -> { publish(describe(current)); roundAlert() }
                    Phase.FAILED -> fullBatteryFailure()
                    else -> publish(describe(current))
                }
            }
        }
    }
    private fun describe(c: ChargingCycle): String = when (c.phase) {
        Phase.PREPARING -> "准备中：等待有效电量"
        Phase.TURNING_ON -> "正在开启并读回确认（${c.attempts}/3）"
        Phase.MONITORING -> "电量循环保护 · 监测中：${c.percent?.let { "$it%" } ?: "电量未知"} → ${c.threshold}% 自动断电"
        Phase.WAITING_LOW -> "电量循环保护 · 插座已确认关闭；${c.percent?.let { "当前 $it%，" } ?: ""}电量 ≤ ${c.lowerThreshold}% 自动开启"
        Phase.TURNING_OFF -> "正在断电并读回确认（${c.attempts}/3）"
        Phase.WAIT_RETRY_ON -> "开启未确认（本轮 3 次已用尽）；${c.percent}% · 电量 ≤ ${c.nextRetryPercent}% 再尝试，保护仍在运行"
        Phase.WAIT_RETRY_OFF -> "断电未确认（本轮 3 次已用尽）；${c.percent}% · ${if ((c.nextRetryPercent ?: 100) < 100) "电量 ≥ ${c.nextRetryPercent}% 再尝试" else "等待电量变化"}，到 100% 仍未确认则结束并提醒"
        Phase.RETRY_ON, Phase.RETRY_OFF -> "结果未确认，等待重试（${c.attempts}/3）"
        else -> status.message
    }
    private fun roundAlert() {
        val c = cycle ?: return
        val message = if (c.offRequested) "插座关闭失败或未确认，请检查网络或插座。" else "插座开启失败或未确认，请检查网络或插座。"
        getSystemService(NotificationManager::class.java).notify(2, notification(routeMessage(message + "\n" + describe(c)), false))
    }
    private fun fullBatteryFailure() {
        finishCycle("电量已达 100%，仍无法确认插座已关闭；不再重试，保护已结束，请手动检查插座", false)
    }
    private var lastNotification: String? = null
    private fun routeMessage(message: String) = "$message\n控制模式：${controlMode.label} · 最近路线：${controls.lastRoute}" +
        controls.fallbackReason.takeIf { it.isNotBlank() }?.let { "\n备选原因：$it" }.orEmpty()
    private fun publish(rawMessage: String) {
        val message = routeMessage(rawMessage)
        if (!restoring || restorePhase == null) cycle?.let { status.save(it, message) }
        if (message != lastNotification) {
            getSystemService(NotificationManager::class.java).notify(1, notification(message, true)); lastNotification = message
        }
    }
    private fun stopCycle() {
        cycle?.stop()
        finishCycle("已停止保护；不另行改变插座状态，已发送的命令可能已生效", false, false)
    }
    private fun fail(message: String) {
        if (cycle == null) cycle = ChargingCycle(status.threshold, status.lowerThreshold.coerceAtMost(status.threshold - 1))
        cycle!!.fail()
        finishCycle(message, false)
    }
    private fun finishCycle(rawMessage: String, success: Boolean, showResult: Boolean = true) {
        val message = routeMessage(rawMessage)
        generation++; handler.removeCallbacksAndMessages(null); future?.cancel(true)
        status.clearResume()
        running = false
        cancelAlarm()
        cycle?.let { status.save(it, message) }
        if (registered) { unregisterReceiver(battery); registered = false }
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (showResult) getSystemService(NotificationManager::class.java).notify(2,
            notification(message, false, success))
        stopSelf()
    }
    private fun notification(message: String, ongoing: Boolean, success: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, if (ongoing) RUN_CHANNEL else RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(if (ongoing) "电量循环保护" else if (success) "已确认断电" else "电量循环保护需要检查")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(ongoing)
        if (ongoing) {
            val stop = PendingIntent.getService(this, 1, Intent(this, ChargingMonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "停止保护", stop).build())
        }
        return builder.build()
    }
    private fun holdWake(ms: Long) { wake.acquire(ms) }
    private fun releaseWake() { if (wake.isHeld) wake.release() }
    private fun safeFailure(error: Throwable): String = when (error) {
        is LocalUnavailableException -> error.message ?: "局域网路线不可用"
        is java.net.SocketTimeoutException -> "控制请求超时，请检查互联网和设备在线状态"
        is UserFacingException, is IllegalArgumentException -> error.message ?: "参数或响应无效"
        is InterruptedException -> "操作已取消"
        else -> "连接或存储操作未完成，请检查所选路线、网络和设备配置"
    }
    override fun onDestroy() {
        generation++; handler.removeCallbacksAndMessages(null); future?.cancel(true); worker.shutdownNow()
        if (registered) unregisterReceiver(battery)
        cancelAlarm(); if (instance === this) instance = null
        releaseWake(); running = false
        super.onDestroy()
    }
}
