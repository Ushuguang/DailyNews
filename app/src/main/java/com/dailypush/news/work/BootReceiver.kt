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
 * 开机 / 换机 / 修改系统时间后，重新排定定时任务。
 * 没有这一步，重启手机后当天的推送就丢了。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = SettingsRepository(app).settingsFlow.first()
                if (settings.pushEnabled) {
                    PushScheduler.schedule(app, settings.pushTimes)
                } else {
                    PushScheduler.cancel(app)
                }
            } catch (t: Throwable) {
                // ignore
            } finally {
                pendingResult.finish()
            }
        }
    }
}
