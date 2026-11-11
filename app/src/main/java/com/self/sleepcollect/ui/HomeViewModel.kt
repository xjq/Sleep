package com.self.sleepcollect.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.self.sleepcollect.AppConfig
import com.self.sleepcollect.algorithm.SleepCalculator
import com.self.sleepcollect.algorithm.SleepConstants
import com.self.sleepcollect.collect.UsageEventCollector
import com.self.sleepcollect.collect.UsagePermission
import com.self.sleepcollect.data.CollectRepository
import com.self.sleepcollect.data.local.CollectRunEntity
import com.self.sleepcollect.data.local.SleepDayEntity
import com.self.sleepcollect.data.prefs.UserPreferences
import com.self.sleepcollect.data.remote.RawSessionDto
import com.self.sleepcollect.data.remote.ReportClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class SleepDayUi(
    val dayDate: String,
    val sleepStartTs: Long,
    val sleepEndTs: Long,
    val sleepDurationMin: Long,
    val lastAppPackage: String,
    val rawSessions: List<RawSessionDto>,
    val expanded: Boolean = false,
)

data class PendingRunUi(
    val id: Long,
    val collectAt: Long,
    val status: String,
    val errorMessage: String?,
)

data class HomeUiState(
    val hasUsagePermission: Boolean = false,
    val rangeStartMs: Long = defaultRangeStart(),
    val rangeEndMs: Long = defaultRangeEnd(),
    val backendUrl: String = "",
    val busy: Boolean = false,
    val statusText: String = "",
    val errorText: String? = null,
    val latestRun: CollectRunEntity? = null,
    val sleepDays: List<SleepDayUi> = emptyList(),
    val pendingRuns: List<PendingRunUi> = emptyList(),
    val lastEventCount: Int? = null,
)

fun defaultRangeEnd(): Long {
    return LocalDateTime.now(SleepConstants.ZONE_ID)
        .truncatedTo(ChronoUnit.MINUTES)
        .atZone(SleepConstants.ZONE_ID)
        .toInstant()
        .toEpochMilli()
}

fun defaultRangeStart(): Long {
    return LocalDateTime.now(SleepConstants.ZONE_ID)
        .truncatedTo(ChronoUnit.MINUTES)
        .minusDays(7)
        .atZone(SleepConstants.ZONE_ID)
        .toInstant()
        .toEpochMilli()
}

class HomeViewModel(
    application: Application,
    private val repository: CollectRepository,
    private val preferences: UserPreferences,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refreshPermission()
        viewModelScope.launch {
            preferences.backendUrl.collect { url ->
                _state.update { it.copy(backendUrl = url) }
            }
        }
        viewModelScope.launch {
            combine(repository.latestRun, repository.pendingOrFailed) { latest, pending ->
                latest to pending
            }.collect { (latest, pending) ->
                val sleeps = if (latest != null) {
                    repository.getSleeps(latest.id).map { it.toUi() }
                } else {
                    emptyList()
                }
                _state.update { prev ->
                    val expandedMap = prev.sleepDays.associate { it.dayDate to it.expanded }
                    prev.copy(
                        latestRun = latest,
                        sleepDays = sleeps.map { day ->
                            day.copy(expanded = expandedMap[day.dayDate] == true)
                        },
                        pendingRuns = pending.map {
                            PendingRunUi(
                                id = it.id,
                                collectAt = it.collectAt,
                                status = it.uploadStatus,
                                errorMessage = it.errorMessage,
                            )
                        },
                    )
                }
            }
        }
    }

    fun refreshPermission() {
        val granted = UsagePermission.hasUsageAccess(getApplication())
        _state.update { it.copy(hasUsagePermission = granted) }
    }

    fun setRangeStart(ms: Long) {
        _state.update { it.copy(rangeStartMs = ms) }
    }

    fun setRangeEnd(ms: Long) {
        _state.update { it.copy(rangeEndMs = ms) }
    }

    fun setBackendUrl(url: String) {
        _state.update { it.copy(backendUrl = url) }
    }

    fun persistBackendUrl() {
        val url = _state.value.backendUrl
        viewModelScope.launch(Dispatchers.IO) {
            preferences.setBackendUrl(url)
        }
    }

    fun toggleExpanded(dayDate: String) {
        _state.update { state ->
            state.copy(
                sleepDays = state.sleepDays.map {
                    if (it.dayDate == dayDate) it.copy(expanded = !it.expanded) else it
                },
            )
        }
    }

    /**
     * 开始采集并上报。每次采集前都重新校验权限，全过程在 IO 线程执行。
     */
    fun collectAndReport() {
        if (_state.value.busy) return
        viewModelScope.launch {
            persistBackendUrl()
            val start = _state.value.rangeStartMs
            val end = _state.value.rangeEndMs
            if (end <= start) {
                _state.update { it.copy(errorText = "结束时间必须晚于开始时间") }
                return@launch
            }

            // 每次采集前现场校验，不信任缓存标记（MIUI 可能静默收回权限）
            val granted = UsagePermission.hasUsageAccess(getApplication())
            _state.update {
                it.copy(
                    hasUsagePermission = granted,
                    busy = granted,
                    errorText = if (granted) null else "未授予使用情况访问权限，请先到系统设置中开启",
                    statusText = if (granted) "正在校验权限…" else it.statusText,
                )
            }
            if (!granted) {
                Log.w(AppConfig.LOG_TAG, "采集中止：使用情况访问权限未授予")
                return@launch
            }

            try {
                _state.update { it.copy(statusText = "正在采集 UsageEvents…") }
                val collection = withContext(Dispatchers.IO) {
                    UsageEventCollector.collect(getApplication(), start, end)
                }

                _state.update {
                    it.copy(
                        statusText = "正在计算推测睡眠…（事件 ${collection.totalEventCount} 条）",
                        lastEventCount = collection.totalEventCount,
                    )
                }

                val computation = withContext(Dispatchers.Default) {
                    SleepCalculator.compute(
                        events = collection.events,
                        rangeStartMs = start,
                        rangeEndMs = end,
                    )
                }
                for (gap in computation.gaps) {
                    val decision = when {
                        gap.selectedAsSleep -> "保留为当日睡眠 day=${gap.assignedDayDate}"
                        gap.discardReason != null -> gap.discardReason
                        else -> gap.filterReason ?: "-"
                    }
                    Log.i(
                        AppConfig.LOG_TAG,
                        "间隙 start=${gap.startTs} end=${gap.endTs} " +
                            "durationMin=${gap.durationMin} passed=${gap.passedFilter} " +
                            "selected=${gap.selectedAsSleep} lastApp=${gap.lastAppPackage} " +
                            "decision=$decision",
                    )
                }
                Log.i(
                    AppConfig.LOG_TAG,
                    "计算完成：会话 ${computation.sessions.size} 段，间隙 ${computation.gaps.size} 条，" +
                        "推测睡眠 ${computation.sleeps.size} 天",
                )

                val collectAt = Instant.now().toEpochMilli()
                val report = repository.buildReport(collectAt, start, end, computation.sleeps)
                val payloadJson = ReportClient.gson.toJson(report)

                _state.update { it.copy(statusText = "正在写入本地缓存并上报…") }
                val url = _state.value.backendUrl
                val uploadMessage = withContext(Dispatchers.IO) {
                    val runId = repository.persistRun(
                        collectAt = collectAt,
                        rangeStart = start,
                        rangeEnd = end,
                        totalEventCount = collection.totalEventCount,
                        events = collection.events,
                        computation = computation,
                        payloadJson = payloadJson,
                    )
                    val first = repository.upload(runId, url, report)
                    val retried = retryPendingUploads(exceptRunId = runId, url = url)
                    buildString {
                        append(first.message)
                        if (retried.isNotEmpty()) {
                            append("；同时重试历史记录：")
                            append(retried)
                        }
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        statusText = uploadMessage,
                        errorText = null,
                    )
                }
            } catch (e: Exception) {
                Log.e(AppConfig.LOG_TAG, "采集失败", e)
                _state.update {
                    it.copy(
                        busy = false,
                        statusText = "",
                        errorText = "采集失败: ${e.message}",
                    )
                }
            }
        }
    }

    fun retryPending() {
        if (_state.value.busy) return
        viewModelScope.launch {
            persistBackendUrl()
            val url = _state.value.backendUrl
            if (url.isBlank()) {
                _state.update { it.copy(errorText = "请先填写后端上报地址再重试") }
                return@launch
            }
            _state.update { it.copy(busy = true, statusText = "正在重试上报…", errorText = null) }
            val message = withContext(Dispatchers.IO) {
                retryPendingUploads(exceptRunId = null, url = url).ifBlank { "没有待重试的记录" }
            }
            _state.update { it.copy(busy = false, statusText = message) }
        }
    }

    /** 对本地 pending/failed 记录做一次性重试，不启动任何后台任务。 */
    private suspend fun retryPendingUploads(exceptRunId: Long?, url: String): String {
        val list = repository.getPendingOrFailed()
            .filter { exceptRunId == null || it.id != exceptRunId }
        if (list.isEmpty()) return ""
        val parts = mutableListOf<String>()
        for (run in list) {
            val outcome = repository.uploadStoredPayload(run.id, url)
            parts += "#${run.id} ${if (outcome.success) "成功" else "失败"}" +
                (outcome.httpCode?.let { " HTTP $it" } ?: "")
        }
        return parts.joinToString("，")
    }
}

private fun SleepDayEntity.toUi(): SleepDayUi {
    val sessions = try {
        ReportClient.gson.fromJson(rawSessionsJson, Array<RawSessionDto>::class.java)?.toList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
    return SleepDayUi(
        dayDate = dayDate,
        sleepStartTs = sleepStartTs,
        sleepEndTs = sleepEndTs,
        sleepDurationMin = sleepDurationMin,
        lastAppPackage = lastAppPackage,
        rawSessions = sessions,
    )
}
