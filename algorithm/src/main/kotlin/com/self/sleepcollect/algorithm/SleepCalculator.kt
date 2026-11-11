package com.self.sleepcollect.algorithm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * 推测睡眠计算：独立纯函数，无 Android 框架依赖，可在 JVM 上单测。
 *
 * 算法步骤（固定）：
 * 1. 事件按时间戳升序（同一毫秒时 PAUSED 先于 RESUMED，便于处理切换）；
 * 2. 按包名配对 RESUMED/PAUSED，组装前台会话；处理应用切换、忽略未匹配 PAUSED；
 *    区间结束仍未关闭的 RESUMED，仅把结束时间填为 rangeEnd 供预览，不作为入睡点；
 * 3. 按会话开始时间排序，计算相邻会话之间的静默间隙（上一次结束 → 下一次开始）；
 *    不从 rangeStart 凭空造间隙；没有下一次使用则不视为睡眠（醒来必须是真实的下次使用开始）；
 * 4. 过滤：间隙时长 ≥ [minSleepGapMs]，且与某夜间窗口（22:00～次日 10:00）有重叠；
 * 5. 若同一间隙与两个夜间窗口都重叠，归给重叠更长的那个窗口（同一间隙绝不记两天）；
 * 6. 每个夜间窗口取最长合格间隙作为当日推测睡眠。
 *
 * 入睡 = 上一次使用的真实结束时间（不裁剪到 22:00）；
 * 醒来 = 下一次使用的真实开始时间。
 */
object SleepCalculator {

    /**
     * @param events 采集到的 RESUMED/PAUSED 事件（其它类型可传入，会被忽略）
     * @param rangeStartMs 采集区间起点（当前算法不从该点凭空造间隙，仅作语义完整）
     * @param rangeEndMs 采集区间终点；未关闭会话的预览结束时间
     */
    fun compute(
        events: List<UsageEventInput>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        minSleepGapMs: Long = SleepConstants.MIN_SLEEP_GAP_MS,
        nightStartHour: Int = SleepConstants.NIGHT_START_HOUR,
        nightEndHour: Int = SleepConstants.NIGHT_END_HOUR,
        zoneId: ZoneId = SleepConstants.ZONE_ID,
    ): SleepComputation {
        val sessions = assembleSessions(events, rangeEndMs)
        val evaluated = evaluateGaps(
            sessions = sessions,
            minSleepGapMs = minSleepGapMs,
            nightStartHour = nightStartHour,
            nightEndHour = nightEndHour,
            zoneId = zoneId,
        )
        val (gaps, sleeps) = selectDailySleep(
            sessions = sessions,
            evaluated = evaluated,
            nightStartHour = nightStartHour,
            nightEndHour = nightEndHour,
            zoneId = zoneId,
        )
        return SleepComputation(
            sessions = sessions,
            gaps = gaps,
            sleeps = sleeps.sortedBy { it.dayDate },
        )
    }

    /**
     * 将 RESUMED/PAUSED 组装为前台会话。
     *
     * - 同一包连续多次 RESUMED（Activity 内部跳转）：保留第一次开始时间。
     * - 应用切换：若 A 仍处于打开状态时出现 B 的 RESUMED，则在 B 开始时刻关闭 A
     *   （部分 ROM 可能丢 PAUSED，避免会话重叠导致间隙为 0 或为负）。
     * - 未匹配的 PAUSED：忽略。
     * - 区间结束仍未 PAUSED 的会话：endTs = rangeEndMs，closed = false。
     */
    fun assembleSessions(
        events: List<UsageEventInput>,
        rangeEndMs: Long,
    ): List<ForegroundSession> {
        val sorted = events
            .filter {
                (it.type == SleepConstants.EVENT_ACTIVITY_RESUMED ||
                    it.type == SleepConstants.EVENT_ACTIVITY_PAUSED) &&
                    it.packageName.isNotBlank()
            }
            .sortedWith(
                compareBy<UsageEventInput> { it.timestampMs }
                    // 同一时间戳：先处理 PAUSED(2) 再处理 RESUMED(1)
                    .thenByDescending { it.type },
            )

        val openStarts = LinkedHashMap<String, Long>()
        val sessions = mutableListOf<ForegroundSession>()

        fun closePackage(pkg: String, endTs: Long, closed: Boolean) {
            val start = openStarts.remove(pkg) ?: return
            if (endTs >= start) {
                sessions += ForegroundSession(pkg = pkg, startTs = start, endTs = endTs, closed = closed)
            }
        }

        for (event in sorted) {
            when (event.type) {
                SleepConstants.EVENT_ACTIVITY_RESUMED -> {
                    // 应用切换：关闭其它仍打开的包，结束时间取本次 RESUMED 时刻
                    val others = openStarts.keys.filter { it != event.packageName }
                    for (pkg in others) {
                        closePackage(pkg, event.timestampMs, closed = true)
                    }
                    if (!openStarts.containsKey(event.packageName)) {
                        openStarts[event.packageName] = event.timestampMs
                    }
                }
                SleepConstants.EVENT_ACTIVITY_PAUSED -> {
                    if (openStarts.containsKey(event.packageName)) {
                        closePackage(event.packageName, event.timestampMs, closed = true)
                    }
                    // 未匹配 PAUSED：忽略
                }
            }
        }

        // 未关闭会话：预览用结束时间填 rangeEnd，标记 closed=false，避免把假结束当成入睡点
        val leftover = openStarts.keys.toList()
        for (pkg in leftover) {
            closePackage(pkg, rangeEndMs, closed = false)
        }

        return sessions.sortedWith(compareBy({ it.startTs }, { it.endTs }, { it.pkg }))
    }

    private data class EvaluatedGap(
        val startTs: Long,
        val endTs: Long,
        val durationMs: Long,
        val lastAppPackage: String,
        val nextAppPackage: String,
        val passedFilter: Boolean,
        val filterReason: String?,
        val assignedDayDate: String?,
        val overlapMs: Long,
    )

    private fun evaluateGaps(
        sessions: List<ForegroundSession>,
        minSleepGapMs: Long,
        nightStartHour: Int,
        nightEndHour: Int,
        zoneId: ZoneId,
    ): List<EvaluatedGap> {
        val result = mutableListOf<EvaluatedGap>()
        if (sessions.size < 2) return result

        for (i in 0 until sessions.lastIndex) {
            val prev = sessions[i]
            val next = sessions[i + 1]

            // 未关闭会话的结束时间是 rangeEnd 填充值，不能作为“上一次使用结束”去开一条间隙
            if (!prev.closed) {
                result += EvaluatedGap(
                    startTs = prev.endTs,
                    endTs = next.startTs,
                    durationMs = (next.startTs - prev.endTs).coerceAtLeast(0L),
                    lastAppPackage = prev.pkg,
                    nextAppPackage = next.pkg,
                    passedFilter = false,
                    filterReason = "上一段会话在采集区间结束时仍未关闭，假结束时间不作为入睡点",
                    assignedDayDate = null,
                    overlapMs = 0L,
                )
                continue
            }

            val gapStart = prev.endTs
            val gapEnd = next.startTs
            if (gapEnd <= gapStart) {
                result += EvaluatedGap(
                    startTs = gapStart,
                    endTs = gapEnd,
                    durationMs = 0L,
                    lastAppPackage = prev.pkg,
                    nextAppPackage = next.pkg,
                    passedFilter = false,
                    filterReason = "相邻会话无正时长静默间隙（衔接或重叠）",
                    assignedDayDate = null,
                    overlapMs = 0L,
                )
                continue
            }

            val durationMs = gapEnd - gapStart
            val reasons = mutableListOf<String>()
            if (durationMs < minSleepGapMs) {
                reasons += "间隙不足${minSleepGapMs / 60_000L}分钟（实际${durationMs / 60_000L}分钟）"
            }

            val overlaps = nightWindowsOverlapping(
                gapStart = gapStart,
                gapEnd = gapEnd,
                nightStartHour = nightStartHour,
                nightEndHour = nightEndHour,
                zoneId = zoneId,
            )
            if (overlaps.isEmpty()) {
                reasons += "不与夜间窗口重叠"
            }

            if (reasons.isNotEmpty()) {
                result += EvaluatedGap(
                    startTs = gapStart,
                    endTs = gapEnd,
                    durationMs = durationMs,
                    lastAppPackage = prev.pkg,
                    nextAppPackage = next.pkg,
                    passedFilter = false,
                    filterReason = reasons.joinToString("；"),
                    assignedDayDate = null,
                    overlapMs = overlaps.maxOfOrNull { it.overlapMs } ?: 0L,
                )
                continue
            }

            // 与多个夜间窗口重叠时，归给重叠更长的窗口；并列时取更早的日期
            val assigned = overlaps
                .groupBy { it.overlapMs }
                .maxBy { it.key }
                .value
                .minBy { it.dayDate }

            result += EvaluatedGap(
                startTs = gapStart,
                endTs = gapEnd,
                durationMs = durationMs,
                lastAppPackage = prev.pkg,
                nextAppPackage = next.pkg,
                passedFilter = true,
                filterReason = null,
                assignedDayDate = assigned.dayDate,
                overlapMs = assigned.overlapMs,
            )
        }
        return result
    }

    private fun selectDailySleep(
        sessions: List<ForegroundSession>,
        evaluated: List<EvaluatedGap>,
        nightStartHour: Int,
        nightEndHour: Int,
        zoneId: ZoneId,
    ): Pair<List<SilentGap>, List<SleepResult>> {
        val winners = evaluated
            .filter { it.passedFilter && it.assignedDayDate != null }
            .groupBy { it.assignedDayDate!! }
            .mapValues { (_, list) ->
                list.maxWith(compareBy<EvaluatedGap> { it.durationMs }.thenBy { it.startTs })
            }

        val gaps = evaluated.map { g ->
            val selected = g.passedFilter &&
                g.assignedDayDate != null &&
                winners[g.assignedDayDate] === g
            val discard = when {
                selected -> null
                g.passedFilter && g.assignedDayDate != null ->
                    "同日已有更长合格间隙，本间隙不作为当日推测睡眠"
                else -> null
            }
            SilentGap(
                startTs = g.startTs,
                endTs = g.endTs,
                durationMs = g.durationMs,
                lastAppPackage = g.lastAppPackage,
                nextAppPackage = g.nextAppPackage,
                passedFilter = g.passedFilter,
                filterReason = g.filterReason,
                assignedDayDate = g.assignedDayDate,
                overlapMs = g.overlapMs,
                selectedAsSleep = selected,
                discardReason = discard,
            )
        }

        val sleeps = winners.values.map { g ->
            val dayDate = g.assignedDayDate!!
            val raw = rawSessionsForNight(
                sessions = sessions,
                dayDate = dayDate,
                sleepStartTs = g.startTs,
                lastAppPackage = g.lastAppPackage,
                nightStartHour = nightStartHour,
                nightEndHour = nightEndHour,
                zoneId = zoneId,
            )
            SleepResult(
                dayDate = dayDate,
                sleepStartTs = g.startTs,
                sleepEndTs = g.endTs,
                sleepDurationMin = g.durationMs / 60_000L,
                lastAppPackage = g.lastAppPackage,
                rawSessions = raw,
            )
        }

        return gaps to sleeps
    }

    /**
     * 取与该夜间窗口相交的会话，并至少包含入睡前最后一次会话。
     */
    private fun rawSessionsForNight(
        sessions: List<ForegroundSession>,
        dayDate: String,
        sleepStartTs: Long,
        lastAppPackage: String,
        nightStartHour: Int,
        nightEndHour: Int,
        zoneId: ZoneId,
    ): List<RawSession> {
        val day = LocalDate.parse(dayDate)
        val window = nightWindowOf(day, nightStartHour, nightEndHour, zoneId)
        val picked = LinkedHashMap<String, RawSession>()

        fun keyOf(pkg: String, start: Long, end: Long) = "$pkg|$start|$end"

        fun add(session: ForegroundSession) {
            val k = keyOf(session.pkg, session.startTs, session.endTs)
            picked.putIfAbsent(
                k,
                RawSession(pkg = session.pkg, startTs = session.startTs, endTs = session.endTs),
            )
        }

        for (session in sessions) {
            val touches = session.startTs < window.endMs && session.endTs > window.startMs
            if (touches) add(session)
        }

        val lastBefore = sessions.find {
            it.closed && it.endTs == sleepStartTs && it.pkg == lastAppPackage
        }
        if (lastBefore != null) add(lastBefore)

        return picked.values.sortedWith(compareBy({ it.startTs }, { it.endTs }, { it.pkg }))
    }

    private data class NightWindow(val dayDate: String, val startMs: Long, val endMs: Long)

    private data class NightOverlap(val dayDate: String, val overlapMs: Long)

    private fun nightWindowOf(
        day: LocalDate,
        nightStartHour: Int,
        nightEndHour: Int,
        zoneId: ZoneId,
    ): NightWindow {
        val startMs = day.atTime(nightStartHour, 0).atZone(zoneId).toInstant().toEpochMilli()
        val endMs = day.plusDays(1).atTime(nightEndHour, 0).atZone(zoneId).toInstant().toEpochMilli()
        return NightWindow(dayDate = day.toString(), startMs = startMs, endMs = endMs)
    }

    /**
     * 枚举与 [gapStart, gapEnd) 有正时长重叠的全部夜间窗口。
     */
    private fun nightWindowsOverlapping(
        gapStart: Long,
        gapEnd: Long,
        nightStartHour: Int = SleepConstants.NIGHT_START_HOUR,
        nightEndHour: Int = SleepConstants.NIGHT_END_HOUR,
        zoneId: ZoneId = SleepConstants.ZONE_ID,
    ): List<NightOverlap> {
        if (gapEnd <= gapStart) return emptyList()
        val startDate = Instant.ofEpochMilli(gapStart).atZone(zoneId).toLocalDate()
        val endDate = Instant.ofEpochMilli(gapEnd).atZone(zoneId).toLocalDate()
        var cursor = startDate.minusDays(1)
        val last = endDate
        val overlaps = mutableListOf<NightOverlap>()
        while (!cursor.isAfter(last)) {
            val window = nightWindowOf(cursor, nightStartHour, nightEndHour, zoneId)
            val overlapStart = max(gapStart, window.startMs)
            val overlapEnd = min(gapEnd, window.endMs)
            if (overlapEnd > overlapStart) {
                overlaps += NightOverlap(dayDate = window.dayDate, overlapMs = overlapEnd - overlapStart)
            }
            cursor = cursor.plusDays(1)
        }
        return overlaps
    }
}
