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

    @Test
    fun briefAndKeyguardConstants_matchAgreedThresholds() {
        assertEquals(3 * 60 * 1000L, SleepConstants.MAX_BRIEF_INTERRUPTION_MS)
        assertEquals(17, SleepConstants.EVENT_KEYGUARD_SHOWN)
        assertEquals(18, SleepConstants.EVENT_KEYGUARD_HIDDEN)
    }

    /**
     * 7:30 锁屏关闹钟（短 RESUMED，没有 KEYGUARD_HIDDEN），随后继续睡到真正解锁。
     * 闹钟会话仍出现在原始会话里，但醒来必须是稍后的解锁，而不是闹钟 RESUMED。
     */
    @Test
    fun lockScreenAlarmDismiss_thenMoreSleep_wakeIsLaterUnlock() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(
            resumed("2026-11-11T07:30:00", "com.android.deskclock"),
            paused("2026-11-11T07:30:15", "com.android.deskclock"),
            hidden("2026-11-11T09:00:00"),
            resumed("2026-11-11T09:00:02", "com.android.settings"),
            paused("2026-11-11T09:20:00", "com.android.settings"),
            shown("2026-11-11T09:20:00"),
        )
        val result = computeNight(events)

        assertEquals(1, result.sleeps.size)
        val sleep = result.sleeps.single()
        assertEquals("2026-11-10", sleep.dayDate)
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T09:00:00"), sleep.sleepEndTs)
        assertEquals(600L, sleep.sleepDurationMin)
        assertEquals("com.tiktok", sleep.lastAppPackage)
        assertTrue(result.sessions.any { it.pkg == "com.android.deskclock" })
        assertTrue(sleep.rawSessions.any { it.pkg == "com.android.deskclock" })
        assertTrue(result.sleeps.none { it.sleepEndTs == ts("2026-11-11T07:30:00") })
    }

    /**
     * 闹钟界面还在前台时就解锁：同一段使用里，醒来是 KEYGUARD_HIDDEN，不是更早的锁屏 RESUMED。
     */
    @Test
    fun unlockWhileAlarmStillResumed_wakeIsUnlockNotAlarmStart() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(
            resumed("2026-11-11T07:30:00", "com.android.deskclock"),
            paused("2026-11-11T07:30:10", "com.android.deskclock"),
            hidden("2026-11-11T07:30:20"),
            resumed("2026-11-11T07:30:21", "com.android.settings"),
            paused("2026-11-11T07:50:00", "com.android.settings"),
            shown("2026-11-11T07:50:00"),
        )
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:30:20"), sleep.sleepEndTs)
        assertEquals("com.tiktok", sleep.lastAppPackage)
    }

    /** 整段输入没有 KEYGUARD_HIDDEN：锁屏短会话视为中断，醒来是稍后那段长时间使用的开始。 */
    @Test
    fun lockScreenAlarmDismiss_withoutAnyKeyguard_wakeIsLaterLongUse() {
        val events = session("2026-11-10T22:30", "2026-11-10T23:00", "com.tiktok") +
            session("2026-11-11T07:30:00", "2026-11-11T07:30:15", "com.android.deskclock") +
            session("2026-11-11T09:30", "2026-11-11T09:50", "com.android.settings")
        val result = computeNight(events)

        val sleep = result.sleeps.single()
        assertEquals("2026-11-10", sleep.dayDate)
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T09:30"), sleep.sleepEndTs)
        assertEquals("com.tiktok", sleep.lastAppPackage)
        assertTrue(result.sleeps.none { it.sleepEndTs == ts("2026-11-11T07:30:00") })
    }

    /** 7:30 解锁后只玩了两分钟又睡，9:30 才真正起来：一次睡眠，结束在后一次解锁。 */
    @Test
    fun briefUnlock_thenMoreSleep_wakeIsLaterUnlock() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(
            hidden("2026-11-11T07:30:00"),
            resumed("2026-11-11T07:30:01", "com.android.deskclock"),
            paused("2026-11-11T07:32:00", "com.android.deskclock"),
            shown("2026-11-11T07:32:00"),
            hidden("2026-11-11T09:30:00"),
            resumed("2026-11-11T09:30:01", "com.android.settings"),
            paused("2026-11-11T09:50:00", "com.android.settings"),
            shown("2026-11-11T09:50:00"),
        )
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T09:30:00"), sleep.sleepEndTs)
        assertEquals("com.tiktok", sleep.lastAppPackage)
    }

    /** 夜里两次不超过 3 分钟的解锁都并掉，醒来是后面那次真正解锁。 */
    @Test
    fun twoBriefUnlocks_areMerged_wakeIsFinalUnlock() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(
            hidden("2026-11-11T03:00:00"),
            resumed("2026-11-11T03:00:01", "com.android.deskclock"),
            paused("2026-11-11T03:01:00", "com.android.deskclock"),
            shown("2026-11-11T03:01:00"),
            hidden("2026-11-11T05:10:00"),
            resumed("2026-11-11T05:10:01", "com.android.settings"),
            paused("2026-11-11T05:11:00", "com.android.settings"),
            shown("2026-11-11T05:11:00"),
            hidden("2026-11-11T07:40:00"),
            resumed("2026-11-11T07:40:01", "com.android.settings"),
            paused("2026-11-11T08:00:00", "com.android.settings"),
            shown("2026-11-11T08:00:00"),
        )
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:40:00"), sleep.sleepEndTs)
    }

    /** 解锁后连续使用明显超过 3 分钟：醒来就是这次解锁，而不是几秒后的 RESUMED。 */
    @Test
    fun unlockThenUseWellOverThreeMinutes_wakeIsThatUnlock() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(
            hidden("2026-11-11T07:30:00"),
            resumed("2026-11-11T07:30:03", "com.android.settings"),
            paused("2026-11-11T07:50:00", "com.android.settings"),
            shown("2026-11-11T07:50:00"),
            hidden("2026-11-11T10:00:00"),
            resumed("2026-11-11T10:00:01", "com.android.settings"),
            paused("2026-11-11T10:20:00", "com.android.settings"),
            shown("2026-11-11T10:20:00"),
        )
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-11T07:30:00"), sleep.sleepEndTs)
        assertEquals(510L, sleep.sleepDurationMin)
        assertTrue(sleep.sleepEndTs < ts("2026-11-11T07:30:03"))
    }

    /** 解锁时长刚好 3 分钟，之后又是长静默：仍是短暂中断。 */
    @Test
    fun unlockOfExactlyThreeMinutes_isBriefInterruption() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(
            hidden("2026-11-11T07:30:00"),
            resumed("2026-11-11T07:30:00", "com.android.settings"),
            paused("2026-11-11T07:33:00", "com.android.settings"),
            shown("2026-11-11T07:33:00"),
            hidden("2026-11-11T09:30:00"),
            resumed("2026-11-11T09:30:01", "com.android.settings"),
            paused("2026-11-11T09:50:00", "com.android.settings"),
            shown("2026-11-11T09:50:00"),
        )
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-11T09:30:00"), sleep.sleepEndTs)
    }

    /** 比 3 分钟多 1 秒的使用就是醒来，时间取解锁而不是 RESUMED。 */
    @Test
    fun unlockThreeMinutesPlusOneSecond_isWake() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(
            hidden("2026-11-11T07:30:00"),
            resumed("2026-11-11T07:30:02", "com.android.settings"),
            paused("2026-11-11T07:33:03", "com.android.settings"),
            shown("2026-11-11T07:33:03"),
            hidden("2026-11-11T09:30:00"),
            resumed("2026-11-11T09:30:01", "com.android.settings"),
            paused("2026-11-11T09:50:00", "com.android.settings"),
            shown("2026-11-11T09:50:00"),
        )
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-11T07:30:00"), sleep.sleepEndTs)
        assertTrue(sleep.sleepEndTs < ts("2026-11-11T07:30:02"))
    }

    /** 没有 KEYGUARD 时，刚好 3 分钟的夜间会话并掉；多 1 秒则从这次会话开始醒来。 */
    @Test
    fun noKeyguard_threeMinuteBoundary_mergesExactAndWakesJustOver() {
        val exact = session("2026-11-10T22:30", "2026-11-10T23:00", "com.tiktok") +
            session("2026-11-11T07:30:00", "2026-11-11T07:33:00", "com.android.settings") +
            session("2026-11-11T09:30", "2026-11-11T09:50", "com.android.settings")
        val exactSleep = computeNight(exact).sleeps.single()
        assertEquals(ts("2026-11-11T09:30"), exactSleep.sleepEndTs)

        val justOver = session("2026-11-10T22:30", "2026-11-10T23:00", "com.tiktok") +
            session("2026-11-11T07:30:00", "2026-11-11T07:33:01", "com.android.settings") +
            session("2026-11-11T09:30", "2026-11-11T09:50", "com.android.settings")
        val overSleep = computeNight(justOver).sleeps.single()
        assertEquals(ts("2026-11-11T07:30:00"), overSleep.sleepEndTs)
    }

    /** 采集结束时还停在解锁状态：没有 KEYGUARD_SHOWN 也没有前台会话，这次解锁就是醒来。 */
    @Test
    fun stillUnlockedAtRangeEnd_withoutSession_isWake() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(hidden("2026-11-11T07:30:00"))
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-11T10:00"),
        )
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:30:00"), sleep.sleepEndTs)
        assertEquals(510L, sleep.sleepDurationMin)
    }

    /** 已经 PAUSED，但没有 KEYGUARD_SHOWN，到采集结束仍算解锁：醒来是 KEYGUARD_HIDDEN。 */
    @Test
    fun closedUseWithoutKeyguardShown_stillUnlockedAtRangeEnd_isWake() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(
            hidden("2026-11-11T07:30:00"),
            resumed("2026-11-11T07:30:02", "com.android.settings"),
            paused("2026-11-11T07:31:00", "com.android.settings"),
        )
        val result = SleepCalculator.compute(
            events,
            ts("2026-11-10T00:00"),
            ts("2026-11-11T10:00"),
        )
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-11T07:30:00"), sleep.sleepEndTs)
        assertTrue(sleep.sleepEndTs < ts("2026-11-11T07:30:02"))
    }

    /**
     * 其它时刻有解锁事件，但起床这次只有一段超过 3 分钟的前台、没有 KEYGUARD_HIDDEN：
     * 用这段会话的开始时间作为醒来。
     */
    @Test
    fun longSessionWithoutKeyguardInStretch_sessionStartIsWake() {
        val events = eveningUntil("2026-11-10T23:00") +
            session("2026-11-11T07:20", "2026-11-11T07:45", "com.android.settings")
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:20"), sleep.sleepEndTs)
        assertEquals("com.tiktok", sleep.lastAppPackage)
    }

    /** 只有锁屏短活动、没有后续解锁或长时间使用：不编造醒来时间。 */
    @Test
    fun lockScreenOnlyAfterSleep_doesNotInventWake() {
        val events = eveningUntil("2026-11-10T23:00") + listOf(
            resumed("2026-11-11T07:30:00", "com.android.deskclock"),
            paused("2026-11-11T07:30:20", "com.android.deskclock"),
        )
        val result = computeNight(events)
        assertTrue(result.sleeps.isEmpty())
        assertTrue(result.sessions.any { it.pkg == "com.android.deskclock" })
    }

    /** 没有解锁事件时，夜里唯一的短会话仍然是醒来（旧行为：它是下一次真实使用）。 */
    @Test
    fun noKeyguard_terminalBriefSession_isStillWake() {
        val events = session("2026-11-10T22:30", "2026-11-10T23:00", "com.tiktok") +
            session("2026-11-11T07:30:00", "2026-11-11T07:31:00", "com.android.settings")
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-11T07:30:00"), sleep.sleepEndTs)
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
    }

    /** 相隔几十秒的两小段前台并成一次中断，不在第一段醒来。 */
    @Test
    fun noKeyguard_twoCloseBriefSessions_mergeAsOneInterruption() {
        val events = session("2026-11-10T22:30", "2026-11-10T23:00", "com.tiktok") + listOf(
            resumed("2026-11-11T03:00:00", "com.a"),
            paused("2026-11-11T03:00:30", "com.a"),
            resumed("2026-11-11T03:00:50", "com.b"),
            paused("2026-11-11T03:01:20", "com.b"),
        ) + session("2026-11-11T07:30", "2026-11-11T07:50", "com.android.settings")
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-10T23:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:30"), sleep.sleepEndTs)
    }

    /** 入睡前两分钟再看一眼手机：间隙不到 90 分钟，不能把更早的使用当成入睡。 */
    @Test
    fun shortUseJustBeforeSleep_staysSleepStart() {
        val events = listOf(
            hidden("2026-11-10T21:00:00"),
            resumed("2026-11-10T21:00:01", "com.app.a"),
            paused("2026-11-10T22:40:00", "com.app.a"),
            shown("2026-11-10T22:40:00"),
            hidden("2026-11-10T22:50:00"),
            resumed("2026-11-10T22:50:01", "com.app.b"),
            paused("2026-11-10T22:52:00", "com.app.b"),
            shown("2026-11-10T22:52:00"),
            hidden("2026-11-11T07:30:00"),
            resumed("2026-11-11T07:30:01", "com.android.settings"),
            paused("2026-11-11T07:50:00", "com.android.settings"),
            shown("2026-11-11T07:50:00"),
        )
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-10T22:52:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:30:00"), sleep.sleepEndTs)
        assertEquals("com.app.b", sleep.lastAppPackage)
    }

    /** 整晚只有 KEYGUARD_HIDDEN、没有 KEYGUARD_SHOWN 时，仍用两次解锁之间的前台结束作为入睡。 */
    @Test
    fun hiddenWithoutShown_stillSplitsNightAtRealUse() {
        val events = listOf(
            hidden("2026-11-10T22:30:00"),
            resumed("2026-11-10T22:30:01", "com.tiktok"),
            paused("2026-11-10T23:00:00", "com.tiktok"),
            hidden("2026-11-11T07:30:00"),
            resumed("2026-11-11T07:30:02", "com.android.settings"),
            paused("2026-11-11T07:50:00", "com.android.settings"),
        )
        val result = computeNight(events)
        val sleep = result.sleeps.single()
        assertEquals(ts("2026-11-10T23:00:00"), sleep.sleepStartTs)
        assertEquals(ts("2026-11-11T07:30:00"), sleep.sleepEndTs)
        assertEquals("com.tiktok", sleep.lastAppPackage)
    }

    private fun hidden(dateTime: String) = UsageEventInput(
        timestampMs = ts(dateTime),
        type = SleepConstants.EVENT_KEYGUARD_HIDDEN,
        packageName = "",
    )

    private fun shown(dateTime: String) = UsageEventInput(
        timestampMs = ts(dateTime),
        type = SleepConstants.EVENT_KEYGUARD_SHOWN,
        packageName = "",
    )

    private fun eveningUntil(end: String) = listOf(
        hidden("2026-11-10T22:30:00"),
        resumed("2026-11-10T22:30:01", "com.tiktok"),
        paused(end, "com.tiktok"),
        shown(end),
    )

    private fun computeNight(events: List<UsageEventInput>) = SleepCalculator.compute(
        events,
        ts("2026-11-10T00:00"),
        ts("2026-11-11T12:00"),
    )
}
