package io.github.chargercontroller

import android.app.Activity
import android.app.Dialog
import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.*
import io.github.chargercontroller.data.*
import io.github.chargercontroller.network.*
import io.github.chargercontroller.core.ChargingCycle
import io.github.chargercontroller.core.ControlMode
import io.github.chargercontroller.core.LocalUnavailableException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: CredentialStore
    private var account = XiaomiAccountRepository()
    private var session: CloudSession? = null
    private var device: PlugDevice? = null
    private var pendingLogin: LoginStep? = null
    private var selectedRegion = "cn"
    private val worker = Executors.newSingleThreadExecutor()
    private val logs = ArrayDeque<String>()
    private var busy = false
    private lateinit var ui: FluentScreen
    private var lastProtectionEvent = ""
    private var lastLoggedPhase = ""
    private lateinit var accountStatus: TextView
    private lateinit var deviceStatus: TextView
    private lateinit var result: TextView
    private lateinit var logView: TextView
    private lateinit var operationStatus: TextView
    private lateinit var controls: ControlPreferences
    private lateinit var routeStatus: TextView
    private lateinit var protection: ProtectionStore
    private lateinit var batteryStatus: TextView
    private lateinit var protectionStatus: TextView
    private lateinit var thresholdInput: EditText
    private lateinit var lowerThresholdInput: EditText
    private var pendingThreshold: Int? = null
    private var pendingLowerThreshold: Int? = null
    private var batteryRegistered = false
    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { updateProtection() } }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val percent = ChargingCycle.percentage(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            batteryStatus.text = "当前电量：${percent?.let { "$it%" } ?: "未知"} · ${if (plugged) "已连接电源" else "未连接电源"}"
            if (::ui.isInitialized) ui.updateBattery(percent, plugged)
        }
    }
    private var verificationDialog: Dialog? = null
    internal var currentSheet: FluentSheet? = null
        private set
    private var availableDevices = emptyList<PlugDevice>()
    private var permissionNotification: FluentToggle? = null
    private var permissionBackground: FluentToggle? = null
    private var sheetResult: TextView? = null
    private val ink = Fluent.ink
    private val accent = Fluent.blue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Fluent.canvas
        window.navigationBarColor = Color.rgb(250, 250, 250)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        store = CredentialStore(this)
        protection = ProtectionStore(this)
        controls = ControlPreferences(this)
        ChargingMonitorService.channels(this)
        var restoreError: Exception? = null
        try {
            session = store.get("session")?.let(CloudSession::fromJson)
            device = store.get("device")?.let(PlugDevice::fromJson)
            selectedRegion = session?.region ?: "cn"
        } catch (e: Exception) { restoreError = e }
        ui = FluentScreen(this, protection.lowerThreshold, protection.threshold, controls.mode, ::handleUiAction)
        accountStatus = ui.accountStatus; deviceStatus = ui.deviceStatus; result = ui.result
        logView = ui.logView; operationStatus = ui.operationStatus; routeStatus = ui.routeStatus
        batteryStatus = ui.batteryStatus; protectionStatus = ui.protectionStatus
        thresholdInput = ui.highInput; lowerThresholdInput = ui.lowInput
        setContentView(ui.root)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { if (!ui.back()) finish() }
        ui.root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        ui.root.requestApplyInsets()
        savedInstanceState?.let { saved ->
            saved.getStringArrayList("ui_logs")?.forEach { logs.addLast(it) }
            val draftMode = runCatching { ControlMode.valueOf(saved.getString("ui_mode").orEmpty()) }.getOrDefault(controls.mode)
            ui.restoreDraft(saved.getString("ui_low") ?: protection.lowerThreshold.toString(),
                saved.getString("ui_high") ?: protection.threshold.toString(), draftMode, saved.getBoolean("ui_dirty"))
            ui.show(saved.getString("ui_page")?.takeIf { it in listOf("home", "protection", "settings", "logs") } ?: "home")
            ui.updateLogs(logs.toList())
        }
        restoreError?.let { record(safeMessage(it)) }
        updateLabels(); updateProtection()
    }
    private fun handleUiAction(key: String) {
        if (key == "stop" || key == "toggle-guard" && ChargingMonitorService.running) { stopProtection(); return }
        if (key == "logs") { ui.showLogs(); return }
        if (key == "account") { accountDialog(); return }
        if (key == "devices") { devicesDialog(); return }
        if (key == "advanced") { advancedDialog(); return }
        if (key == "back-settings") { ui.show("settings"); return }
        if (key == "permissions") { backgroundSettings(); return }
        if (key == "about") { aboutDialog(); return }
        if (key == "clear-logs") {
            sheet("clear", "清空运行记录？") { panel ->
                panel.note("将清空当前运行记录，不会影响保护任务和设备设置。")
                panel.actions(panel.button("保留记录", "keep-logs", false) { panel.dismiss() },
                    panel.button("清空记录", "confirm-clear") { logs.clear(); ui.updateLogs(emptyList()); operationStatus.text = "尚无操作记录"; panel.dismiss() })
            }
            return
        }
        if (ChargingMonitorService.running) { record("请先停止充电保护，再修改配置或手动控制"); return }
        if (busy) { record("已有操作进行中，请稍候"); return }
        when (key) {
            "toggle-guard" -> if (ui.dirty) { ui.show("protection"); record("请先保存保护设置，再开始保护") } else startProtection()
            "save" -> {
                if (!ui.validate()) return
                val low = lowerThresholdInput.text.toString().toInt(); val high = thresholdInput.text.toString().toInt()
                runCatching { protection.configure(high, low); controls.mode = ui.selectedMode }.fold({
                    ui.saved(low, high); updateProtection(); record("保护设置已保存，尚未启动保护")
                }, { record(safeMessage(it)) })
            }
            "on" -> operate(true)
            "off" -> operate(false)
            "read" -> operate(null)
            "account" -> accountDialog()
            "devices" -> devicesDialog()
            "advanced" -> advancedDialog()
        }
    }
    private fun stopProtection() {
        if (ChargingMonitorService.running) startService(Intent(this, ChargingMonitorService::class.java).setAction(ChargingMonitorService.STOP))
        else { if (protection.active) protection.interrupt(); updateProtection(); record("当前没有运行中的保护任务") }
    }
    private fun sheet(kind: String, title: String, secret: Boolean = false, build: (FluentSheet) -> Unit): FluentSheet {
        currentSheet?.dismiss()
        val panel = FluentSheet(this, kind, title, secret)
        currentSheet = panel
        panel.setOnDismissListener { if (currentSheet === panel) currentSheet = null }
        build(panel); panel.show()
        return panel
    }
    private fun editableAction(action: () -> Unit) {
        if (ChargingMonitorService.running || busy) { record("请先停止保护并等待当前操作完成"); return }
        action()
    }
    private fun accountDialog() {
        sheet("account", "小米账号") { panel ->
            val saved = session
            panel.centered("user", if (saved == null) "连接小米账号" else "账号已连接",
                if (saved == null) "使用手机号与验证码完成登录\n即可使用云端备选控制"
                else "${if (saved.region == "cn") "中国大陆" else saved.region} · ${if (controls.cloudExpired) "会话已失效，请重新登录" else "云端会话已保存"}\n设备凭据已安全保存")
            panel.note(if (saved == null) "将在小米官方网页完成登录，不保存账号密码。" else "账号会话已加密保存，局域网与云端共用当前目标插座。")
            if (saved == null) panel.action("手机号 / 验证码登录", "sms-login") { editableAction { panel.dismiss(); smsLogin() } }
            else panel.action("退出登录", "logout", false) { editableAction {
                panel.dismiss(); task("退出小米账号", { store.put("session", null) }) {
                    session = null; pendingLogin = null; account = XiaomiAccountRepository(); availableDevices = emptyList(); controls.cloudExpired = false; clearWebCookies(); updateLabels()
                }
            } }
            panel.action("登录与设备管理", "account-options", false) { editableAction { accountOptions() } }
            panel.busy(busy || ChargingMonitorService.running)
        }
    }
    private fun accountOptions() {
        sheet("account-options", "登录与设备管理") { panel ->
            panel.row("账号地区", regionLabel(selectedRegion))
            panel.action("更改地区", "account-region", false) { editableAction { regionDialog() } }
            panel.action("手机号 / 验证码登录", "sms-login") { editableAction { panel.dismiss(); smsLogin() } }
            panel.actions(panel.button("密码登录（备用）", "password-login", false) { editableAction { panel.dismiss(); loginDialog() } },
                panel.button("继续身份验证", "resume-login", false) { editableAction { panel.dismiss(); pendingLogin?.let(::loginStep) ?: record("没有待处理的验证，请先登录") } })
            panel.action("获取 / 刷新设备列表", "load-devices", false) { editableAction { panel.dismiss(); loadDevices() } }
        }
    }
    private fun regionLabel(region: String) = mapOf("cn" to "中国大陆", "de" to "欧洲", "i2" to "印度", "ru" to "俄罗斯", "sg" to "新加坡", "us" to "美国")[region] ?: region
    private fun regionDialog() {
        sheet("region", "账号地区") { panel ->
            panel.note("请选择与米家相同的地区。地区用于登录和获取设备列表。")
            XiaomiAccountRepository.REGIONS.forEach { region ->
                panel.action("${if (region == selectedRegion) "✓  " else ""}${regionLabel(region)} · $region", "region-$region", false) {
                    editableAction { selectedRegion = region; accountDialog() }
                }
            }
        }
    }
    private fun devicesDialog() {
        sheet("devices", "目标插座") { panel ->
            panel.note("选择充电器实际连接的插座。" + if (ChargingMonitorService.running) "保护运行中，请先停止保护再切换设备。" else "")
            val devices = (availableDevices + listOfNotNull(device)).distinctBy { it.did.ifBlank { it.name + it.ip } }
            if (devices.isEmpty()) panel.note("尚未选择插座，请从小米账号获取设备列表，或手动导入设备凭据。")
            devices.forEachIndexed { index, picked ->
                val chosen = device?.let { it.did == picked.did && it.name == picked.name && it.ip == picked.ip } == true
                panel.device(picked.name, if (picked.model == "cuco.plug.v3") "米家智能插座 3 · ${if (chosen) "当前目标" else "状态待查询"}" else "${picked.model} · 本版不支持",
                    chosen, picked.model == "cuco.plug.v3", "device-choice-$index") {
                    editableAction {
                        if (chosen && picked == device) { panel.dismiss(); return@editableAction }
                        try { PlugAccess.validate(this, picked) } catch (e: Exception) { deviceDialog(picked); return@editableAction }
                        panel.dismiss(); task("加密保存设备凭据", { store.put("device", picked.json()); picked }) {
                            device = it; result.text = "状态：配置已更新，待查询"; ui.showPlug(null, "配置已更新，待查询"); updateLabels()
                        }
                    }
                }
            }
            panel.action("获取 / 刷新设备列表", "load-devices", false) { editableAction { loadDevices() } }
            panel.action("手动导入设备", "import-device", false) { editableAction { deviceDialog(device) } }
            panel.busy(busy || ChargingMonitorService.running)
        }
    }
    private fun advancedDialog() {
        sheet("advanced", "高级配置") { panel ->
            panel.note("日常使用无需修改。设备凭据已加密保存在本机。")
            panel.field("局域网 IP", device?.ip.orEmpty(), "advanced-ip", editable = false)
            panel.field("设备 Token", if (device?.token.isNullOrBlank()) "尚未保存" else "•••• •••• •••• ••••", "advanced-token", editable = false)
            panel.row("目标型号", device?.model ?: "cuco.plug.v3")
            panel.actions(panel.button("测试局域网", "test-lan", false) { editableAction { operate(null, ControlMode.LAN) } },
                panel.button("测试云端", "test-cloud", false) { editableAction { operate(null, ControlMode.CLOUD) } })
            sheetResult = panel.note(result.text.toString()).apply { visibility = if (result.text.toString() == "状态：未查询") View.GONE else View.VISIBLE }
            panel.note("仅云端路线依赖账号会话和设备标识，不要求填写局域网 IP 或 Token。")
            panel.actions(panel.link("编辑 IP / 设备凭据", "edit-device") { editableAction { deviceDialog(device) } },
                panel.link("详细运行状态", "diagnostics") {
                    sheet("diagnostics", "详细运行状态") { details -> details.note(result.text.toString()); details.note(routeStatus.text.toString()); details.note(protectionStatus.text.toString()) }
                })
            panel.busy(busy || ChargingMonitorService.running)
        }
    }
    private fun aboutDialog() {
        sheet("about", "关于") { panel ->
            panel.centered("shield", "充电守护", "版本 ${packageManager.getPackageInfo(packageName, 0).versionName}\n首页 / 保护 / 设置")
            panel.note("米家智能插座 3 · ZNCZ301KK\n凭据通过 Android Keystore 加密保存，不保存账号密码。停止保护不会另行改变插座状态。")
            panel.action("知道了", "about-done", false) { panel.dismiss() }
            panel.body.addView(panel.link("清除账号与设备配置", "clear-config") { clearConfiguration() }, LinearLayout.LayoutParams(-1, panel.dp(44)).apply { topMargin = panel.dp(8) })
        }
    }
    private fun clearConfiguration() {
        if (ChargingMonitorService.running || busy) { record("请先停止保护并等待当前操作完成"); return }
        sheet("clear-config", "清除本机配置？") { panel ->
            panel.note("删除本 App 的账号会话和设备凭据，不改变插座状态。")
            panel.actions(panel.button("保留配置", "keep-config", false) { panel.dismiss() }, panel.button("清除配置", "confirm-clear-config") {
                editableAction {
                    panel.dismiss(); task("清除本机配置", { store.clear() }) {
                        clearWebCookies(); account = XiaomiAccountRepository(); session = null; device = null; availableDevices = emptyList(); pendingLogin = null; controls.cloudExpired = false
                        result.text = "状态：未查询"; ui.showPlug(null, "尚未选择插座"); updateLabels()
                    }
                }
            })
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::ui.isInitialized) {
            outState.putString("ui_page", ui.selectedPage); outState.putString("ui_low", lowerThresholdInput.text.toString())
            outState.putString("ui_high", thresholdInput.text.toString()); outState.putString("ui_mode", ui.selectedMode.name)
            outState.putBoolean("ui_dirty", ui.dirty); outState.putStringArrayList("ui_logs", ArrayList(logs))
        }
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Activity back dispatch")
    override fun onBackPressed() { if (!ui.back()) super.onBackPressed() }
    private fun updateProtection() {
        if (!::protectionStatus.isInitialized) return
        val active = ChargingMonitorService.running
        val message = if (protection.active && !active) "上次保护未在运行，请重新开始" else protection.message
        val time = protection.updatedAt.takeIf { it > 0 }?.let { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it)) }
        protectionStatus.text = listOfNotNull(message, time?.let { "最近更新：$it" },
            protection.sampledAt.takeIf { it > 0 }?.let { "电量复查：${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))} · ${protection.sampleSource}" },
            "后台电池限制：" + if (getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) "已解除" else "未解除（建议在后台设置中允许）", device?.let { "控制对象：${it.name}" }).joinToString("\n")
        updateRouteLabel()
        thresholdInput.isEnabled = !active
        lowerThresholdInput.isEnabled = !active
        ui.updateState(active, busy, if (protection.active && !active) "INTERRUPTED" else protection.phase, message, protection.lowerThreshold, protection.threshold)
        if (currentSheet?.kind in listOf("account", "account-options", "advanced", "devices", "edit-device", "password-login", "region")) currentSheet?.busy(active || busy)
        val event = "${protection.phase}:${protection.updatedAt}"
        if (protection.updatedAt > 0 && event != lastProtectionEvent) {
            lastProtectionEvent = event
            // Battery sampling also publishes status; log phase transitions, not every battery tick.
            val phaseKey = protection.phase
            if (lastLoggedPhase != phaseKey) { lastLoggedPhase = phaseKey; record(message) }
        }
    }
    private fun updateRouteLabel() {
        if (::routeStatus.isInitialized) routeStatus.text = "最近尝试路线：${controls.lastRoute}" +
            controls.fallbackReason.takeIf { it.isNotBlank() }?.let { "\n备选原因：$it" }.orEmpty() +
            if (controls.cloudExpired) "\n云端会话已失效，请重新登录；局域网仍可使用" else ""
    }
    private fun startProtection() {
        val config = device ?: run { record("请先选择目标插座并完成连接测试"); return }
        try { PlugAccess.validate(this, config) } catch (e: Exception) { record(safeMessage(e)); return }
        val threshold = thresholdInput.text.toString().toIntOrNull()
        if (threshold == null || threshold !in 2..100) { thresholdInput.error = "请输入 2–100 的整数"; return }
        val low = lowerThresholdInput.text.toString().toIntOrNull()
        if (low == null || low !in 1 until threshold) {
            lowerThresholdInput.error = "开启阈值须为正整数，并低于断电阈值"; return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingThreshold = threshold
            pendingLowerThreshold = low
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 80)
            return
        }
        launchProtection(threshold, low)
    }
    private fun launchProtection(threshold: Int, low: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || listOf(ChargingMonitorService.RUN_CHANNEL, ChargingMonitorService.RESULT_CHANNEL)
                .any { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }) {
            record("请先在通知设置中允许保护运行与结果通知，再启动任务")
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            return
        }
        protection.configure(threshold, low)
        try {
            startForegroundService(Intent(this, ChargingMonitorService::class.java).setAction(ChargingMonitorService.START)
                .putExtra("threshold", threshold).putExtra("lower_threshold", low))
            record("正在启动本次充电保护")
        } catch (e: Exception) { record("无法启动前台保护，请保持 App 在前台后重试") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 80) {
            val threshold = pendingThreshold; pendingThreshold = null
            val low = pendingLowerThreshold; pendingLowerThreshold = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && threshold != null && low != null) launchProtection(threshold, low)
            else record("未授予通知权限，保护未启动；可在通知与后台设置中开启")
        }
    }
    private fun notificationsAllowed(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && listOf(ChargingMonitorService.RUN_CHANNEL, ChargingMonitorService.RESULT_CHANNEL)
            .none { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
    }
    private fun backgroundSettings() {
        sheet("permissions", "通知与后台") { panel ->
            panel.note("允许通知与后台运行，让保护状态及时可见。")
            permissionNotification = panel.toggle("保护与结果通知", "控制失败时及时提醒", notificationsAllowed(), "permission-notifications") {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            permissionBackground = panel.toggle("后台无限制", "减少锁屏后被系统中断的可能", getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName), "permission-background") {
                runCatching { startActivity(if (getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) else
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))) }
                    .onFailure { record("请在系统设置中打开本 App 的电池选项") }
            }
            panel.note("点击开关前往系统授权，返回后显示实际权限状态。部分小米手机还需在系统设置中开启自启动，并将省电策略设为无限制。")
            panel.action("完成", "permissions-done") { panel.dismiss() }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::protection.isInitialized) {
            protection.prefs.registerOnSharedPreferenceChangeListener(statusListener)
            updateLabels(); updateProtection()
            if (currentSheet?.kind == "permissions") {
                permissionNotification?.checked = notificationsAllowed()
                permissionBackground?.checked = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            }
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(batteryReceiver, filter)
            batteryRegistered = true
        }
    }
    override fun onPause() {
        if (batteryRegistered) { unregisterReceiver(batteryReceiver); batteryRegistered = false }
        if (::protection.isInitialized) protection.prefs.unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onPause()
    }
    private fun updateLabels() {
        if (!::ui.isInitialized) return
        val notifications = notificationsAllowed()
        val background = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        ui.updateIdentity(device?.name, notifications, background)
        ui.updateAccount(session != null, session?.region ?: selectedRegion, controls.cloudExpired)
        ui.updateSavedMode(controls.mode)
        accountStatus.text = session?.let { "已保存云端会话 · ${it.region}（${if (controls.cloudExpired) "已失效，请重新登录" else "请求时校验有效性"}）" } ?: "尚未取得云端会话"
        deviceStatus.text = device?.let { "${it.name}\n${it.model}\n局域网 IP：${it.ip}\nToken：已获取并加密保存" } ?: "尚未选择插座"
    }
    private fun smsLogin() {
        val region = selectedRegion
        busy = true
        updateProtection()
        record("正在准备手机号登录…")
        clearWebCookies {
            if (isDestroyed || isFinishing) return@clearWebCookies
            busy = false
            updateProtection()
            pendingLogin = null
            account = XiaomiAccountRepository()
            task("准备手机号登录", { account.beginSms(region) }, ::loginStep)
        }
    }
    private fun loginDialog() {
        sheet("password-login", "登录小米账号", true) { panel ->
            val user = panel.field("小米账号 / 手机号码", "", "login-user")
            val password = panel.field("密码", "", "login-password", secret = true)
            val error = panel.note("请输入账号和密码").apply { visibility = View.GONE; setTextColor(Fluent.red) }
            panel.note("仅向小米 HTTPS 服务发送登录请求，可能需要验证码或身份验证。")
            panel.action("登录", "password-submit") {
                editableAction {
                    val username = user.text.toString().trim(); val secret = password.text.toString()
                    if (username.isEmpty() || secret.isEmpty()) { error.visibility = View.VISIBLE; return@editableAction }
                    val region = selectedRegion
                    password.text.clear(); panel.dismiss(); clearWebCookies(); pendingLogin = null; account = XiaomiAccountRepository()
                    task("小米账号登录", { account.begin(username, secret, region) }, ::loginStep)
                }
            }
        }
    }
    private fun loginStep(step: LoginStep) {
        pendingLogin = if (step is LoginStep.Success) null else step
        when (step) {
            is LoginStep.Success -> task("保存账号会话", { store.put("session", step.session.json()); step.session }) {
                session = it; controls.cloudExpired = false; updateLabels(); updateRouteLabel(); clearWebCookies(); loadDevices()
            }
            is LoginStep.Captcha -> {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(step.image, 0, step.image.size)
                if (bitmap == null) { record("验证码图片无法解析，请重新登录"); return }
                sheet("captcha", "图形验证码", true) { panel ->
                    panel.body.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true })
                    val code = panel.field("图形验证码", "", "captcha-code")
                    panel.action("提交", "captcha-submit") { editableAction {
                        val value = code.text.toString().trim(); code.text.clear(); panel.dismiss()
                        task("提交图形验证码", { account.authenticate(value) }, ::loginStep)
                    } }
                }
            }
            is LoginStep.Verification -> sheet("verification", "小米要求身份验证", true) { panel ->
                panel.note("请在小米网页完成验证；也可尝试当前会话的短信 / 邮件验证码。")
                panel.action("打开验证网页", "verification-web") { panel.dismiss(); openVerification(step.url) }
                panel.action("获取验证码", "verification-otp", false) { editableAction { panel.dismiss(); task("发送验证码", { account.sendOtp() }, ::loginStep) } }
            }
            is LoginStep.Otp -> sheet("otp", "输入当前会话验证码", true) { panel ->
                val field = panel.field("${step.method}验证码", "", "otp-code").apply { inputType = InputType.TYPE_CLASS_NUMBER }
                panel.action("提交", "otp-submit") { editableAction {
                    val code = field.text.toString().trim(); field.text.clear(); panel.dismiss()
                    task("验证验证码", { account.submitOtp(code) }, ::loginStep)
                } }
            }
            is LoginStep.SmsWeb -> openVerification(step.url, smsLogin = true)
        }
    }
    @Suppress("SetJavaScriptEnabled")
    private fun openVerification(url: String, smsLogin: Boolean = false) {
        if (!XiaomiHttp.allowed(url)) { record("验证地址不受信任"); return }
        val cookies = CookieManager.getInstance(); cookies.setAcceptCookie(true)
        val urls = listOf("https://account.xiaomi.com/", "https://account.xiaomi.com/pass/", url)
        urls.forEach { u -> account.http.cookieHeader(u).split(';').filter { it.contains('=') }.forEach {
            cookies.setCookie(u, it.trim() + "; Path=/; Secure")
        } }
        cookies.flush()
        val dialog = XiaomiLoginDialog(this, url, smsLogin) { cookieUrls, finished ->
            if (busy) { finished(false) } else {
                cookieUrls.forEach { u ->
                    cookies.getCookie(u)?.split(';')?.filter { it.contains('=') }?.forEach {
                        account.http.importCookie(u, it.trim() + "; Path=/; Secure")
                    }
                }
                task("继续小米登录", { account.resume() }, { step -> finished(true); loginStep(step) }, { finished(false) })
            }
        }
        verificationDialog = dialog
        dialog.setOnDismissListener { verificationDialog = null; clearWebCookies() }
        dialog.show()
    }
    private fun loadDevices() {
        val saved = session ?: run { record("请先登录小米账号"); return }
        val current = saved.copy(region = selectedRegion)
        task("获取设备列表", {
            val devices = account.devices(current)
            store.put("session", current.json())
            devices
        }) { devices ->
            session = current; controls.cloudExpired = false; updateLabels(); updateRouteLabel()
            if (devices.isEmpty()) { record("该地区暂无设备，请核对米家地区和账号"); return@task }
            availableDevices = devices
            devicesDialog()
        }
    }
    private fun deviceDialog(existing: PlugDevice?) {
        sheet("edit-device", "编辑设备凭据", true) { panel ->
            val name = panel.field("设备名称", existing?.name ?: "米家智能插座 3", "device-name")
            val ip = panel.field("局域网 IP", existing?.ip.orEmpty(), "device-ip").apply { inputType = InputType.TYPE_CLASS_PHONE }
            val token = panel.field("设备 Token", existing?.token.orEmpty(), "device-token", secret = true)
            panel.note("型号：cuco.plug.v3。云端需要从设备列表取得 DID；仅云端无需 IP 或 Token。")
            val error = panel.note("").apply { visibility = View.GONE; setTextColor(Fluent.red) }
            panel.action("保存设备", "save-device") {
                editableAction {
                    val config = PlugDevice(name.text.toString().trim().ifBlank { "米家智能插座 3" }, "cuco.plug.v3",
                        existing?.did.orEmpty(), ip.text.toString().trim(), token.text.toString().trim())
                    try { PlugAccess.validate(this, config) } catch (e: Exception) { error.text = safeMessage(e); error.visibility = View.VISIBLE; return@editableAction }
                    token.text.clear(); panel.dismiss()
                    task("加密保存设备凭据", { store.put("device", config.json()); config }) {
                        device = it; result.text = "状态：配置已更新，待查询"; ui.showPlug(null, "配置已更新，点击刷新确认"); updateLabels()
                    }
                }
            }
        }
    }
    private fun operate(on: Boolean?, mode: ControlMode = controls.mode) {
        val config = device ?: run { record("请先选择设备或导入 IP / Token"); return }
        result.text = "状态：正在查询 / 确认…"
        if (currentSheet?.kind == "advanced") { sheetResult?.text = result.text; sheetResult?.visibility = View.VISIBLE }
        val label = when (on) { true -> "开启插座"; false -> "关闭插座"; null -> "读取插座状态" }
        task(label, { synchronized(PlugAccess.lock) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val client = PlugAccess.controller(this, mode); if (on == null) client.read(config) else client.setPower(config, on)
        } }, { status ->
            result.text = getString(R.string.confirmed_status, if (status.power) "开启" else "关闭",
                status.watts?.let { String.format(Locale.getDefault(), "%.1f W", it) } ?: "未提供",
                status.kwh?.let { String.format(Locale.getDefault(), "%.2f kWh", it) } ?: "未提供") +
                "\n确认路线：${status.route?.label ?: "未知"}" + status.fallbackReason?.let { "\n备选原因：$it" }.orEmpty()
            ui.showPlug(status.power, "${status.route?.label ?: "未知路线"}已读回确认" + status.watts?.let { " · %.1f W".format(it) }.orEmpty())
            updateRouteLabel()
            if (currentSheet?.kind == "advanced") { sheetResult?.text = result.text; sheetResult?.visibility = View.VISIBLE }
            record("${status.route?.label ?: "未知路线"}已读回确认插座${if (status.power) "开启" else "关闭"}")
        }, {
            result.text = "状态：结果未确认，请重新刷新"
            ui.showPlug(null, "结果未确认，请重新刷新")
            if (currentSheet?.kind == "advanced") { sheetResult?.text = result.text; sheetResult?.visibility = View.VISIBLE }
            record("本次尝试路线：${controls.lastRoute}" + controls.fallbackReason.takeIf { it.isNotBlank() }?.let { "；备选原因：$it" }.orEmpty())
        })
    }
    private fun <T> task(label: String, background: () -> T, success: (T) -> Unit) = task(label, background, success, {})
    private fun <T> task(label: String, background: () -> T, success: (T) -> Unit, failure: () -> Unit) {
        if (busy) { record("已有操作进行中，请稍候"); return }
        busy = true; updateProtection(); record("$label：进行中")
        worker.execute {
            val outcome = runCatching(background)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false; updateProtection()
                outcome.fold({ record("$label：完成"); success(it) }, {
                    if (it is CloudAuthException) { controls.cloudExpired = true; updateLabels() }
                    updateRouteLabel(); record("$label：${safeMessage(it)}"); failure()
                })
            }
        }
    }
    private fun safeMessage(error: Throwable): String = when (error) {
        is LocalUnavailableException -> error.message ?: "局域网路线不可用"
        is UserFacingException, is IllegalArgumentException -> error.message ?: "参数或响应无效"
        is java.net.UnknownHostException -> "无法解析服务地址，请检查互联网连接"
        is java.net.SocketTimeoutException -> "网络超时，请稍后重试"
        is javax.net.ssl.SSLException -> "HTTPS 连接失败，请检查手机时间和网络"
        is java.io.IOException -> "网络或存储访问失败，请检查连接后重试"
        is InterruptedException -> "操作已取消"
        else -> "操作未完成（${error.javaClass.simpleName}），请重新登录或检查配置"
    }
    private fun record(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.addFirst("$time  $message"); while (logs.size > 30) logs.removeLast()
        if (::logView.isInitialized) logView.text = logs.joinToString("\n")
        if (::operationStatus.isInitialized) operationStatus.text = message
        if (::ui.isInitialized) { ui.updateLogs(logs.toList()); ui.toast(message.substringBefore('\n')) }
    }
    private fun clearWebCookies(complete: (() -> Unit)? = null) {
        android.webkit.WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            complete?.invoke()
        }
    }
    override fun onDestroy() { if (::ui.isInitialized) ui.dispose(); currentSheet?.dismiss(); verificationDialog?.dismiss(); worker.shutdownNow(); super.onDestroy() }
}
