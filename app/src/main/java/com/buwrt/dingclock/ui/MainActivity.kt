package com.buwrt.dingclock.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.buwrt.dingclock.R
import com.buwrt.dingclock.data.SettingsStore
import com.buwrt.dingclock.databinding.ActivityMainBinding
import com.buwrt.dingclock.engine.RootShell
import com.buwrt.dingclock.engine.RootShellImpl
import com.buwrt.dingclock.engine.ShizukuShell
import com.buwrt.dingclock.model.Backend
import com.buwrt.dingclock.model.Settings
import com.buwrt.dingclock.model.TaskType
import com.buwrt.dingclock.schedule.AlarmScheduler
import com.buwrt.dingclock.schedule.CatchUpScheduler
import com.buwrt.dingclock.service.ClockService
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore

    private val weekdayChips = ArrayList<Chip>()
    private var startHour = 9
    private var startMinute = 0
    private var endHour = 18
    private var endMinute = 0

    private var shizukuPermissionListener: Shizuku.OnRequestPermissionResultListener? = null
    private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("未授予通知权限，打卡结果通知可能不显示")
            refreshPermissionStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)

        setupWeekdayChips()
        setupSpinner()
        setupListeners()
        setupShizukuListeners()
        loadSettings(store.load())
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        refreshBackendStatus()
    }

    override fun onDestroy() {
        shizukuPermissionListener?.let {
            runCatching { Shizuku.removeRequestPermissionResultListener(it) }
        }
        binderReceivedListener?.let {
            runCatching { Shizuku.removeBinderReceivedListener(it) }
        }
        super.onDestroy()
    }

    private fun setupWeekdayChips() {
        val names = listOf("一", "二", "三", "四", "五", "六", "日")
        names.forEachIndexed { index, name ->
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isCheckedIconVisible = true
                tag = index
                minWidth = 0
                setPadding(0, 0, 0, 0)
                textSize = 14f
            }
            weekdayChips += chip
            binding.chipWeekdays.addView(chip)
        }
    }

    private fun setupSpinner() {
        val labels = Backend.entries.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBackend.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnStartTime.setOnClickListener {
            pickTime(startHour, startMinute) { hour, minute ->
                startHour = hour
                startMinute = minute
                binding.btnStartTime.text = formatTime(hour, minute)
            }
        }
        binding.btnEndTime.setOnClickListener {
            pickTime(endHour, endMinute) { hour, minute ->
                endHour = hour
                endMinute = minute
                binding.btnEndTime.text = formatTime(hour, minute)
            }
        }

        binding.btnSave.setOnClickListener { saveAndEnable() }
        binding.btnStop.setOnClickListener { stopSchedule() }
        binding.btnTestIn.setOnClickListener { testRun(TaskType.CLOCK_IN) }
        binding.btnTestOut.setOnClickListener { testRun(TaskType.CLOCK_OUT) }

        binding.btnRootTest.setOnClickListener { testRoot() }
        binding.btnShizuku.setOnClickListener { requestShizuku() }
        binding.btnExactAlarm.setOnClickListener { requestExactAlarm() }
        binding.btnBattery.setOnClickListener { requestBattery() }
        binding.btnNotification.setOnClickListener { requestNotification() }

        binding.btnRefreshLogs.setOnClickListener { refreshLogs() }
        binding.btnClearLogs.setOnClickListener {
            store.clearLogs()
            refreshLogs()
        }
    }

    private fun setupShizukuListeners() {
        val received = Shizuku.OnBinderReceivedListener {
            runOnUiThread { refreshBackendStatus() }
        }
        binderReceivedListener = received
        runCatching { Shizuku.addBinderReceivedListenerSticky(received) }

        val permission = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                runOnUiThread {
                    toast(
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            "Shizuku 权限已授予"
                        } else {
                            "Shizuku 权限被拒绝"
                        }
                    )
                    refreshBackendStatus()
                    refreshPermissionStatus()
                }
            }
        }
        shizukuPermissionListener = permission
        runCatching { Shizuku.addRequestPermissionResultListener(permission) }
    }

    private fun loadSettings(s: Settings) {
        startHour = s.startHour
        startMinute = s.startMinute
        endHour = s.endHour
        endMinute = s.endMinute

        binding.btnStartTime.text = formatTime(startHour, startMinute)
        binding.btnEndTime.text = formatTime(endHour, endMinute)
        binding.switchStart.isChecked = s.startEnabled
        binding.switchEnd.isChecked = s.endEnabled
        setWeekdayMask(s.weekdaysMask)

        binding.etOffset.setText(s.offsetSeconds.toString())
        binding.etRandom.setText(s.randomDelaySeconds.toString())
        binding.etRetries.setText(s.retries.toString())
        binding.etLaunchWait.setText((s.launchWaitMs / 1000).toString())
        binding.etStartKeywords.setText(s.startKeywords)
        binding.etEndKeywords.setText(s.endKeywords)
        binding.etPackage.setText(s.dingTalkPackage)
        binding.etStartCoord.setText(
            if (s.startCoordX >= 0 && s.startCoordY >= 0) "${s.startCoordX},${s.startCoordY}" else ""
        )
        binding.etEndCoord.setText(
            if (s.endCoordX >= 0 && s.endCoordY >= 0) "${s.endCoordX},${s.endCoordY}" else ""
        )

        binding.spinnerBackend.setSelection(s.backend.ordinal)
    }

    private fun setWeekdayMask(mask: Int) {
        weekdayChips.forEachIndexed { index, chip ->
            chip.isChecked = mask and (1 shl index) != 0
        }
    }

    private fun weekdayMask(): Int {
        var mask = 0
        weekdayChips.forEachIndexed { index, chip ->
            if (chip.isChecked) mask = mask or (1 shl index)
        }
        return mask
    }

    private fun collectSettings(enabled: Boolean): Settings? {
        val offset = readInt(binding.etOffset, "提前/延后秒数") ?: return null
        if (offset !in -3600..3600) {
            toast("提前/延后秒数应在 -3600 到 3600 之间")
            return null
        }
        val random = readInt(binding.etRandom, "随机延迟") ?: return null
        if (random !in 0..3600) {
            toast("随机延迟应在 0 到 3600 秒之间")
            return null
        }
        val retries = readInt(binding.etRetries, "重试次数") ?: return null
        if (retries !in 1..8) {
            toast("重试次数应在 1 到 8 之间")
            return null
        }
        val launchWait = readInt(binding.etLaunchWait, "启动等待") ?: return null
        if (launchWait !in 3..60) {
            toast("启动等待应在 3 到 60 秒之间")
            return null
        }

        val mask = weekdayMask()
        if (mask == 0) {
            toast("请至少勾选一个重复日期")
            return null
        }

        val startEnabled = binding.switchStart.isChecked
        val endEnabled = binding.switchEnd.isChecked
        if (!startEnabled && !endEnabled) {
            toast("请至少启用上班或下班打卡")
            return null
        }
        if (startEnabled && endEnabled && startHour == endHour && startMinute == endMinute) {
            toast("上班和下班时间不能完全相同")
            return null
        }

        val startKeywords = binding.etStartKeywords.text?.toString()?.trim().orEmpty()
        val endKeywords = binding.etEndKeywords.text?.toString()?.trim().orEmpty()
        if (startEnabled && startKeywords.isEmpty()) {
            toast("请填写上班打卡按钮关键词")
            return null
        }
        if (endEnabled && endKeywords.isEmpty()) {
            toast("请填写下班打卡按钮关键词")
            return null
        }

        val pkg = binding.etPackage.text?.toString()?.trim().orEmpty()
        if (!pkg.matches(Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$"""))) {
            toast("包名格式不正确")
            return null
        }

        val startCoord = parseCoord(binding.etStartCoord.text?.toString())
        if (!binding.etStartCoord.text.isNullOrBlank() && startCoord == null) {
            toast("上班备用坐标格式应为 x,y")
            return null
        }
        val endCoord = parseCoord(binding.etEndCoord.text?.toString())
        if (!binding.etEndCoord.text.isNullOrBlank() && endCoord == null) {
            toast("下班备用坐标格式应为 x,y")
            return null
        }

        val old = store.load()
        return Settings(
            enabled = enabled,
            startEnabled = startEnabled,
            startHour = startHour,
            startMinute = startMinute,
            endEnabled = endEnabled,
            endHour = endHour,
            endMinute = endMinute,
            weekdaysMask = mask,
            offsetSeconds = offset,
            randomDelaySeconds = random,
            retries = retries,
            launchWaitMs = launchWait * 1000,
            backend = Backend.entries[binding.spinnerBackend.selectedItemPosition],
            catchUpEnabled = true,
            catchUpWindowMinutes = old.catchUpWindowMinutes,
            dingTalkPackage = pkg,
            startKeywords = startKeywords,
            endKeywords = endKeywords,
            excludeKeywords = old.excludeKeywords,
            autoNavigate = true,
            startCoordX = startCoord?.first ?: -1,
            startCoordY = startCoord?.second ?: -1,
            endCoordX = endCoord?.first ?: -1,
            endCoordY = endCoord?.second ?: -1
        )
    }

    private fun saveAndEnable() {
        val settings = collectSettings(enabled = true) ?: return
        store.save(settings)
        store.appendLog(
            "保存设置：上班 ${formatTime(settings.startHour, settings.startMinute)}，" +
                "下班 ${formatTime(settings.endHour, settings.endMinute)}"
        )
        AlarmScheduler.reschedule(this, store)
        CatchUpScheduler.ensurePeriodic(this)
        refreshAll()
        toast("已保存并启用")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExact()) {
            requestExactAlarm()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestNotification()
        }
    }

    private fun stopSchedule() {
        val settings = store.load().copy(enabled = false)
        store.save(settings)
        AlarmScheduler.cancel(this)
        store.setNextRunAt(0L)
        store.appendLog("已停止自动打卡")
        refreshAll()
        toast("已停止自动打卡")
    }

    private fun testRun(task: TaskType) {
        val settings = collectSettings(enabled = store.load().enabled) ?: return
        store.save(settings)
        refreshAll()
        toast("开始测试${task.title}，请保持手机解锁")
        try {
            ClockService.start(this, task, test = true)
        } catch (t: Throwable) {
            toast("启动失败：${t.message}")
            store.appendLog("测试启动失败：${t.message}")
            return
        }
        lifecycleScope.launch {
            repeat(4) {
                delay(1500)
                refreshLogs()
            }
        }
    }

    private fun refreshAll() {
        val settings = store.load()
        val nextAt = store.nextRunAt()
        binding.tvNextRun.text = if (settings.enabled && nextAt > 0) {
            "下次运行：${formatDateTime(nextAt)}"
        } else {
            "下次运行：未启用"
        }

        binding.tvLastResult.text = buildString {
            append(resultLine(TaskType.CLOCK_IN))
            append('\n')
            append(resultLine(TaskType.CLOCK_OUT))
        }

        refreshPermissionStatus()
        refreshLogs()
    }

    private fun resultLine(task: TaskType): String {
        val record = store.lastRun(task) ?: return "${task.title}：—"
        val time = formatDateTime(record.time)
        return "${task.title}：$time ${record.status.label}"
    }

    private fun refreshBackendStatus() {
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.probe() }
            val shizuku = ShizukuShell.ready()
            val installed = isPackageInstalled("moe.shizuku.privileged.api")
            binding.tvBackend.text = buildString {
                append("Root：").append(if (root) "可用" else "不可用")
                append("　Shizuku：")
                append(
                    when {
                        shizuku -> "已授权"
                        installed -> "未运行/未授权"
                        else -> "未安装"
                    }
                )
            }
            binding.btnShizuku.text = if (shizuku) "Shizuku：已授权" else "申请 Shizuku 权限"
            refreshPermissionStatus()
        }
    }

    private fun refreshPermissionStatus() {
        val exact = canScheduleExact()
        val battery = isIgnoringBattery()
        val notification = hasNotificationPermission()

        binding.btnExactAlarm.isEnabled = !exact
        binding.btnExactAlarm.text = if (exact) "精确闹钟：已授权" else "授予精确闹钟权限"

        binding.btnBattery.isEnabled = !battery
        binding.btnBattery.text = if (battery) "电池优化：已忽略" else "忽略电池优化（建议）"

        binding.btnNotification.isEnabled = !notification
        binding.btnNotification.text = if (notification) "通知：已授权" else "授予通知权限"

        binding.tvPermission.text = buildString {
            append("精确闹钟：").append(if (exact) "已授权" else "未授权")
            append("　电池优化：").append(if (battery) "已忽略" else "未忽略")
            append("　通知：").append(if (notification) "已授权" else "未授权")
        }
    }

    private fun refreshLogs() {
        val logs = store.logs()
        binding.tvLogs.text = logs.ifBlank { "暂无日志" }
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun requestExactAlarm() {
        if (canScheduleExact()) {
            toast("精确闹钟权限已授权")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(
                AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(intent) }
                .onFailure { openAppDetails() }
        }
    }

    private fun requestBattery() {
        if (isIgnoringBattery()) {
            toast("已忽略电池优化")
            return
        }
        val intent = Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasNotificationPermission()) {
                toast("通知权限已授予")
            } else {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            toast("当前系统版本无需申请通知权限")
        }
    }

    private fun requestShizuku() {
        if (ShizukuShell.ready()) {
            toast("Shizuku 已授权")
            return
        }
        if (!isPackageInstalled("moe.shizuku.privileged.api")) {
            toast("未安装 Shizuku，请先安装并启动 Shizuku")
            openUrl("https://shizuku.rikka.app/")
            return
        }
        if (!Shizuku.pingBinder()) {
            toast("Shizuku 服务未运行，请先启动 Shizuku")
            openShizukuApp()
            return
        }
        if (Shizuku.isPreV11()) {
            toast("Shizuku 版本过低，请升级到 v11 以上")
            return
        }
        runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("Shizuku 已授权")
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        }.onFailure { toast("请求失败：${it.message}") }
    }

    private fun testRoot() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { RootShellImpl().exec("id; echo EXIT_OK", 12_000).output }
                    .getOrElse { "执行失败：${it.message}" }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Root 检测")
                .setMessage(result.ifBlank { "没有输出" })
                .setPositiveButton("好", null)
                .show()
        }
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            runCatching { startActivity(intent) }.onFailure { openUrl("https://shizuku.rikka.app/") }
        } else {
            openUrl("https://shizuku.rikka.app/")
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("无法打开浏览器") }
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun pickTime(hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText("选择时间")
            .build()
        picker.addOnPositiveButtonClickListener {
            onPicked(picker.hour, picker.minute)
        }
        picker.show(supportFragmentManager, "time_picker")
    }

    private fun readInt(edit: TextInputEditText, label: String): Int? {
        val raw = edit.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return 0
        return raw.toIntOrNull() ?: run {
            toast("$label 必须是数字")
            null
        }
    }

    private fun parseCoord(raw: String?): Pair<Int, Int>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(',', '，', ' ').filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        if (x < 0 || y < 0) return null
        return x to y
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun isIgnoringBattery(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isPackageInstalled(pkg: String): Boolean = runCatching {
        packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private fun formatTime(hour: Int, minute: Int): String =
        String.format(Locale.CHINA, "%02d:%02d", hour, minute)

    private fun formatDateTime(ms: Long): String {
        if (ms <= 0) return "—"
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 100
    }
}
