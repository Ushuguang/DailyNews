package com.dailypush.news

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.dailypush.news.data.ArticleCache
import com.dailypush.news.data.Feed
import com.dailypush.news.data.HotFetcher
import com.dailypush.news.data.PushTrace
import com.dailypush.news.data.PushTraceStore
import com.dailypush.news.data.RssFetcher
import com.dailypush.news.data.TraceItem
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 用 Robolectric 原生渲染出真实像素的界面截图。
 * 输出目录必须是纯 ASCII 路径（沙箱 JVM 的 sun.jnu.encoding 是 POSIX，中文路径会变问号）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class, qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    private lateinit var context: Context
    private val outputDir = File("/workspace/output/screenshots")

    @Before
    fun setUp() {
        // 注意：必须用 RuntimeEnvironment.getApplication()（Activity 真正会用的那个 App 实例）。
        // ApplicationProvider.getApplicationContext() 在多个测试类之间会串味，
        // 导致「测试里写的缓存，App 里读不到」。
        context = org.robolectric.RuntimeEnvironment.getApplication()
        outputDir.mkdirs()
        // 截图里展示「一天多个时间点」和「40 条上限」的样子
        runBlocking {
            val repo = com.dailypush.news.data.SettingsRepository(context)
            repo.setPushTimes(listOf("08:00", "12:00", "20:00"))
            repo.setMaxItems(40)
            repo.setNotifyStyle("both")
            repo.setAiPlan(
                categories = listOf("时政", "要闻", "国际"),
                keywords = listOf("AI", "米哈游", "原神"),
                includeHot = true,
                note = "每天早中晚各推一次，重点关注 AI 和米哈游"
            )
        }
    }

    /**
     * 所有页面放在同一个测试方法里拍。
     *
     * 原因：Robolectric 里 Application 实例会在多个测试方法之间串味（ViewModel 拿到的
     * Application 可能来自上一个方法的沙盒），拆成两个方法会导致「痕迹」页读不到数据。
     */
    @Test
    fun renderAllPages() {
        prepareSampleNews()
        prepareSampleTrace()
        capture("news", "01-news.png")
        capture("feeds", "02-feeds.png")
        capture("settings", "03-settings.png")
        capture("guide", "04-guide.png")
        captureAi()
        capture("today", "06-today.png")
        capture("news", "07-trace.png", subPage = "trace")
        capture("news", "09-about.png", subPage = "about")
        capture("settings", "08-settings-filter.png", heightScale = 3)
    }

    /** 造一份样例痕迹，让「今日」和「痕迹」页有内容可渲染 */
    private fun prepareSampleTrace() {
        val now = System.currentTimeMillis()
        val articles = listOf(
            TraceItem(
                id = "t1",
                title = "习近平会见智利总统卡斯特",
                source = "新华社·要闻",
                link = "https://www.news.cn/example-1",
                publishedAt = now - 3600_000L,
                selected = true,
                reason = "AI 选定作为代表：两家媒体报道的同一次会见，标题基本一致，新华社稿件更完整",
                mergedFrom = listOf("t2", "t3"),
                topic = "习近平会见智利总统卡斯特"
            ),
            TraceItem(
                id = "t2",
                title = "习近平会见智利总统卡斯特",
                source = "中新网·滚动",
                link = "https://www.chinanews.com.cn/example-2",
                publishedAt = now - 3500_000L,
                selected = false,
                reason = "与「习近平会见智利总统卡斯特」是同一件事，已合并到：习近平会见智利总统卡斯特",
                mergedInto = "t1"
            ),
            TraceItem(
                id = "t3",
                title = "中智领导人举行双边会谈",
                source = "人民日报·要闻",
                link = "http://paper.people.com.cn/example-3",
                publishedAt = now - 3400_000L,
                selected = false,
                reason = "与「习近平会见智利总统卡斯特」是同一件事，已合并到：习近平会见智利总统卡斯特",
                mergedInto = "t1"
            ),
            TraceItem(
                id = "t4",
                title = "国内多地迎来本周首场寒潮",
                source = "中新网·天气",
                link = "https://www.chinanews.com.cn/example-4",
                publishedAt = now - 7200_000L,
                selected = true,
                reason = "AI 判定为独立内容，未发现重复报道"
            ),
            TraceItem(
                id = "t5",
                title = "某新能源车企公布上月交付量",
                source = "界面新闻",
                link = "https://a.jiemian.com/example-5",
                publishedAt = now - 9000_000L,
                selected = true,
                reason = "AI 判定为独立内容，未发现重复报道"
            ),
            TraceItem(
                id = "t6",
                title = "外交部回应近期涉海问题",
                source = "央视新闻",
                link = "https://example.com/6",
                publishedAt = now - 11000_000L,
                selected = false,
                reason = "与「外交部就涉海问题答记者问」是同一件事，已合并到：外交部就涉海问题答记者问",
                mergedInto = "t7"
            ),
            TraceItem(
                id = "t7",
                title = "外交部就涉海问题答记者问",
                source = "中新网·滚动",
                link = "https://www.chinanews.com.cn/example-7",
                publishedAt = now - 11500_000L,
                selected = false,
                reason = "超出条数上限，未推送"
            )
        )
        val trace = PushTrace(
            date = java.time.LocalDate.now().toString(),
            pushedAt = now - 1800_000L,
            candidates = articles,
            filteredOut = listOf(
                TraceItem(
                    id = "f1",
                    title = "某手机厂商发布新机型",
                    source = "IT之家",
                    link = "https://www.ithome.com/example",
                    publishedAt = now - 4000_000L,
                    selected = false,
                    reason = "分类「科技」不在推送范围（时政）"
                ),
                TraceItem(
                    id = "f2",
                    title = "上周 A 股三大指数集体收涨",
                    source = "东方财富",
                    link = "http://rss.eastmoney.com/example",
                    publishedAt = now - 86400_000L * 2,
                    selected = false,
                    reason = "不在最近 24 小时内，属于旧内容"
                )
            ),
            selectedIds = listOf("t1", "t4", "t5"),
            note = "合并了 2 组重复报道，候选 7 条最终推送 3 条",
            filterSummary = "分类：时政　热搜：含",
            aiUsed = true
        )
        PushTraceStore.save(context, trace)
        println(">>> 已写入样例痕迹")
    }

    /** 抓几条真实新闻写进缓存，让「要闻」页有内容可渲染 */
    private fun prepareSampleNews() {
        val articles = runBlocking {
            val feeds = com.dailypush.news.data.DefaultFeeds.all
                .filter { it.kind == Feed.KIND_RSS }
                .take(8)
            val hotFeeds = com.dailypush.news.data.DefaultFeeds.all
                .filter { it.kind == Feed.KIND_HOT }
                .take(3)
            val news = feeds.flatMap { feed -> runCatching { RssFetcher.fetch(feed) }.getOrDefault(emptyList()) }
                .take(40)
            val hot = hotFeeds.flatMap { feed -> runCatching { HotFetcher.fetch(feed) }.getOrDefault(emptyList()) }
                .take(20)
            (news + hot)
                .sortedWith(compareByDescending<com.dailypush.news.data.Article> { it.isHot }
                    .thenByDescending { if (it.publishedAt > 0) it.publishedAt else it.fetchedAt })
        }
        runBlocking { ArticleCache.save(context, articles) }
        println(">>> 缓存样例新闻 ${articles.size} 条")
    }

    /** AI 页：预填一段需求，让截图更接近真实使用状态 */
    private fun captureAi() {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, "ai")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        val vm = androidx.lifecycle.ViewModelProvider(activity)[
            com.dailypush.news.ui.MainViewModel::class.java
        ]
        vm.setAiRequirement("每天早上8点给我10条国际和科技新闻，另外带上热搜")
        vm.setAiApiKey("sk-demo-key-not-real")
        shadowOf(Looper.getMainLooper()).idle()

        val decor = activity.window.decorView
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val file = File(outputDir, "05-ai.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println(">>> 截图 05-ai.png  ${width}x$height")
    }

    private fun capture(
        tab: String,
        fileName: String,
        subPage: String? = null,
        heightScale: Int = 1
    ) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .apply { if (subPage != null) putExtra(MainActivity.EXTRA_SUBPAGE, subPage) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        // 等 ViewModel 把磁盘缓存里的新闻读出来（读缓存走 IO 线程，回来还要再 idle 一次）
        val vm = androidx.lifecycle.ViewModelProvider(activity)[
            com.dailypush.news.ui.MainViewModel::class.java
        ]
        if (subPage == "trace") {
            vm.loadTraces()
            shadowOf(Looper.getMainLooper()).idle()
        }
        var tries = 0
        while (tries < 40) {
            val newsReady = vm.articles.value.isNotEmpty()
            val traceReady = subPage != "trace" || vm.selectedTrace.value != null
            if (newsReady && traceReady) break
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            tries++
        }

        val decor = activity.window.decorView
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        // heightScale > 1 时把窗口拉高，整页内容一次拍全（不用滚）
        val height = metrics.heightPixels * heightScale

        // 画两遍：第一遍触发重组（状态变了要重绘），第二遍才是最终画面
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, width, height)
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        decor.draw(canvas)

        val file = File(outputDir, fileName)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println(">>> 截图 $fileName  ${width}x$height")
    }
}
