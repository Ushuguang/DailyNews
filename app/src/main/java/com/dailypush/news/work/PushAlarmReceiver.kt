package com.dailypush.news.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dailypush.news.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 闹钟触发器：收到广播后重新排下一天的闹钟，并把抓取任务交给 WorkManager。
 */
class PushAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: PushScheduler.ACTION_DAILY
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // 一次性闹钟：立刻把（所有）时间点的下一次都排上，保证每天都能触发
                val settings = SettingsRepository(app).settingsFlow.first()
                if (settings.pushEnabled) {
                    PushScheduler.schedule(app, settings.pushTimes)
                }

                val notify = action != PushScheduler.ACTION_REFRESH
                // 带上是哪个时间点触发的，这样每个时间点一天只推一次
                val slot = intent.getStringExtra(PushScheduler.EXTRA_SLOT).orEmpty()
                PushScheduler.runNow(app, notify, slot.ifBlank { null })
            } catch (t: Throwable) {
                // 广播里出错也不能让系统记为崩溃
            } finally {
                pendingResult.finish()
            }
        }
    }
}
