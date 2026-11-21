package com.dailypush.news.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dailypush.news.util.TimeUtil
import java.util.concurrent.TimeUnit

/**
 * 定时推送的调度器。
 *
 * 支持**一天多个推送时间点**（早中晚各一次），每个时间点：
 *  1. AlarmManager 精确闹钟 —— 负责「准点」触发（HyperOS 上需要精确闹钟 + 电池白名单）
 *  2. WorkManager 周期任务 —— 兜底，防止厂商系统把闹钟丢掉
 *
 * 每个时间点带一个「时段标记」（如 08:00），用来保证同一个时间点一天只推一次，
 * 但不同时间点可以各推一次。
 */
object PushScheduler {

    const val ACTION_DAILY = "com.dailypush.news.action.DAILY_PUSH"
    const val ACTION_REFRESH = "com.dailypush.news.action.REFRESH"

    /** 闹钟/任务里带的时段标记 */
    const val EXTRA_SLOT = "slot"

    private const val ALARM_REQUEST_CODE = 8801
    private const val WORK_ONESHOT = "daily_push_oneshot"
    private const val WORK_PERIODIC_PREFIX = "daily_push_periodic_"

    /** 一天最多几个时间点（和 AppSettings.MAX_PUSH_TIMES 对齐） */
    private const val MAX_SLOTS = 6

    /** 设定推送时间点（可多个） */
    fun schedule(context: Context, times: List<String>) {
        val app = context.applicationContext
        val slots = times
            .map { normalizeSlot(it) }
            .filterNotNull()
            .distinct()
            .sorted()
            .take(MAX_SLOTS)
            .ifEmpty { listOf("08:00") }

        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false))

        slots.forEachIndexed { index, slot ->
            val hour = slot.substringBefore(":").toInt()
            val minute = slot.substringAfter(":").toInt()
            val trigger = TimeUtil.nextOccurrence(hour, minute)

            val pendingIntent = PendingIntent.getBroadcast(
                app,
                ALARM_REQUEST_CODE + index,
                Intent(app, PushAlarmReceiver::class.java)
                    .setAction(ACTION_DAILY)
                    .putExtra(EXTRA_SLOT, slot),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            runCatching {
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        trigger.toInstant().toEpochMilli(),
                        pendingIntent
                    )
                } else {
                    // 没有精确闹钟权限时退化为「允许在低电耗模式下触发」，仍能准点附近的窗口内唤醒
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        trigger.toInstant().toEpochMilli(),
                        pendingIntent
                    )
                }
            }

            // 兜底周期任务：万一闹钟被系统清掉，24 小时周期任务还能补一次
            val delay = trigger.toInstant().toEpochMilli() - System.currentTimeMillis()
            val periodic = PeriodicWorkRequestBuilder<NewsSyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(networkConstraints())
                .setInputData(
                    workDataOf(
                        NewsSyncWorker.KEY_NOTIFY to true,
                        NewsSyncWorker.KEY_SLOT to slot
                    )
                )
                .build()
            runCatching {
                WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                    "$WORK_PERIODIC_PREFIX$index",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodic
                )
            }
        }

        // 时间点变少时，把多出来的旧闹钟和旧任务撤掉
        for (index in slots.size until MAX_SLOTS) {
            val stale = PendingIntent.getBroadcast(
                app,
                ALARM_REQUEST_CODE + index,
                Intent(app, PushAlarmReceiver::class.java).setAction(ACTION_DAILY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { alarmManager.cancel(stale) }
            runCatching {
                WorkManager.getInstance(app).cancelUniqueWork("$WORK_PERIODIC_PREFIX$index")
            }
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        for (index in 0 until MAX_SLOTS) {
            val pendingIntent = PendingIntent.getBroadcast(
                app,
                ALARM_REQUEST_CODE + index,
                Intent(app, PushAlarmReceiver::class.java).setAction(ACTION_DAILY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { alarmManager?.cancel(pendingIntent) }
            runCatching {
                WorkManager.getInstance(app).cancelUniqueWork("$WORK_PERIODIC_PREFIX$index")
            }
        }
        runCatching {
            WorkManager.getInstance(app).cancelUniqueWork(WORK_ONESHOT)
        }
    }

    /**
     * 立刻执行一次抓取。
     * @param notify 是否发通知
     * @param slot   触发的时间点标记（空 = 手动触发）
     * @param force  是否强制执行（跳过「同一时间点一天只推一次」的限制）
     */
    fun runNow(context: Context, notify: Boolean, slot: String? = null, force: Boolean = false) {
        val app = context.applicationContext
        val data = workDataOf(
            NewsSyncWorker.KEY_NOTIFY to notify,
            NewsSyncWorker.KEY_SLOT to (slot ?: ""),
            NewsSyncWorker.KEY_FORCE to force
        )
        val request = OneTimeWorkRequestBuilder<NewsSyncWorker>()
            .setInputData(data)
            .setConstraints(networkConstraints())
            .apply {
                if (notify && notificationsAllowed(app)) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        runCatching {
            WorkManager.getInstance(app).enqueueUniqueWork(
                WORK_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    /** 把 "8:30" / "08:30" 统一成 "08:30" */
    private fun normalizeSlot(raw: String): String? {
        val text = raw.trim()
        if (!text.matches(Regex("\\d{1,2}:\\d{2}"))) return null
        val h = text.substringBefore(":").toIntOrNull() ?: return null
        val m = text.substringAfter(":").toIntOrNull() ?: return null
        return "%02d:%02d".format(h.coerceIn(0, 23), m.coerceIn(0, 59))
    }

    private fun notificationsAllowed(context: Context): Boolean =
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
