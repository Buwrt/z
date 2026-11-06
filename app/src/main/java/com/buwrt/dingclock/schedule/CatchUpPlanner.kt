package com.buwrt.dingclock.schedule

import com.buwrt.dingclock.data.SettingsStore
import com.buwrt.dingclock.model.RunStatus
import com.buwrt.dingclock.model.Settings
import com.buwrt.dingclock.model.TaskType
import java.time.ZonedDateTime

/**
 * 补跑：定时闹钟被系统吞掉、手机在打卡时间之后才开机等情况下，在时间窗口内补执行一次。
 */
object CatchUpPlanner {

    fun pendingTask(
        s: Settings,
        store: SettingsStore,
        now: ZonedDateTime = ZonedDateTime.now()
    ): TaskType? {
        if (!s.enabled || !s.catchUpEnabled) return null

        val date = now.toLocalDate()
        val bit = 1 shl (date.dayOfWeek.value - 1)
        if (s.weekdaysMask and bit == 0) return null

        for (task in listOf(TaskType.CLOCK_IN, TaskType.CLOCK_OUT)) {
            val enabled = if (task == TaskType.CLOCK_IN) s.startEnabled else s.endEnabled
            if (!enabled) continue

            val trigger = NextRunPlanner.triggerAt(s, task, date, now.zone)
            if (now.isBefore(trigger)) continue
            if (now.isAfter(trigger.plusMinutes(s.catchUpWindowMinutes.toLong()))) continue
            if (store.hasSuccessfulRunToday(task, date)) continue

            // 刚失败过就先不反复重试，避免每 30 分钟骚扰一次
            val last = store.lastRun(task)
            if (last != null && last.status == RunStatus.FAILED &&
                now.toInstant().toEpochMilli() - last.time < 10 * 60_000L
            ) {
                continue
            }
            return task
        }
        return null
    }
}
