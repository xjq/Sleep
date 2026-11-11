package com.self.sleepcollect.collect

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.self.sleepcollect.AppConfig
import com.self.sleepcollect.algorithm.SleepConstants
import com.self.sleepcollect.algorithm.UsageEventInput

/**
 * 一次性前台使用事件采集。只在用户手动触发时调用，不注册监听、不做保活。
 *
 * 小米部分 ROM 对 [UsageStatsManager.queryEvents] 返回条数可能有上限，
 * 因此必须把「系统返回的总事件数」打到日志，便于排查丢数据。
 */
object UsageEventCollector {

    data class Collection(
        /** queryEvents 迭代到的全部事件条数（含非 RESUMED/PAUSED）。 */
        val totalEventCount: Int,
        val events: List<UsageEventInput>,
    )

    fun collect(context: Context, rangeStartMs: Long, rangeEndMs: Long): Collection {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents: UsageEvents = manager.queryEvents(rangeStartMs, rangeEndMs)

        // Event 对象会被复用，必须立刻拷贝字段
        val reused = UsageEvents.Event()
        val collected = mutableListOf<UsageEventInput>()
        var total = 0

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(reused)
            total++
            val type = reused.eventType
            val pkg = reused.packageName ?: ""
            val ts = reused.timeStamp
            if (type == SleepConstants.EVENT_ACTIVITY_RESUMED ||
                type == SleepConstants.EVENT_ACTIVITY_PAUSED
            ) {
                if (pkg.isNotBlank()) {
                    collected += UsageEventInput(timestampMs = ts, type = type, packageName = pkg)
                    Log.d(
                        AppConfig.LOG_TAG,
                        "raw event ts=$ts type=$type pkg=$pkg",
                    )
                }
            }
        }

        Log.i(
            AppConfig.LOG_TAG,
            "queryEvents 返回事件总条数=$total, 其中 RESUMED/PAUSED=${collected.size}, " +
                "range=[$rangeStartMs, $rangeEndMs]",
        )
        return Collection(totalEventCount = total, events = collected)
    }
}
