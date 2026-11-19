package com.self.sleepcollect.algorithm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * 推测睡眠计算：独立纯函数，无 Android 框架依赖，可在 JVM 上单测。
 *
 * 入睡仍是一段够长、且与夜间窗口重叠的未使用间隙的起点（真实的上一次前台结束，不裁到 22:00）。
 * 醒来优先取这段睡眠开始之后的第一次真正解锁 [SleepConstants.EVENT_KEYGUARD_HIDDEN]：
 * 锁屏上的短前台（关闹钟、看时间）没有解锁，不结束睡眠；
 * 解锁后使用不超过 [SleepConstants.MAX_BRIEF_INTERRUPTION_MS]，并且之后又是长静默，则视为短暂中断，前后间隙合并。
 * 整段输入完全没有 KEYGUARD_HIDDEN 时，退回「下一次前台开始」，但夜间里被长静默夹住的短会话同样视为中断。
 * 不编造时间：sleep_start / sleep_end 只落在真实的 PAUSED、RESUMED 或 KEYGUARD_HIDDEN 上。
 */
object SleepCalculator {

    /**
     * @param events RESUMED/PAUSED，以及可选的 KEYGUARD_SHOWN/KEYGUARD_HIDDEN
     * @param rangeStartMs 采集区间起点（不从该点凭空造间隙）
     * @param rangeEndMs 采集区间终点；未关闭会话的预览结束时间
     * @param maxBriefInterruptionMs 解锁或前台使用不超过此时长，且之后长静默仍在继续，则不视为醒来
     */
    fun compute(
        events: List<UsageEventInput>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        minSleepGapMs: Long = SleepConstants.MIN_SLEEP_GAP_MS,
        nightStartHour: Int = SleepConstants.NIGHT_START_HOUR,
        nightEndHour: Int = SleepConstants.NIGHT_END_HOUR,
        zoneId: ZoneId = SleepConstants.ZONE_ID,
        maxBriefInterruptionMs: Long = SleepConstants.MAX_BRIEF_INTERRUPTION_MS,
    ): SleepComputation {
        val sessions = assembleSessions(events, rangeEndMs)
        val anchors = gapAnchors(
            events = events,
            sessions = sessions,
            rangeEndMs = rangeEndMs,
            minSleepGapMs = minSleepGapMs,
            maxBriefMs = maxBriefInterruptionMs,
            nightStartHour = nightStartHour,
            nightEndHour = nightEndHour,
            zoneId = zoneId,
        )
        val evaluated = evaluateGaps(
            sessions = anchors,
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

    /**
     * 给间隙计算用的边界，不是对外的前台会话列表。
     *
     * [startTs] 是这段使用把睡眠打断的时刻（优先 KEYGUARD_HIDDEN，否则会话开始）；
     * [endTs] 是这段使用真正结束的时刻，可作为下一次入睡点。
     */
    private data class GapAnchor(
        val startTs: Long,
        val endTs: Long,
        val pkg: String,
        val closed: Boolean,
        val briefCandidate: Boolean,
    )

    private data class UseEpisode(
        val startTs: Long,
        val endTs: Long,
        val pkg: String,
        val closed: Boolean,
    )

    /**
     * 把前台会话和锁屏解锁收成「会打断长静默」的边界。
     * 间隔不超过 [maxBriefMs] 的相邻会话并成一段使用，避免把一次拿起手机拆开。
     */
    private fun gapAnchors(
        events: List<UsageEventInput>,
        sessions: List<ForegroundSession>,
        rangeEndMs: Long,
        minSleepGapMs: Long,
        maxBriefMs: Long,
        nightStartHour: Int,
        nightEndHour: Int,
        zoneId: ZoneId,
    ): List<ForegroundSession> {
        val hiddens = events
            .filter { it.type == SleepConstants.EVENT_KEYGUARD_HIDDEN }
            .map { it.timestampMs }
            .distinct()
            .sorted()
        val showns = events
            .filter { it.type == SleepConstants.EVENT_KEYGUARD_SHOWN }
            .map { it.timestampMs }
            .distinct()
            .sorted()
        val hasHidden = hiddens.isNotEmpty()
        val episodes = groupEpisodes(sessions, maxBriefMs)
        val consumedHiddens = HashSet<Long>()
        val anchors = mutableListOf<GapAnchor>()

        for ((index, episode) in episodes.withIndex()) {
            val prevEnd = episodes.getOrNull(index - 1)?.endTs ?: Long.MIN_VALUE
            val associated = if (hasHidden) {
                hiddens.filter { hidden ->
                    hidden > prevEnd &&
                        hidden <= episode.endTs &&
                        hidden >= episode.startTs - maxBriefMs
                }
            } else {
                emptyList()
            }
            consumedHiddens.addAll(associated)

            val span = episode.endTs - episode.startTs
            val noUnlockInStretch = hasHidden && associated.isEmpty()
            // 有解锁数据时，这段里完全没有 KEYGUARD_HIDDEN 的短前台是锁屏活动（关闹钟、看时间）。
            if (noUnlockInStretch && episode.closed && span <= maxBriefMs) {
                continue
            }

            val wakeTs = wakeTimestamp(episode, associated)
            val nextEpisodeStart = episodes.getOrNull(index + 1)?.startTs
            val unlockDur = if (associated.isNotEmpty()) {
                shownDelayAfter(wakeTs, showns, nextEpisodeStart)
            } else {
                null
            }
            val brief = episode.closed &&
                !noUnlockInStretch &&
                span <= maxBriefMs &&
                (unlockDur == null || unlockDur <= maxBriefMs)
            if (wakeTs <= episode.endTs) {
                anchors += GapAnchor(
                    startTs = wakeTs,
                    endTs = episode.endTs,
                    pkg = episode.pkg,
                    closed = episode.closed,
                    briefCandidate = brief,
                )
            }
        }

        if (hasHidden) {
            // 下一边界只用已经保留的前台段，锁屏短会话不能把「后面还有长静默」挡住。
            val keptStarts = anchors.map { it.startTs }.sorted()
            anchors += orphanUnlockAnchors(
                hiddens = hiddens,
                showns = showns,
                consumed = consumedHiddens,
                keptStarts = keptStarts,
                rangeEndMs = rangeEndMs,
                minSleepGapMs = minSleepGapMs,
                maxBriefMs = maxBriefMs,
            )
        }

        val kept = dropBriefInterruptions(
            anchors = mergeOverlapping(anchors, maxBriefMs),
            minSleepGapMs = minSleepGapMs,
            nightStartHour = nightStartHour,
            nightEndHour = nightEndHour,
            zoneId = zoneId,
        )
        return kept
            .map { ForegroundSession(pkg = it.pkg, startTs = it.startTs, endTs = it.endTs, closed = it.closed) }
            .sortedWith(compareBy({ it.startTs }, { it.endTs }, { it.pkg }))
    }

    /** 相邻会话间隔不超过短暂中断上限时，视为同一次拿起手机。 */
    private fun groupEpisodes(sessions: List<ForegroundSession>, maxBriefMs: Long): List<UseEpisode> {
        if (sessions.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<ForegroundSession>>()
        var current = mutableListOf(sessions.first())
        for (session in sessions.drop(1)) {
            val prev = current.last()
            val gap = session.startTs - prev.endTs
            if (prev.closed && gap <= maxBriefMs) {
                current += session
            } else {
                groups += current
                current = mutableListOf(session)
            }
        }
        groups += current
        return groups.map { group ->
            UseEpisode(
                startTs = group.first().startTs,
                endTs = group.last().endTs,
                pkg = group.last().pkg,
                closed = group.all { it.closed },
            )
        }
    }

    /**
     * 这段使用对应的醒来时刻。
     * 应用打开前 3 分钟内的 KEYGUARD_HIDDEN 优先于会话开始；
     * 否则取这段使用期间的第一次解锁（先在锁屏上响铃，再解锁）。
     * 没有解锁时退回会话开始。
     */
    private fun wakeTimestamp(episode: UseEpisode, associatedHiddens: List<Long>): Long {
        if (associatedHiddens.isEmpty()) return episode.startTs
        val beforeStart = associatedHiddens.filter { it <= episode.startTs }
        return if (beforeStart.isNotEmpty()) beforeStart.max() else associatedHiddens.min()
    }

    /** 这次解锁之后、下一段使用开始之前的第一次 KEYGUARD_SHOWN 距解锁的时长；没有则未知。 */
    private fun shownDelayAfter(hiddenTs: Long, showns: List<Long>, nextStartTs: Long?): Long? {
        val limit = nextStartTs ?: Long.MAX_VALUE
        val shown = showns.firstOrNull { it >= hiddenTs && it < limit } ?: return null
        return (shown - hiddenTs).coerceAtLeast(0L)
    }

    /**
     * 没有落进任何前台段的 KEYGUARD_HIDDEN。
     * 采集结束时仍未锁屏：直接作为醒来。
     * 之后还有长静默：没有使用证据，先当成可被合并的短暂中断。
     */
    private fun orphanUnlockAnchors(
        hiddens: List<Long>,
        showns: List<Long>,
        consumed: Set<Long>,
        keptStarts: List<Long>,
        rangeEndMs: Long,
        minSleepGapMs: Long,
        maxBriefMs: Long,
    ): List<GapAnchor> {
        val anchors = mutableListOf<GapAnchor>()
        for (hidden in hiddens) {
            if (hidden in consumed || hidden > rangeEndMs) continue
            val nextStart = keptStarts.firstOrNull { it > hidden }
            val shownDelay = shownDelayAfter(hidden, showns, nextStart)
            when {
                shownDelay != null -> anchors += GapAnchor(
                    startTs = hidden,
                    endTs = hidden + shownDelay,
                    pkg = "",
                    closed = true,
                    briefCandidate = shownDelay <= maxBriefMs,
                )
                nextStart == null -> anchors += GapAnchor(
                    startTs = hidden,
                    endTs = rangeEndMs.coerceAtLeast(hidden),
                    pkg = "",
                    closed = false,
                    briefCandidate = false,
                )
                nextStart - hidden >= minSleepGapMs -> anchors += GapAnchor(
                    startTs = hidden,
                    endTs = hidden,
                    pkg = "",
                    closed = true,
                    briefCandidate = true,
                )
                else -> anchors += GapAnchor(
                    startTs = hidden,
                    endTs = hidden,
                    pkg = "",
                    closed = true,
                    briefCandidate = false,
                )
            }
        }
        return anchors
    }

    /** 时间上重叠的边界合成一段，避免同一次使用被算成两段睡眠。 */
    private fun mergeOverlapping(anchors: List<GapAnchor>, maxBriefMs: Long): List<GapAnchor> {
        val sorted = anchors.sortedWith(compareBy({ it.startTs }, { it.endTs }))
        val merged = mutableListOf<GapAnchor>()
        for (anchor in sorted) {
            if (anchor.endTs < anchor.startTs) continue
            val last = merged.lastOrNull()
            if (last != null && anchor.startTs < last.endTs) {
                val endTs = max(last.endTs, anchor.endTs)
                val span = endTs - last.startTs
                val pkg = when {
                    anchor.endTs >= last.endTs && anchor.pkg.isNotBlank() -> anchor.pkg
                    last.pkg.isNotBlank() -> last.pkg
                    else -> anchor.pkg
                }
                merged[merged.lastIndex] = GapAnchor(
                    startTs = last.startTs,
                    endTs = endTs,
                    pkg = pkg,
                    closed = last.closed && anchor.closed,
                    briefCandidate = last.briefCandidate &&
                        anchor.briefCandidate &&
                        last.closed &&
                        anchor.closed &&
                        span <= maxBriefMs,
                )
            } else {
                merged += anchor
            }
        }
        return merged
    }

    /**
     * 夹在两段长静默中间、且两段都与夜间窗口重叠的短使用，不结束睡眠。
     * 长静默必须已经落到真实的下一次边界上，不能把采集区间终点当成醒来。
     */
    private fun dropBriefInterruptions(
        anchors: List<GapAnchor>,
        minSleepGapMs: Long,
        nightStartHour: Int,
        nightEndHour: Int,
        zoneId: ZoneId,
    ): List<GapAnchor> {
        if (anchors.size < 3) return anchors
        val drop = BooleanArray(anchors.size)
        for (i in anchors.indices) {
            val anchor = anchors[i]
            if (!anchor.briefCandidate || !anchor.closed) continue
            val prev = anchors.getOrNull(i - 1) ?: continue
            val next = anchors.getOrNull(i + 1) ?: continue
            if (!prev.closed) continue
            val gapBefore = anchor.startTs - prev.endTs
            val gapAfter = next.startTs - anchor.endTs
            if (gapBefore >= minSleepGapMs &&
                gapAfter >= minSleepGapMs &&
                overlapsNight(prev.endTs, anchor.startTs, nightStartHour, nightEndHour, zoneId) &&
                overlapsNight(anchor.endTs, next.startTs, nightStartHour, nightEndHour, zoneId)
            ) {
                drop[i] = true
            }
        }
        return anchors.filterIndexed { index, _ -> !drop[index] }
    }

    private fun overlapsNight(
        startMs: Long,
        endMs: Long,
        nightStartHour: Int,
        nightEndHour: Int,
        zoneId: ZoneId,
    ): Boolean {
        return nightWindowsOverlapping(
            gapStart = startMs,
            gapEnd = endMs,
            nightStartHour = nightStartHour,
            nightEndHour = nightEndHour,
            zoneId = zoneId,
        ).isNotEmpty()
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
