package com.self.sleepcollect.data.remote

import com.google.gson.annotations.SerializedName

/**
 * 上报 JSON 结构，字段名必须与约定完全一致。
 */
data class CollectReport(
    @SerializedName("collect_at") val collectAt: Long,
    @SerializedName("range_start") val rangeStart: Long,
    @SerializedName("range_end") val rangeEnd: Long,
    @SerializedName("sleep_list") val sleepList: List<SleepItemDto>,
)

data class SleepItemDto(
    @SerializedName("day_date") val dayDate: String,
    @SerializedName("sleep_start_ts") val sleepStartTs: Long,
    @SerializedName("sleep_end_ts") val sleepEndTs: Long,
    @SerializedName("sleep_duration_min") val sleepDurationMin: Long,
    @SerializedName("last_app_package") val lastAppPackage: String,
    @SerializedName("raw_sessions") val rawSessions: List<RawSessionDto>,
)

data class RawSessionDto(
    @SerializedName("pkg") val pkg: String,
    @SerializedName("start_ts") val startTs: Long,
    @SerializedName("end_ts") val endTs: Long,
)
