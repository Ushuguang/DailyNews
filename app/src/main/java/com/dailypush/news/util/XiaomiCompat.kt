package com.dailypush.news.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 小米 / 澎湃 OS 相关适配。
 *
 * 澎湃 OS（MIUI 13+）对后台管控很严：
 *  - 必须允许「自启动」，否则应用被划掉后定时任务不会恢复
 *  - 必须加入「电池优化白名单」，否则息屏后闹钟会被延迟
 *  - 必须允许通知，否则 Android 13+ 收不到任何推送
 */
object XiaomiCompat {

    /** 是否已加入电池优化白名单 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 请求加入电池优化白名单 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 跳转到澎湃 OS 的「自启动管理」页；返回 false 表示找不到入口 */
    fun openAutostartSettings(context: Context): Boolean {
        val pkg = context.packageName
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter" to "com.miui.permcenter.permissions.PermissionsEditorActivity",
            "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        )
        for ((componentPkg, cls) in candidates) {
            val intent = Intent()
                .setClassName(componentPkg, cls)
                .putExtra("package_name", pkg)
                .putExtra("packageName", pkg)
                .putExtra("android.intent.extra.PACKAGE_NAME", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                val started = runCatching { context.startActivity(intent) }.isSuccess
                if (started) return true
            }
        }
        return false
    }

    /** 通知权限 */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 跳转通知设置页 */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isFailure) {
            openAppDetails(context)
        }
    }

    /** 是否可以设置精确闹钟 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(android.app.AlarmManager::class.java) ?: return false
        return alarm.canScheduleExactAlarms()
    }

    /** 申请精确闹钟权限 */
    fun requestExactAlarms(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isFailure) {
                openAppDetails(context)
            }
        } else {
            openAppDetails(context)
        }
    }

    /** 跳转应用详情页 */
    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
