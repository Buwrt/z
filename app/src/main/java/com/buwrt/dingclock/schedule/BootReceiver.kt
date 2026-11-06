package com.buwrt.dingclock.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.buwrt.dingclock.data.SettingsStore
import java.time.ZonedDateTime

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = SettingsStore(context)
        val settings = store.load()
        if (!settings.enabled) return

        AlarmScheduler.reschedule(context, store)
        CatchUpScheduler.ensurePeriodic(context)

        // 开机/改时间后如果已经错过了打卡点，尽快补一次
        val pending = CatchUpPlanner.pendingTask(settings, store, ZonedDateTime.now())
        if (pending != null) {
            store.appendLog("进入补跑窗口：${pending.title}")
            CatchUpScheduler.runNow(context)
        }
    }
}
