package com.self.sleepcollect.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectDao {
    @Insert
    suspend fun insertRun(run: CollectRunEntity): Long

    @Insert
    suspend fun insertEvents(events: List<UsageEventEntity>)

    @Insert
    suspend fun insertSleeps(sleeps: List<SleepDayEntity>)

    @Query(
        """
        UPDATE collect_runs
        SET uploadStatus = :status, httpCode = :httpCode, errorMessage = :errorMessage
        WHERE id = :id
        """,
    )
    suspend fun updateUploadStatus(
        id: Long,
        status: String,
        httpCode: Int?,
        errorMessage: String?,
    )

    @Query("SELECT * FROM collect_runs ORDER BY collectAt DESC")
    fun observeRuns(): Flow<List<CollectRunEntity>>

    @Query("SELECT * FROM collect_runs ORDER BY collectAt DESC LIMIT 1")
    fun observeLatestRun(): Flow<CollectRunEntity?>

    @Query("SELECT * FROM collect_runs WHERE id = :id")
    suspend fun getRun(id: Long): CollectRunEntity?

    @Query("SELECT * FROM sleep_days WHERE runId = :runId ORDER BY dayDate ASC")
    fun observeSleeps(runId: Long): Flow<List<SleepDayEntity>>

    @Query("SELECT * FROM sleep_days WHERE runId = :runId ORDER BY dayDate ASC")
    suspend fun getSleeps(runId: Long): List<SleepDayEntity>

    @Query(
        """
        SELECT * FROM collect_runs
        WHERE uploadStatus IN ('pending', 'failed')
        ORDER BY collectAt DESC
        """,
    )
    fun observePendingOrFailed(): Flow<List<CollectRunEntity>>

    @Query(
        """
        SELECT * FROM collect_runs
        WHERE uploadStatus IN ('pending', 'failed')
        ORDER BY collectAt ASC
        """,
    )
    suspend fun getPendingOrFailed(): List<CollectRunEntity>
}
