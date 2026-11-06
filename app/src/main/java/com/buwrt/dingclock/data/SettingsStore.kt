package com.buwrt.dingclock.data

import android.content.Context
import com.buwrt.dingclock.model.Backend
import com.buwrt.dingclock.model.RunStatus
import com.buwrt.dingclock.model.Settings
import com.buwrt.dingclock.model.TaskType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

data class RunRecord(
    val date: String,
    val time: Long,
    val status: RunStatus,
    val message: String
) {
    fun toJson(): String = JSONObject()
        .put("date", date)
        .put("time", time)
        .put("status", status.name)
        .put("message", message)
        .toString()

    companion object {
        fun fromJson(raw: String?): RunRecord? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                RunRecord(
                    date = o.optString("date"),
                    time = o.optLong("time", 0L),
                    status = RunStatus.entries.firstOrNull { it.name == o.optString("status") }
                        ?: RunStatus.FAILED,
                    message = o.optString("message")
                )
            }.getOrNull()
        }
    }
}

class SettingsStore(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        enabled = sp.getBoolean("enabled", false),
        startEnabled = sp.getBoolean("start_enabled", true),
        startHour = sp.getInt("start_hour", 9),
        startMinute = sp.getInt("start_minute", 0),
        endEnabled = sp.getBoolean("end_enabled", true),
        endHour = sp.getInt("end_hour", 18),
        endMinute = sp.getInt("end_minute", 0),
        weekdaysMask = sp.getInt("weekdays", 0b0011111),
        offsetSeconds = sp.getInt("offset", 0),
        randomDelaySeconds = sp.getInt("random", 0),
        retries = sp.getInt("retries", 3),
        launchWaitMs = sp.getInt("launch_wait", 8000),
        backend = Backend.fromName(sp.getString("backend", null)),
        catchUpEnabled = sp.getBoolean("catch_up", true),
        catchUpWindowMinutes = sp.getInt("catch_up_window", 120),
        dingTalkPackage = sp.getString("package", "com.alibaba.android.rimet")
            ?: "com.alibaba.android.rimet",
        startKeywords = sp.getString("start_keywords", "上班打卡,打卡") ?: "上班打卡,打卡",
        endKeywords = sp.getString("end_keywords", "下班打卡,打卡") ?: "下班打卡,打卡",
        excludeKeywords = sp.getString(
            "exclude_keywords",
            "考勤打卡,打卡记录,打卡设置,打卡规则,打卡时间,打卡成功,已打卡,更新打卡,外勤打卡,补卡"
        ) ?: "考勤打卡,打卡记录,打卡设置,打卡规则,打卡时间,打卡成功,已打卡,更新打卡,外勤打卡,补卡",
        autoNavigate = sp.getBoolean("auto_navigate", true),
        startCoordX = sp.getInt("start_x", -1),
        startCoordY = sp.getInt("start_y", -1),
        endCoordX = sp.getInt("end_x", -1),
        endCoordY = sp.getInt("end_y", -1)
    )

    fun save(s: Settings) {
        sp.edit()
            .putBoolean("enabled", s.enabled)
            .putBoolean("start_enabled", s.startEnabled)
            .putInt("start_hour", s.startHour)
            .putInt("start_minute", s.startMinute)
            .putBoolean("end_enabled", s.endEnabled)
            .putInt("end_hour", s.endHour)
            .putInt("end_minute", s.endMinute)
            .putInt("weekdays", s.weekdaysMask)
            .putInt("offset", s.offsetSeconds)
            .putInt("random", s.randomDelaySeconds)
            .putInt("retries", s.retries)
            .putInt("launch_wait", s.launchWaitMs)
            .putString("backend", s.backend.name)
            .putBoolean("catch_up", s.catchUpEnabled)
            .putInt("catch_up_window", s.catchUpWindowMinutes)
            .putString("package", s.dingTalkPackage)
            .putString("start_keywords", s.startKeywords)
            .putString("end_keywords", s.endKeywords)
            .putString("exclude_keywords", s.excludeKeywords)
            .putBoolean("auto_navigate", s.autoNavigate)
            .putInt("start_x", s.startCoordX)
            .putInt("start_y", s.startCoordY)
            .putInt("end_x", s.endCoordX)
            .putInt("end_y", s.endCoordY)
            .apply()
    }

    fun setNextRunAt(ms: Long) {
        sp.edit().putLong("next_at", ms).apply()
    }

    fun nextRunAt(): Long = sp.getLong("next_at", 0L)

    fun lastRun(task: TaskType): RunRecord? =
        RunRecord.fromJson(sp.getString("last_${task.name}", null))

    fun saveLastRun(task: TaskType, record: RunRecord) {
        sp.edit().putString("last_${task.name}", record.toJson()).apply()
    }

    fun hasSuccessfulRunToday(task: TaskType, date: LocalDate): Boolean {
        val last = lastRun(task) ?: return false
        return last.date == date.toString() && last.status.countedAsDone
    }

    fun appendLog(line: String) {
        val entry = "${timestamp()}  $line"
        synchronized(LOG_LOCK) {
            val list = currentLogs().toMutableList()
            list.add(entry)
            while (list.size > MAX_LOG_LINES) list.removeAt(0)
            sp.edit().putString(KEY_LOGS, list.joinToString("\n")).apply()
        }
    }

    fun logs(): String = currentLogs().joinToString("\n")

    fun clearLogs() {
        sp.edit().remove(KEY_LOGS).apply()
    }

    private fun currentLogs(): List<String> {
        val raw = sp.getString(KEY_LOGS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    private fun timestamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    companion object {
        private const val PREFS_NAME = "dingclock"
        private const val KEY_LOGS = "logs"
        private const val MAX_LOG_LINES = 300
        private val LOG_LOCK = Any()
    }
}
