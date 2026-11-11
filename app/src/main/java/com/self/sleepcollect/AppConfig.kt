package com.self.sleepcollect

/**
 * 应用级配置。后端地址由用户在界面填写并持久化，这里只提供占位提示，不是真实服务器。
 */
object AppConfig {
    const val LOG_TAG = "SleepCollect"

    /** 输入框占位符，请替换为你自己的完整上报 URL。 */
    const val BACKEND_URL_PLACEHOLDER = "https://your-server.example/api/sleep/report"
}
