package com.self.sleepcollect.collect

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.self.sleepcollect.AppConfig

/**
 * 使用情况访问权限（PACKAGE_USAGE_STATS / OPSTR_GET_USAGE_STATS）。
 *
 * 重要：不能只靠本地缓存标记判断。MIUI 可能在用户不知情时静默收回该权限，
 * 每次采集前必须通过 [AppOpsManager] 现场校验。
 */
object UsagePermission {

    /**
     * 通过 AppOps 实时查询是否已授予使用情况访问权限。
     */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        val granted = mode == AppOpsManager.MODE_ALLOWED
        Log.i(AppConfig.LOG_TAG, "权限校验 PACKAGE_USAGE_STATS granted=$granted mode=$mode")
        return granted
    }

    /** 跳转到系统「使用情况访问权限」设置页，引导用户手动开启。 */
    fun usageAccessSettingsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
