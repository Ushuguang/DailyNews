package com.dailypush.news

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 冒烟测试：确认 App 进程能正常启动、通知渠道能建好、主界面能完成组合。
 * 覆盖「装完就崩溃」「界面一开就崩」这类只有真机才会暴露的问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class AppSmokeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // WorkManager 由 androidx.startup 自动初始化，这里不再手动 initialize
    }

    @Test
    fun `应用启动后通知渠道已创建`() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val names = manager.notificationChannels.map { it.id }
        println(">>> 通知渠道：$names")
        assertTrue(names.contains("daily_news"), "应创建汇总新闻渠道")
        assertTrue(names.contains("daily_news_items"), "应创建单条新闻渠道")
        assertTrue(names.contains("fetch_status"), "应创建抓取状态渠道")
    }

    @Test
    fun `主界面能正常启动并完成组合`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        // 让主线程队列里的任务跑完，触发 Compose 的首次组合
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get() != null, "Activity 应已创建")
        println(">>> 主界面组合完成")
    }

    @Test
    fun `应用启动不会崩溃`() {
        assertTrue(true, "")
    }

    private fun assertTrue(value: Boolean, message: String) {
        if (!value) throw AssertionError(message)
    }
}
