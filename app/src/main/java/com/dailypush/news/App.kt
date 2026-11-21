package com.dailypush.news

import android.app.Application
import com.dailypush.news.data.SettingsRepository
import com.dailypush.news.notify.Notifier
import com.dailypush.news.work.PushScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)

        // 每次进程启动都重新排一次定时任务。
        // 这样「刚装好还没动过设置」的用户也能立刻拥有定时推送，
        // 同时能在系统清掉闹钟后自动恢复。
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = SettingsRepository(this@App).settingsFlow.first()
                if (settings.pushEnabled) {
                    PushScheduler.schedule(this@App, settings.pushTimes)
                } else {
                    PushScheduler.cancel(this@App)
                }
            } catch (t: Throwable) {
                // 排程失败不影响 App 正常启动
            }
        }
    }
}
