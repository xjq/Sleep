package com.self.sleepcollect.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object UploadStatus {
    const val PENDING = "pending"
    const val SUCCESS = "success"
    const val FAILED = "failed"
}

@Entity(tableName = "collect_runs")
data class CollectRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectAt: Long,
    val rangeStart: Long,
    val rangeEnd: Long,
    val totalEventCount: Int,
    val relevantEventCount: Int,
    /** 完整上报 JSON，失败后原样重试。 */
    val payloadJson: String,
    val uploadStatus: String,
    val httpCode: Int? = null,
    val errorMessage: String? = null,
)

@Entity(
    tableName = "usage_events",
    foreignKeys = [
        ForeignKey(
            entity = CollectRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val timestampMs: Long,
    val type: Int,
    val packageName: String,
)

@Entity(
    tableName = "sleep_days",
    foreignKeys = [
        ForeignKey(
            entity = CollectRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class SleepDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val dayDate: String,
    val sleepStartTs: Long,
    val sleepEndTs: Long,
    val sleepDurationMin: Long,
    val lastAppPackage: String,
    /** raw_sessions JSON 数组，供 UI 展开预览。 */
    val rawSessionsJson: String,
)
