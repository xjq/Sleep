package com.self.sleepcollect.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SleepCalculatorTest {

    private val zone: ZoneId = SleepConstants.ZONE_ID

    private fun ts(dateTime: String): Long {
        // dateTime: "2026-11-10T23:00"
        return LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()
    }

    private fun resumed(dateTime: String, pkg: String) = UsageEventInput(
        timestampMs = ts(dateTime),
        type = SleepConstants.EVENT_ACTIVITY_RESUMED,
        packageName = pkg,
    )

    private fun paused(dateTime: String, pkg: String) = UsageEventInput(
        timestampMs = ts(dateTime),
        type = SleepConstants.EVENT_ACTIVITY_PAUSED,
        packageName = pkg,
    )

    private fun session(start: String, end: String, pkg: String) = listOf(
        resumed(start, pkg),
        paused(end, pkg),
    )

    /**
     * 正常夜晚：23:00 结束使用，次日 07:10 再次使用 → 一条睡眠。
     * day_date 为夜间起始日，时长 490 分钟，last package 为入睡前应用。
     */
    @Test
    fun normalNight_producesOneSleepWithCorrectFields() {
        val events = session("2026-11-10T22:30", "2026-11-10T23:00", "com.tiktok") +
            session("2026-11-11T07:10", "2026-11-11T07:40", "com.android.settings")
        val rangeStart = ts("2026-11-10T00:00")
        val rangeEnd = ts("2026-11-11T12:00")

        val result = SleepCalculator.compute(events, rangeStart, rangeEnd)

        assertEquals(1, result.sleeps.size)
        val sleep = result.sleeps.single()
        assertEquals("2026-11-10", sleep.dayDate)
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:10"), sleep.sleepEndTs)
        assertEquals(490L, sleep.sleepDurationMin)
        assertEquals("com.tiktok", sleep.lastAppPackage)
        assertTrue(sleep.rawSessions.any { it.pkg == "com.tiktok" && it.endTs == sleep.sleepStartTs })
    }

    /** 短于 90 分钟的间隙应被忽略。 */
    @Test
    fun shortGapUnder90Minutes_isIgnored() {
        val events = session("2026-11-10T23:00", "2026-11-10T23:10", "com.a") +
            session("2026-11-11T00:20", "2026-11-11T00:30", "com.b")
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-11T12:00"),
        )

        assertTrue(result.sleeps.isEmpty())
        val gap = result.gaps.single { it.startTs == ts("2026-11-10T23:10") }
        assertFalse(gap.passedFilter)
        assertFalse(gap.selectedAsSleep)
        assertTrue(gap.filterReason!!.contains("不足90分钟"))
    }

    /** 白天的长间隙（例如 12:00–16:00）即使超过 90 分钟也不算睡眠。 */
    @Test
    fun daytimeLongGap_isIgnored() {
        val events = session("2026-11-10T10:00", "2026-11-10T12:00", "com.day.a") +
            session("2026-11-10T16:00", "2026-11-10T17:00", "com.day.b")
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-10T23:59"),
        )

        assertTrue(result.sleeps.isEmpty())
        val gap = result.gaps.single { it.startTs == ts("2026-11-10T12:00") }
        assertEquals(4 * 60L, gap.durationMin)
        assertFalse(gap.passedFilter)
        assertTrue(gap.filterReason!!.contains("不与夜间窗口重叠"))
    }

    /** 半夜打开手机刷一会儿，会把睡眠窗口拆成两段；当日取最长的一段。 */
    @Test
    fun midnightPhoneSession_splitsSleepAndKeepsLongestGap() {
        val events = session("2026-11-10T22:00", "2026-11-10T23:00", "com.app.a") +
            session("2026-11-11T02:00", "2026-11-11T02:20", "com.app.b") +
            session("2026-11-11T07:10", "2026-11-11T07:30", "com.app.c")
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-11T12:00"),
        )

        val passed = result.gaps.filter { it.passedFilter }
        assertEquals(2, passed.size)
        // 23:00–02:00 = 180 分钟；02:20–07:10 = 290 分钟，取后者
        assertEquals(1, result.sleeps.size)
        val sleep = result.sleeps.single()
        assertEquals("2026-11-10", sleep.dayDate)
        assertEquals(ts("2026-11-11T02:20"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:10"), sleep.sleepEndTs)
        assertEquals(290L, sleep.sleepDurationMin)
        assertEquals("com.app.b", sleep.lastAppPackage)

        val shorter = result.gaps.single { it.startTs == ts("2026-11-10T23:00") }
        assertTrue(shorter.passedFilter)
        assertFalse(shorter.selectedAsSleep)
        assertTrue(shorter.discardReason!!.contains("更长合格间隙"))
    }

    /** 醒来已过 0 点，day_date 仍归属前一天（夜间起始日 D）。 */
    @Test
    fun wakeAfterMidnight_stillBelongsToPreviousDateD() {
        val events = session("2026-11-10T22:40", "2026-11-10T23:30", "com.last") +
            session("2026-11-11T01:00", "2026-11-11T01:20", "com.wake")
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-11T12:00"),
        )

        val sleep = result.sleeps.single()
        assertEquals("2026-11-10", sleep.dayDate)
        assertEquals(ts("2026-11-10T23:30"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T01:00"), sleep.sleepEndTs)
        assertEquals(90L, sleep.sleepDurationMin)
        assertEquals("com.last", sleep.lastAppPackage)
        // 醒来日是 11 日，但日期标签必须是夜间开始的 10 日
        val wakeDate = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(sleep.sleepEndTs),
            zone,
        ).toLocalDate()
        assertEquals(LocalDate.parse("2026-11-11"), wakeDate)
        assertEquals("2026-11-10", sleep.dayDate)
    }

    /**
     * 间隙在 22:00 之前开始，但与夜间窗口重叠，仍然合格；
     * sleep_start 是真实的上一次使用结束时间，不得裁剪到 22:00。
     */
    @Test
    fun gapStartingBeforeNightWindow_qualifiesAndKeepsRealSleepStart() {
        val events = session("2026-11-10T19:00", "2026-11-10T20:00", "com.tiktok") +
            session("2026-11-11T07:00", "2026-11-11T07:30", "com.alarm")
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-11T12:00"),
        )

        val sleep = result.sleeps.single()
        assertEquals("2026-11-10", sleep.dayDate)
        assertEquals(ts("2026-11-10T20:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:00"), sleep.sleepEndTs)
        assertEquals(11 * 60L, sleep.sleepDurationMin) // 20:00 → 07:00 = 660
        assertEquals("com.tiktok", sleep.lastAppPackage)
        // 明确断言没有被裁剪到 22:00
        assertTrue(sleep.sleepStartTs < ts("2026-11-10T22:00"))
    }

    /** 未匹配的 PAUSED 应被忽略，不影响会话与睡眠计算。 */
    @Test
    fun unmatchedPaused_isIgnored() {
        val events = listOf(
            paused("2026-11-10T21:00", "com.ghost"),
        ) + session("2026-11-10T22:30", "2026-11-10T23:00", "com.tiktok") +
            session("2026-11-11T07:10", "2026-11-11T07:40", "com.android.settings")

        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-11T12:00"),
        )

        assertEquals(2, result.sessions.size)
        assertFalse(result.sessions.any { it.pkg == "com.ghost" })
        assertEquals(1, result.sleeps.size)
        assertEquals("com.tiktok", result.sleeps.single().lastAppPackage)
    }

    /**
     * 区间结束时仍未关闭的 RESUMED：其假结束时间不得成为入睡点；
     * 但其真实开始时间可以作为“醒来”（下一次使用开始）。
     */
    @Test
    fun unclosedResumed_fakeEndIsNotSleepStart_butStartCanBeWake() {
        val events = session("2026-11-10T21:00", "2026-11-10T22:00", "com.last") +
            listOf(resumed("2026-11-11T07:00", "com.morning"))
        val rangeEnd = ts("2026-11-11T10:00")
        val result = SleepCalculator.compute(events, ts("2026-11-10T00:00"), rangeEnd)

        // 未关闭会话出现在预览里，结束时间为 rangeEnd
        val open = result.sessions.single { it.pkg == "com.morning" }
        assertFalse(open.closed)
        assertEquals(rangeEnd, open.endTs)

        // 22:00–07:00 是有效睡眠；不会再出现以 rangeEnd 为入睡点的睡眠
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-10T22:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:00"), sleep.sleepEndTs)
        assertTrue(result.sleeps.none { it.sleepStartTs == rangeEnd })
    }

    /** 一条长间隙同时覆盖两个夜间窗口时，只归给重叠更长的那天，不记两天。 */
    @Test
    fun longGapOverlappingTwoNightWindows_assignedToGreaterOverlapOnly() {
        // 11-10 23:00 结束，11-12 00:30 才再次使用
        val events = session("2026-11-10T22:00", "2026-11-10T23:00", "com.a") +
            session("2026-11-12T00:30", "2026-11-12T01:00", "com.b")
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-12T12:00"),
        )

        assertEquals(1, result.sleeps.size)
        val sleep = result.sleeps.single()
        // 与 11-10 窗口重叠：23:00–11-11 10:00 = 11h
        // 与 11-11 窗口重叠：11-11 22:00–11-12 00:30 = 2.5h
        assertEquals("2026-11-10", sleep.dayDate)
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-12T00:30"), sleep.sleepEndTs)
        assertTrue(result.sleeps.none { it.dayDate == "2026-11-11" })
    }

    /** 应用切换时若缺失 PAUSED，应在下一个 RESUMED 时刻关闭上一个会话，避免重叠。 */
    @Test
    fun appSwitchWithoutPaused_closesPreviousSession() {
        val events = listOf(
            resumed("2026-11-10T22:00", "com.a"),
            resumed("2026-11-10T22:30", "com.b"),
            paused("2026-11-10T23:00", "com.b"),
            resumed("2026-11-11T07:10", "com.c"),
            paused("2026-11-11T07:40", "com.c"),
        )
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-11T12:00"),
        )

        val sessionA = result.sessions.single { it.pkg == "com.a" }
        assertTrue(sessionA.closed)
        assertEquals(ts("2026-11-10T22:00"), sessionA.startTs)
        assertEquals(ts("2026-11-10T22:30"), sessionA.endTs)

        val sleep = result.sleeps.single()
        assertEquals("com.b", sleep.lastAppPackage)
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
    }

    /** 没有下一次使用时，开口间隙不能当成睡眠（醒来必须是真实的下次使用开始）。 */
    @Test
    fun openEndedGapWithoutNextSession_isNotSleep() {
        val events = session("2026-11-10T22:00", "2026-11-10T23:00", "com.last")
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-11T12:00"),
        )
        assertTrue(result.sessions.size == 1)
        assertTrue(result.sleeps.isEmpty())
        assertTrue(result.gaps.isEmpty())
    }

    /** 不从 rangeStart 凭空构造“采集开始前的间隙”。 */
    @Test
    fun doesNotInventGapFromRangeStart() {
        val events = session("2026-11-11T07:00", "2026-11-11T07:30", "com.first")
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T22:00"),
            ts("2026-11-11T12:00"),
        )
        assertTrue(result.gaps.isEmpty())
        assertTrue(result.sleeps.isEmpty())
    }
}
