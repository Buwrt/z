package com.buwrt.dingclock.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.buwrt.dingclock.data.SettingsStore
import com.buwrt.dingclock.model.TaskType
import com.buwrt.dingclock.ui.MainActivity
import java.time.ZonedDateTime

object AlarmScheduler {
    private const val REQ_CLOCK_IN = 4101
    private const val REQ_CLOCK_OUT = 4102

    fun reschedule(context: Context, store: SettingsStore = SettingsStore(context)) {
        val app = context.applicationContext
        val settings = store.load()

        cancel(app)

        if (!settings.enabled) {
            store.setNextRunAt(0L)
            return
        }

        val slot = NextRunPlanner.nextSlot(settings, ZonedDateTime.now())
        if (slot == null) {
            store.setNextRunAt(0L)
            return
        }

        val triggerAt = slot.at.toInstant().toEpochMilli()
        store.setNextRunAt(triggerAt)
        scheduleAt(app, slot.task, triggerAt)
    }

    fun scheduleAt(context: Context, task: TaskType, triggerAt: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, task)

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                return
            } catch (_: SecurityException) {
                // Android 12+ 未授权时降级为闹钟方式
            }
        }
        val show = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pending)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context, TaskType.CLOCK_IN))
        am.cancel(pendingIntent(context, TaskType.CLOCK_OUT))
    }

    private fun pendingIntent(context: Context, task: TaskType): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = task.action
        }
        return PendingIntent.getBroadcast(
            context,
            if (task == TaskType.CLOCK_IN) REQ_CLOCK_IN else REQ_CLOCK_OUT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
