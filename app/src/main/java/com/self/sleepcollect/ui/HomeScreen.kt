package com.self.sleepcollect.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.self.sleepcollect.AppConfig
import com.self.sleepcollect.algorithm.SleepConstants
import com.self.sleepcollect.collect.UsagePermission
import com.self.sleepcollect.data.local.UploadStatus
import com.self.sleepcollect.data.remote.RawSessionDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshPermission()
    }

    // 从设置页返回、或 App 回到前台时重新校验权限
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermission()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.persistBackendUrl()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("推测睡眠采集") })
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PermissionCard(
                    granted = state.hasUsagePermission,
                    onOpenSettings = {
                        settingsLauncher.launch(UsagePermission.usageAccessSettingsIntent().apply {
                            flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                        })
                    },
                )
            }
            item { WarningCard() }
            item { DisclaimerCard() }
            item { LimitationsCard() }
            item {
                RangeCard(
                    startMs = state.rangeStartMs,
                    endMs = state.rangeEndMs,
                    onStart = viewModel::setRangeStart,
                    onEnd = viewModel::setRangeEnd,
                )
            }
            item {
                OutlinedTextField(
                    value = state.backendUrl,
                    onValueChange = viewModel::setBackendUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("后端上报地址（完整 URL）") },
                    placeholder = { Text(AppConfig.BACKEND_URL_PLACEHOLDER) },
                    singleLine = true,
                    supportingText = {
                        Text("请填写你自己的接口完整地址。采集完成后会把 JSON POST 到该地址；留空则只缓存到本地，可稍后重试。")
                    },
                )
            }
            item {
                Button(
                    onClick = {
                        viewModel.persistBackendUrl()
                        viewModel.collectAndReport()
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (state.busy) "正在采集…" else "开始采集并上报")
                }
            }
            if (state.busy) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            if (state.statusText.isNotBlank()) {
                item {
                    Text(
                        text = state.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.errorText != null) {
                item {
                    Text(
                        text = state.errorText.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state.lastEventCount != null) {
                item {
                    Text(
                        text = "最近一次 queryEvents 返回事件总条数：${state.lastEventCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.pendingRuns.isNotEmpty()) {
                item {
                    PendingCard(
                        pending = state.pendingRuns,
                        enabled = !state.busy,
                        onRetry = viewModel::retryPending,
                    )
                }
            }
            item {
                Text(
                    text = "采集结果",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.latestRun != null) {
                item {
                    val run = state.latestRun
                    val uploadLabel = when (run?.uploadStatus) {
                        UploadStatus.SUCCESS -> "上报成功" + (run.httpCode?.let { "（HTTP $it）" } ?: "")
                        UploadStatus.FAILED -> "上报失败" + (run.httpCode?.let { "（HTTP $it）" } ?: "")
                        UploadStatus.PENDING -> "待上报"
                        else -> run?.uploadStatus.orEmpty()
                    }
                    Text(
                        text = "最近一次采集：${formatDateTime(run!!.collectAt)}　$uploadLabel　区间 ${formatDateTime(run.rangeStart)} ~ ${formatDateTime(run.rangeEnd)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.sleepDays.isEmpty() && state.latestRun != null) {
                item {
                    Text(
                        text = "该时间范围内没有满足条件的推测睡眠（夜间窗口内静默不足 90 分钟，或没有成对的使用会话）。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.sleepDays, key = { it.dayDate }) { day ->
                SleepDayCard(
                    day = day,
                    onToggle = { viewModel.toggleExpanded(day.dayDate) },
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}


@Composable
private fun PermissionCard(granted: Boolean, onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(
        containerColor = if (granted) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    )) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("使用情况访问权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (granted) "已授予。每次采集前仍会重新校验（MIUI 可能静默收回）。" else "未授予。没有该权限将无法读取 UsageEvents。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!granted) {
                Button(onClick = onOpenSettings) { Text("去系统设置开启") }
            } else {
                OutlinedButton(onClick = onOpenSettings) { Text("打开系统设置") }
            }
        }
    }
}

@Composable
private fun WarningCard() {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("小米 MIUI / 澎湃OS 提示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "小米系统会对 UsageStats 数据做裁剪，存储周期通常只有约 7～14 天。超过这个时间的记录可能查不到，界面和结果为空并不一定代表当时没有使用手机。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("免责声明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "本应用根据前台应用的打开/退出记录推测睡眠，不是医疗级睡眠监测，结果仅供个人参考，不能用于健康诊断。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LimitationsCard() {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("已知限制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("• 只能看到前台 App 事件。仅通知引起的短暂亮屏（没有前台应用）无法识别，可能让睡眠看起来更长。", style = MaterialTheme.typography.bodyMedium)
            Text("• 半夜打开手机刷一会儿，会被判定为醒来，睡眠窗口会被拆开。", style = MaterialTheme.typography.bodyMedium)
            Text("• 恢复出厂设置或关闭「使用情况访问权限」后，系统底层历史数据会被清空。", style = MaterialTheme.typography.bodyMedium)
            Text("• 只做推测睡眠，不是医疗级监测。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeCard(
    startMs: Long,
    endMs: Long,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("采集时间范围", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "默认最近 7 天。请尽量不要选择超过 14 天的区间，更早的数据在 MIUI 上常常为空。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DateTimeRow(label = "开始时间", epochMs = startMs, onChange = onStart)
            DateTimeRow(label = "结束时间", epochMs = endMs, onChange = onEnd)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(label: String, epochMs: Long, onChange: (Long) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pendingDateMs by remember { mutableStateOf(epochMs) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDate = true }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = formatDateTime(epochMs),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }

    if (showDate) {
        val zone = SleepConstants.ZONE_ID
        // DatePicker 使用 UTC 毫秒表示「本地日历日」，这里按上海时区的日期换算到 UTC 当天 00:00。
        val initialUtcMidnight = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
            .toLocalDate()
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMidnight)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val selected = dateState.selectedDateMillis
                    showDate = false
                    if (selected != null) {
                        val current = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
                        val newDate = Instant.ofEpochMilli(selected).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        pendingDateMs = newDate.atTime(current.hour, current.minute).atZone(zone).toInstant().toEpochMilli()
                        showTime = true
                    }
                }) { Text("下一步") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTime) {
        val zone = SleepConstants.ZONE_ID
        val current = LocalDateTime.ofInstant(Instant.ofEpochMilli(pendingDateMs), zone)
        val timeState = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = true)
        Dialog(onDismissRequest = { showTime = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("选择时间", style = MaterialTheme.typography.titleMedium)
                    TimePicker(state = timeState)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showTime = false }) { Text("取消") }
                        TextButton(onClick = {
                            val picked = current.withHour(timeState.hour).withMinute(timeState.minute)
                                .atZone(zone).toInstant().toEpochMilli()
                            onChange(picked)
                            showTime = false
                        }) { Text("确定") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingCard(
    pending: List<PendingRunUi>,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("有 ${pending.size} 条采集结果尚未成功上报", style = MaterialTheme.typography.titleMedium)
            pending.take(5).forEach { run ->
                val status = if (run.status == UploadStatus.PENDING) "待上报" else "失败"
                Text(
                    text = "${formatDateTime(run.collectAt)}　$status" +
                        (run.errorMessage?.let { "：$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (pending.size > 5) {
                Text("……还有 ${pending.size - 5} 条", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onRetry, enabled = enabled) { Text("重试上报") }
        }
    }
}

@Composable
private fun SleepDayCard(day: SleepDayUi, onToggle: () -> Unit) {
    Card {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(day.dayDate, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatDateTime(day.sleepStartTs)}  →  ${formatDateTime(day.sleepEndTs)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "时长 ${formatDuration(day.sleepDurationMin)}　入睡前应用：${day.lastAppPackage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (day.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (day.expanded) "收起会话" else "展开会话",
                )
            }
            if (day.expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("原始会话预览", style = MaterialTheme.typography.labelLarge)
                    if (day.rawSessions.isEmpty()) {
                        Text("无会话数据", style = MaterialTheme.typography.bodySmall)
                    } else {
                        day.rawSessions.forEach { session ->
                            SessionLine(session)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionLine(session: RawSessionDto) {
    Text(
        text = "${formatDateTime(session.startTs)} ~ ${formatTime(session.endTs)}  ${session.pkg}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

private fun formatDateTime(epochMs: Long): String {
    return DATE_TIME_FMT.format(
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), SleepConstants.ZONE_ID),
    )
}

private fun formatTime(epochMs: Long): String {
    return TIME_FMT.format(
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), SleepConstants.ZONE_ID),
    )
}

private fun formatDuration(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
}
