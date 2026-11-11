package com.self.sleepcollect.algorithm

/**
 * 纯 JVM 输入：一条前台生命周期事件。
 * [type] 仅使用 1=RESUMED / 2=PAUSED；其它类型应在采集层过滤掉。
 */
data class UsageEventInput(
    val timestampMs: Long,
    val type: Int,
    val packageName: String,
)

/**
 * 由 RESUMED/PAUSED 组装出的一次前台使用会话。
 *
 * @param closed false 表示采集区间结束时仍未收到 PAUSED，[endTs] 被填为 rangeEnd，
 * 仅供会话预览使用，不得作为“入睡时间”（上一次使用结束）。
 */
data class ForegroundSession(
    val pkg: String,
    val startTs: Long,
    val endTs: Long,
    val closed: Boolean = true,
)

/** 上报 / UI 预览用的原始会话片段。 */
data class RawSession(
    val pkg: String,
    val startTs: Long,
    val endTs: Long,
)

/** 某一夜间窗口的推测睡眠结果。 */
data class SleepResult(
    /** 夜间窗口起始那天的日期，格式 YYYY-MM-DD（即使醒来已是次日）。 */
    val dayDate: String,
    val sleepStartTs: Long,
    val sleepEndTs: Long,
    /** 整分钟数，向下取整。 */
    val sleepDurationMin: Long,
    /** 入睡前最后一个前台应用包名。 */
    val lastAppPackage: String,
    val rawSessions: List<RawSession>,
)

/**
 * 两次前台会话之间的静默间隙，以及过滤 / 归属决策，供日志排查。
 */
data class SilentGap(
    val startTs: Long,
    val endTs: Long,
    val durationMs: Long,
    val lastAppPackage: String,
    val nextAppPackage: String,
    /** 是否通过时长与夜间窗口过滤（尚未比较“当日最长”）。 */
    val passedFilter: Boolean,
    val filterReason: String?,
    /** 归属的夜间窗口日期（YYYY-MM-DD）；未通过过滤时为 null。 */
    val assignedDayDate: String?,
    /** 与所归属夜间窗口的重叠毫秒数。 */
    val overlapMs: Long,
    /** 是否被选为该日的推测睡眠（当日最长合格间隙）。 */
    val selectedAsSleep: Boolean,
    val discardReason: String? = null,
) {
    val durationMin: Long get() = durationMs / 60_000L
}

/** 算法一次计算的完整输出。 */
data class SleepComputation(
    val sessions: List<ForegroundSession>,
    val gaps: List<SilentGap>,
    val sleeps: List<SleepResult>,
)
