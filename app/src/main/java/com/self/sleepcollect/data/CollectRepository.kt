package com.self.sleepcollect.data

import android.util.Log
import com.self.sleepcollect.AppConfig
import com.self.sleepcollect.algorithm.RawSession
import com.self.sleepcollect.algorithm.SleepComputation
import com.self.sleepcollect.algorithm.SleepResult
import com.self.sleepcollect.algorithm.UsageEventInput
import com.self.sleepcollect.data.local.CollectDao
import com.self.sleepcollect.data.local.CollectRunEntity
import com.self.sleepcollect.data.local.SleepDayEntity
import com.self.sleepcollect.data.local.UploadStatus
import com.self.sleepcollect.data.local.UsageEventEntity
import com.self.sleepcollect.data.remote.CollectReport
import com.self.sleepcollect.data.remote.RawSessionDto
import com.self.sleepcollect.data.remote.ReportApi
import com.self.sleepcollect.data.remote.ReportClient
import com.self.sleepcollect.data.remote.SleepItemDto
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CollectRepository(
    private val dao: CollectDao,
    private val api: ReportApi = ReportClient.api,
) {
    val latestRun: Flow<CollectRunEntity?> = dao.observeLatestRun()
    val pendingOrFailed: Flow<List<CollectRunEntity>> = dao.observePendingOrFailed()

    fun sleepsOf(runId: Long): Flow<List<SleepDayEntity>> = dao.observeSleeps(runId)

    suspend fun getSleeps(runId: Long): List<SleepDayEntity> = dao.getSleeps(runId)

    suspend fun getPendingOrFailed(): List<CollectRunEntity> = dao.getPendingOrFailed()

    fun buildReport(
        collectAt: Long,
        rangeStart: Long,
        rangeEnd: Long,
        sleeps: List<SleepResult>,
    ): CollectReport {
        return CollectReport(
            collectAt = collectAt,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            sleepList = sleeps.map { it.toDto() },
        )
    }

    suspend fun persistRun(
        collectAt: Long,
        rangeStart: Long,
        rangeEnd: Long,
        totalEventCount: Int,
        events: List<UsageEventInput>,
        computation: SleepComputation,
        payloadJson: String,
    ): Long {
        val runId = dao.insertRun(
            CollectRunEntity(
                collectAt = collectAt,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                totalEventCount = totalEventCount,
                relevantEventCount = events.size,
                payloadJson = payloadJson,
                uploadStatus = UploadStatus.PENDING,
            ),
        )
        if (events.isNotEmpty()) {
            dao.insertEvents(
                events.map {
                    UsageEventEntity(
                        runId = runId,
                        timestampMs = it.timestampMs,
                        type = it.type,
                        packageName = it.packageName,
                    )
                },
            )
        }
        if (computation.sleeps.isNotEmpty()) {
            dao.insertSleeps(
                computation.sleeps.map { sleep ->
                    SleepDayEntity(
                        runId = runId,
                        dayDate = sleep.dayDate,
                        sleepStartTs = sleep.sleepStartTs,
                        sleepEndTs = sleep.sleepEndTs,
                        sleepDurationMin = sleep.sleepDurationMin,
                        lastAppPackage = sleep.lastAppPackage,
                        rawSessionsJson = ReportClient.gson.toJson(
                            sleep.rawSessions.map { it.toDto() },
                        ),
                    )
                },
            )
        }
        return runId
    }

    /**
     * 将已缓存的 payload 原样 POST 到 [url]。在 IO 线程调用。
     */
    suspend fun uploadStoredPayload(runId: Long, url: String): UploadOutcome {
        val run = dao.getRun(runId) ?: return UploadOutcome(
            success = false,
            httpCode = null,
            message = "本地记录不存在",
        )
        val report = try {
            ReportClient.gson.fromJson(run.payloadJson, CollectReport::class.java)
        } catch (e: Exception) {
            val msg = "本地 payload 无法解析: ${e.message}"
            Log.e(AppConfig.LOG_TAG, msg, e)
            dao.updateUploadStatus(runId, UploadStatus.FAILED, null, msg)
            return UploadOutcome(false, null, msg)
        }
        return upload(runId, url, report)
    }

    suspend fun upload(runId: Long, url: String, report: CollectReport): UploadOutcome {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            val msg = "未填写后端地址，已缓存在本地，待填写后重试"
            Log.w(AppConfig.LOG_TAG, "上报跳过: $msg")
            dao.updateUploadStatus(runId, UploadStatus.PENDING, null, msg)
            return UploadOutcome(false, null, msg)
        }
        if (trimmed.toHttpUrlOrNull() == null ||
            !(trimmed.startsWith("http://") || trimmed.startsWith("https://"))
        ) {
            val msg = "后端地址无效，需为 http/https 完整 URL"
            Log.e(AppConfig.LOG_TAG, "上报失败: $msg url=$trimmed")
            dao.updateUploadStatus(runId, UploadStatus.FAILED, null, msg)
            return UploadOutcome(false, null, msg)
        }

        return try {
            val response = api.postReport(trimmed, report)
            val code = response.code()
            val bodySnippet = try {
                response.body()?.string()?.take(500)
                    ?: response.errorBody()?.string()?.take(500)
                    ?: ""
            } catch (e: Exception) {
                "(读取响应体失败: ${e.message})"
            }
            Log.i(
                AppConfig.LOG_TAG,
                "上报 HTTP code=$code url=$trimmed body=${bodySnippet.replace("\n", " ")}",
            )
            if (response.isSuccessful) {
                dao.updateUploadStatus(runId, UploadStatus.SUCCESS, code, null)
                UploadOutcome(true, code, "上报成功（HTTP $code）")
            } else {
                val msg = "上报失败 HTTP $code ${bodySnippet.take(200)}"
                dao.updateUploadStatus(runId, UploadStatus.FAILED, code, msg)
                UploadOutcome(false, code, msg)
            }
        } catch (e: Exception) {
            val msg = "上报异常: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(AppConfig.LOG_TAG, msg, e)
            dao.updateUploadStatus(runId, UploadStatus.FAILED, null, msg)
            UploadOutcome(false, null, msg)
        }
    }

    data class UploadOutcome(
        val success: Boolean,
        val httpCode: Int?,
        val message: String,
    )
}

private fun SleepResult.toDto() = SleepItemDto(
    dayDate = dayDate,
    sleepStartTs = sleepStartTs,
    sleepEndTs = sleepEndTs,
    sleepDurationMin = sleepDurationMin,
    lastAppPackage = lastAppPackage,
    rawSessions = rawSessions.map { it.toDto() },
)

private fun RawSession.toDto() = RawSessionDto(
    pkg = pkg,
    startTs = startTs,
    endTs = endTs,
)
