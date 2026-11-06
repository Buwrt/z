package com.buwrt.dingclock.schedule

import com.buwrt.dingclock.model.Settings
import com.buwrt.dingclock.model.TaskType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * 纯计算：下一次该在什么时间执行哪个任务。无 Android 依赖，便于单元测试。
 */
object NextRunPlanner {

    data class Slot(val task: TaskType, val at: ZonedDateTime)

    fun nextSlot(s: Settings, now: ZonedDateTime = ZonedDateTime.now()): Slot? {
        if (!s.enabled) return null
        val slots = ArrayList<Slot>(2)
        if (s.startEnabled) {
            nextFor(s.startHour, s.startMinute, s, now)
                ?.let { slots += Slot(TaskType.CLOCK_IN, it) }
        }
        if (s.endEnabled) {
            nextFor(s.endHour, s.endMinute, s, now)
                ?.let { slots += Slot(TaskType.CLOCK_OUT, it) }
        }
        return slots.minByOrNull { it.at.toInstant() }
    }

    private fun nextFor(hour: Int, minute: Int, s: Settings, now: ZonedDateTime): ZonedDateTime? {
        for (dayOffset in 0..7) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            val bit = 1 shl (date.dayOfWeek.value - 1)
            if (s.weekdaysMask and bit == 0) continue

            var at = date.atTime(hour, minute)
                .atZone(now.zone)
                .plusSeconds(s.offsetSeconds.toLong())
            if (s.randomDelaySeconds > 0) {
                at = at.plusSeconds(Random.nextLong(0L, s.randomDelaySeconds + 1L))
            }
            if (at.isAfter(now.plusSeconds(5))) return at
        }
        return null
    }

    fun triggerAt(s: Settings, task: TaskType, date: LocalDate, zone: ZoneId): ZonedDateTime {
        val (hour, minute) = s.timeFor(task)
        return date.atTime(hour, minute)
            .atZone(zone)
            .plusSeconds(s.offsetSeconds.toLong())
    }
}
