package com.buwrt.dingclock.schedule

import com.buwrt.dingclock.model.Settings
import com.buwrt.dingclock.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NextRunPlannerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2024, month, day, hour, minute, 0, 0, zone)

    private fun settings(
        startHour: Int = 9,
        startMinute: Int = 0,
        endHour: Int = 18,
        endMinute: Int = 0
    ) = Settings(
        enabled = true,
        startEnabled = true,
        startHour = startHour,
        startMinute = startMinute,
        endEnabled = true,
        endHour = endHour,
        endMinute = endMinute,
        weekdaysMask = 0b0011111,
        randomDelaySeconds = 0
    )

    @Test
    fun `disabled returns null`() {
        assertNull(NextRunPlanner.nextSlot(settings().copy(enabled = false), at(6, 3, 8, 0)))
    }

    @Test
    fun `morning before start picks clock in today`() {
        val slot = NextRunPlanner.nextSlot(settings(), at(6, 3, 8, 0))
        assertEquals(TaskType.CLOCK_IN, slot?.task)
        assertEquals(at(6, 3, 9, 0), slot?.at)
    }

    @Test
    fun `after start picks clock out today`() {
        val slot = NextRunPlanner.nextSlot(settings(), at(6, 3, 10, 0))
        assertEquals(TaskType.CLOCK_OUT, slot?.task)
        assertEquals(at(6, 3, 18, 0), slot?.at)
    }

    @Test
    fun `after both picks clock in next weekday`() {
        val slot = NextRunPlanner.nextSlot(settings(), at(6, 3, 19, 0))
        assertEquals(TaskType.CLOCK_IN, slot?.task)
        assertEquals(at(6, 4, 9, 0), slot?.at)
    }

    @Test
    fun `friday after work jumps to monday`() {
        val slot = NextRunPlanner.nextSlot(settings(), at(6, 7, 19, 0))
        assertEquals(at(6, 10, 9, 0), slot?.at)
    }

    @Test
    fun `weekend is skipped when only workdays selected`() {
        val slot = NextRunPlanner.nextSlot(settings(), at(6, 8, 8, 0))
        assertEquals(at(6, 10, 9, 0), slot?.at)
    }

    @Test
    fun `offset moves trigger earlier`() {
        val slot = NextRunPlanner.nextSlot(
            settings().copy(offsetSeconds = -300),
            at(6, 3, 8, 0)
        )
        assertEquals(at(6, 3, 8, 55), slot?.at)
    }

    @Test
    fun `only clock out enabled`() {
        val slot = NextRunPlanner.nextSlot(
            settings().copy(startEnabled = false),
            at(6, 3, 8, 0)
        )
        assertEquals(TaskType.CLOCK_OUT, slot?.task)
        assertEquals(at(6, 3, 18, 0), slot?.at)
    }

    @Test
    fun `only clock in enabled`() {
        val slot = NextRunPlanner.nextSlot(
            settings().copy(endEnabled = false),
            at(6, 3, 10, 0)
        )
        assertEquals(TaskType.CLOCK_IN, slot?.task)
        assertEquals(at(6, 4, 9, 0), slot?.at)
    }

    @Test
    fun `slot within five second grace is skipped`() {
        val slot = NextRunPlanner.nextSlot(settings(), at(6, 3, 9, 0).plusSeconds(4))
        assertEquals(TaskType.CLOCK_OUT, slot?.task)
    }

    @Test
    fun `triggerAt applies offset`() {
        val trigger = NextRunPlanner.triggerAt(
            settings().copy(offsetSeconds = 120),
            TaskType.CLOCK_IN,
            at(6, 3, 0, 0).toLocalDate(),
            zone
        )
        assertEquals(at(6, 3, 9, 2), trigger)
    }

    @Test
    fun `keywords parse separators`() {
        val s = Settings(startKeywords = "上班打卡，打卡/签到|考勤")
        assertEquals(listOf("上班打卡", "打卡", "签到", "考勤"), s.keywords(TaskType.CLOCK_IN))
    }

    @Test
    fun `random delay stays within bounds`() {
        val base = settings().copy(randomDelaySeconds = 60)
        repeat(20) {
            val slot = NextRunPlanner.nextSlot(base, at(6, 3, 8, 0))
            val earliest = at(6, 3, 9, 0)
            val latest = at(6, 3, 9, 1)
            assertTrue(slot!!.at.toInstant() >= earliest.toInstant())
            assertTrue(slot.at.toInstant() <= latest.toInstant())
        }
    }
}
