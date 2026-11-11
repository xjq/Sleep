package com.self.sleepcollect.algorithm

import java.time.ZoneId

/**
 * 推测睡眠算法的可调常量。
 * 全部集中在此，修改阈值时只需改这一处（或调用 [SleepCalculator.compute] 时显式传参）。
 *
 * 夜间窗口定义为：当天 [NIGHT_START_HOUR]:00 至次日 [NIGHT_END_HOUR]:00，时区为 [ZONE_ID]。
 */
object SleepConstants {
    /** 最小静默间隙：90 分钟。短于此时长的间隙不视为睡眠。 */
    const val MIN_SLEEP_GAP_MS: Long = 90L * 60L * 1000L

    /** 夜间窗口起始小时（当天 22 点）。 */
    const val NIGHT_START_HOUR: Int = 22

    /** 夜间窗口结束小时（次日 10 点）。 */
    const val NIGHT_END_HOUR: Int = 10

    /**
     * 计算 day_date 与夜间窗口边界所使用的时区。
     * 本自用工具面向小米国区用户，固定 Asia/Shanghai，避免设备时区被改后日期错位。
     */
    val ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 与 UsageEvents.Event.ACTIVITY_RESUMED / MOVE_TO_FOREGROUND 相同，值为 1。 */
    const val EVENT_ACTIVITY_RESUMED: Int = 1

    /** 与 UsageEvents.Event.ACTIVITY_PAUSED / MOVE_TO_BACKGROUND 相同，值为 2。 */
    const val EVENT_ACTIVITY_PAUSED: Int = 2
}
