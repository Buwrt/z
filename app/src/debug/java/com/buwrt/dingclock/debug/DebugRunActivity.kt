package com.buwrt.dingclock.debug

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.buwrt.dingclock.data.SettingsStore
import com.buwrt.dingclock.model.Backend
import com.buwrt.dingclock.model.TaskType
import com.buwrt.dingclock.service.ClockService

/**
 * 仅 debug 包可用：方便用 adb/root 直接触发一次打卡，而不必手动点界面。
 *
 * am start -n com.buwrt.dingclock/.debug.DebugRunActivity \
 *   --es task CLOCK_IN --es package com.buwrt.mockding --ez test true
 */
class DebugRunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = SettingsStore(this)
        var settings = store.load()

        intent.getStringExtra("package")
            ?.takeIf { it.isNotBlank() }
            ?.let { settings = settings.copy(dingTalkPackage = it) }
        intent.getStringExtra("startKeywords")
            ?.takeIf { it.isNotBlank() }
            ?.let { settings = settings.copy(startKeywords = it) }
        intent.getStringExtra("endKeywords")
            ?.takeIf { it.isNotBlank() }
            ?.let { settings = settings.copy(endKeywords = it) }
        intent.getStringExtra("backend")
            ?.let { raw -> Backend.entries.firstOrNull { it.name == raw } }
            ?.let { settings = settings.copy(backend = it) }

        store.save(settings.copy(enabled = true))

        val task = intent.getStringExtra("task")
            ?.let { raw -> TaskType.entries.firstOrNull { it.name == raw } }
            ?: TaskType.CLOCK_IN
        val test = intent.getBooleanExtra("test", true)

        ClockService.start(this, task, test)
        Toast.makeText(this, "Debug: ${task.title}", Toast.LENGTH_SHORT).show()
        finish()
    }
}
