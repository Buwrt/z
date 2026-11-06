package com.buwrt.dingclock.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.buwrt.dingclock.data.SettingsStore
import com.buwrt.dingclock.model.TaskType
import com.buwrt.dingclock.service.ClockService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = TaskType.fromAction(intent.action) ?: return
        val store = SettingsStore(context)
        val settings = store.load()

        // 先把下一次闹钟排好，避免本次执行失败后整个计划断掉
        AlarmScheduler.reschedule(context, store)

        if (!settings.enabled) return
        store.appendLog("定时触发：${task.title}")

        try {
            ContextCompat.startForegroundService(context, ClockService.intent(context, task, false))
        } catch (t: Throwable) {
            Log.w(TAG, "startForegroundService failed", t)
            store.appendLog("无法启动前台服务：${t.message}")
            CatchUpScheduler.runNow(context)
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
