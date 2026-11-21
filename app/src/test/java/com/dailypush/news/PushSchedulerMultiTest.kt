package com.dailypush.news

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailypush.news.work.PushScheduler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 多个推送时间点的调度。
 *
 * 注意：
 *  1. 方法名用 ASCII —— 中文方法名 + 捕获局部变量的 lambda 会生成带中文的 class 文件名，
 *     在 POSIX 编码的沙箱 JVM 里会抛 InvalidPathException。
 *  2. 每个用例先等 App 启动时的自动排程跑完再清场 —— App.onCreate 会在后台线程里
 *     按默认配置排一个 08:00 的闹钟，不等它结束会导致计数忽多忽少。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class PushSchedulerMultiTest {

    private fun alarmCount(context: Context): Int {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        return shadowOf(alarmManager).scheduledAlarms.size
    }

    /** 等 App 启动排程结束，并把现场清干净 */
    private fun clean(context: Context) {
        Thread.sleep(500)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        PushScheduler.cancel(context)
    }

    @Test
    fun threeSlotsThreeAlarms() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clean(context)
        assertEquals(0, alarmCount(context))
        PushScheduler.schedule(context, listOf("08:00", "12:00", "20:00"))
        assertEquals("三个时间点应该排三个闹钟", 3, alarmCount(context))
    }

    @Test
    fun fewerSlotsCancelsExtraAlarms() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clean(context)
        PushScheduler.schedule(context, listOf("08:00", "12:00", "20:00"))
        assertEquals("第一次排三个闹钟", 3, alarmCount(context))
        PushScheduler.schedule(context, listOf("08:00"))
        assertEquals("只剩一个时间点时应该只剩一个闹钟", 1, alarmCount(context))
    }

    @Test
    fun maxSixSlots() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clean(context)
        val times = (0..9).map { "%02d:00".format(it) }
        PushScheduler.schedule(context, times)
        assertEquals("一天最多六个时间点", 6, alarmCount(context))
    }

    @Test
    fun cancelClearsEverything() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clean(context)
        PushScheduler.schedule(context, listOf("08:00", "12:00", "20:00"))
        assertEquals(3, alarmCount(context))
        PushScheduler.cancel(context)
        assertEquals("取消后应该一个闹钟都不剩", 0, alarmCount(context))
    }
}
