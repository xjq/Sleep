# 推测睡眠采集（MiSleepCollector）

面向小米（MIUI / 澎湃OS）的**个人自用**安卓采集工具：打开 App 后手动选择时间范围，读取系统 UsageEvents，按固定算法推测睡眠，并把结果 HTTP POST 到你自己的后端。

- 包名 / applicationId：`com.self.sleepcollect`
- 启动器名称：推测睡眠采集
- 语言：Kotlin；界面：Jetpack Compose（Material 3）；仅一页，无图表
- **不上架** Google Play / 应用商店
- **不申请**自启动、后台活动、电池优化白名单；无保活、无后台监听、无周期任务。采集完成即结束。

---

## 它做什么

1. 检测「使用情况访问权限」。未开启时引导跳转到系统 `USAGE_ACCESS_SETTINGS`。
2. 用户选择起止时间（默认最近 7 天），调用 `UsageStatsManager.queryEvents` 拉取 `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED`。
3. 用独立纯函数组装前台会话、计算静默间隙，过滤夜间窗口（22:00～次日 10:00）且 ≥ 90 分钟的间隙，取当日最长间隙作为推测睡眠。
4. 在首页展示每日结果和原始会话预览。
5. 把约定 JSON POST 到用户填写的完整接口地址；本地 Room（SQLite）缓存事件、计算结果和上报状态，失败可重试。

---

## 用 Android Studio 打开并编译

1. 安装 Android Studio（建议 Ladybug / 2024.2 或更新版本）与 Android SDK 35、JDK 17。
2. **File → Open**，选中本目录 `MiSleepCollector`（含 `settings.gradle.kts` 的那一层）。
3. 等待 Gradle Sync。本仓库已包含 Gradle Wrapper（Gradle 8.10.2），无需另外安装 Gradle。
4. 用真机或模拟器运行 `app` 的 debug 变体，或使用命令行：

```bash
./gradlew :algorithm:test      # 纯 JVM 睡眠算法单测，不需要设备
./gradlew assembleDebug        # 调试包
./gradlew assembleRelease      # 已签名的正式包，无需再输入密码
```

产物路径：

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release 签名 APK：`app/build/outputs/apk/release/app-release.apk`

命令行编译时请使用 JDK 17，并把 `ANDROID_HOME`（或 `local.properties` 里的 `sdk.dir`）指向本机 SDK。

---

## 后端地址怎么填

本应用**不内置真实服务器地址**。首页有一个「后端上报地址（完整 URL）」输入框，内容会保存到 DataStore。

- 请填写**完整端点**，例如 `https://example.com/api/sleep/report`（这只是格式示例）。
- 采集结束后，会把下面结构的 JSON 直接 POST 到该地址。
- 留空时仍会完成本地采集与计算并写入数据库，上报状态为「待上报」，填好地址后再点「重试上报」。
- 个人自用场景可能使用局域网 HTTP，因此开启了 cleartext（明文 HTTP）允许。

JSON 字段名（必须一致）：

```json
{
  "collect_at": 1762800000000,
  "range_start": 1762713600000,
  "range_end": 1762800000000,
  "sleep_list": [
    {
      "day_date": "2026-11-10",
      "sleep_start_ts": 1762741200000,
      "sleep_end_ts": 1762770600000,
      "sleep_duration_min": 490,
      "last_app_package": "com.tiktok",
      "raw_sessions": [
        {"pkg": "com.tiktok", "start_ts": 1762740000000, "end_ts": 1762741200000}
      ]
    }
  ]
}
```

时间戳均为 epoch 毫秒。`collect_at` 为本次采集发生的时间。`day_date` 与夜间窗口按 `Asia/Shanghai` 计算。

超时：连接 15s、读写 30s、整次调用 45s，在 `Dispatchers.IO` 执行。

---

## 小米 / MIUI 上如何开权限

1. 打开本 App，若提示权限未授予，点「去系统设置开启」。
2. 在系统「使用情况访问权限 / 有权查看使用情况的应用」列表中找到「推测睡眠采集」并打开。
3. 返回 App。每次点「开始采集并上报」前都会**重新通过 AppOps 校验**，不依赖本地缓存标记（MIUI 存在权限被系统静默收回的情况）。

不同小米版本路径可能略有差异：设置 → 应用设置 → 权限管理 → 其他权限 → 使用情况访问权限。

### MIUI 对 UsageStats 的保留周期

小米系统会对 UsageStats 做裁剪，**一般只保留约 7～14 天**。更早的数据 `queryEvents` 会得到空结果。首页有醒目提示；请尽量把采集区间放在最近两周内。部分系统版本对单次 `queryEvents` 返回条数也有上限，采集时会用日志输出事件总条数，便于排查丢数据。

### 为什么不申请自启动 / 后台 / 电池白名单

本 App **不常驻后台**：没有前台服务、没有开机广播、没有周期 WorkManager、采集结束即停止。因此不需要自启动、后台活动或电池优化白名单。在小米上申请这些权限反而容易引起误导和审核/使用困扰。

---

## 算法常量（改阈值只改这里）

纯函数模块：`algorithm`（无 Android 依赖，可在 JVM 单测）。

- 常量：`algorithm/src/main/kotlin/com/self/sleepcollect/algorithm/SleepConstants.kt`
- 计算：`algorithm/src/main/kotlin/com/self/sleepcollect/algorithm/SleepCalculator.kt`

```kotlin
const val MIN_SLEEP_GAP_MS = 90 * 60 * 1000L // 最小静默 90 分钟
const val NIGHT_START_HOUR = 22              // 夜间窗口起始 22 点
const val NIGHT_END_HOUR = 10                // 夜间窗口结束次日 10 点
// 时区固定 Asia/Shanghai
```

更细的规则说明见 [`docs/ALGORITHM.md`](docs/ALGORITHM.md)。

单测：`./gradlew :algorithm:test`

---

## 日志

统一 tag：`SleepCollect`。在 logcat 中过滤：

```bash
adb logcat -s SleepCollect
```

会输出：采集前权限校验结果、`queryEvents` 事件总条数、相关原始事件、每条静默间隙的过滤/归属决策、上报 HTTP 状态码和响应摘要。

---

## 签名密钥（仅供个人使用）

`keystore/release.jks` 和 `keystore/signing.properties` **不会进入 Git**（已写入 `.gitignore`）。克隆仓库后请按 `keystore/README.md` 本地生成或拷入你自己的密钥。

仓库只提供模板 `keystore/signing.properties.example`。当 `signing.properties` 存在时，`./gradlew assembleRelease` 会自动签名。Debug 构建使用 Android 默认 debug 证书，不依赖该文件。

这套证书只用于个人安装，不要用于上架。

---

## 已知限制

1. 数据源只有前台 App 的 RESUMED/PAUSED 事件，**无法识别仅通知引起的短暂亮屏**（没有前台应用）。这类情况会让静默间隙看起来更长，从而干扰推测。
2. **半夜打开手机刷一会儿**会被当成醒来，睡眠窗口会被拆成两段；算法在同一夜间窗口取最长的一段。
3. **恢复出厂设置**或**关闭使用情况访问权限**后，系统底层历史数据会被清空，本 App 也无法再读到。
4. 本工具只做**推测睡眠**，**不是医疗级睡眠监测**。界面已标注，结果仅供个人参考。

---

## 工程结构（重要文件）

```
MiSleepCollector/
  algorithm/          纯 JVM 模块：睡眠算法 + 单元测试
  app/                Android 应用（单 Activity + Compose 首页）
  keystore/           个人用 release 签名
  docs/ALGORITHM.md   算法说明
  README.md
```

关键源码：

- `algorithm/.../SleepCalculator.kt`：睡眠计算纯函数
- `algorithm/.../SleepConstants.kt`：阈值常量
- `app/.../collect/UsagePermission.kt`：AppOps 实时权限校验
- `app/.../collect/UsageEventCollector.kt`：queryEvents 遍历与日志
- `app/.../data/CollectRepository.kt`：Room 缓存与上报重试
- `app/.../ui/HomeScreen.kt`：唯一界面
- `app/.../ui/HomeViewModel.kt`：采集 / 计算 / 上报流程（子线程）

清单权限仅有 `INTERNET` 与 `PACKAGE_USAGE_STATS`。
