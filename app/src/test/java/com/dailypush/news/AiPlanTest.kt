package com.dailypush.news

import com.dailypush.news.data.AiClient
import com.dailypush.news.data.AppSettings
import com.dailypush.news.data.Article
import com.dailypush.news.data.Feed
import com.dailypush.news.data.PushFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlanTest {

    @Test
    fun `纯JSON回复能解析`() {
        val reply = """{"pushTime":"09:30","maxItems":12,"categories":["国际","科技"],"keywords":["APEC"],"includeHot":true,"notifyStyle":"both","note":"每天9点半12条"}"""
        val plan = AiClient.extractJson(reply)
        assertNotNull(plan)
        assertEquals(9, plan!!.hour())
        assertEquals(30, plan.minute())
        assertEquals(12, plan.maxItems)
        assertEquals(listOf("国际", "科技"), plan.categories)
        assertEquals(listOf("APEC"), plan.keywords)
        assertTrue(plan.includeHot)
        assertEquals("both", plan.notifyStyle)
    }

    @Test
    fun `带代码块和前后说明的回复也能解析`() {
        val reply = """
            好的，这是配置：
            ```json
            {"pushTime":"07:00","maxItems":5,"categories":["财经"],"keywords":[],"includeHot":false,"notifyStyle":"summary","note":"只要财经"}
            ```
            如需调整请告诉我。
        """.trimIndent()
        val plan = AiClient.extractJson(reply)
        assertNotNull(plan)
        assertEquals(7, plan!!.hour())
        assertEquals(5, plan.maxItems)
        assertEquals(listOf("财经"), plan.categories)
    }

    @Test
    fun `不是JSON时返回空`() {
        assertNull(AiClient.extractJson("抱歉，我无法理解"))
    }

    // ---------------- 推送筛选 ----------------

    private val feeds = listOf(
        Feed("f1", "中新网·国际", "http://x", "国际"),
        Feed("f2", "IT之家", "http://y", "科技"),
        Feed("f3", "微博热搜", "http://z", "热搜", true, Feed.KIND_HOT)
    )

    private fun article(title: String, source: String, hot: Boolean = false) = Article(
        id = title,
        title = title,
        link = "http://a/$title",
        source = source,
        publishedAt = 1L,
        fetchedAt = 1L,
        kind = if (hot) Article.KIND_HOT else Article.KIND_NEWS
    )

    @Test
    fun `按分类过滤`() {
        val settings = AppSettings(pushCategories = listOf("国际"))
        val all = listOf(
            article("国际新闻A", "中新网·国际"),
            article("科技新闻B", "IT之家"),
            article("热搜C", "微博热搜", hot = true)
        )
        val result = PushFilter.apply(all, settings, feeds).items
        assertTrue(result.any { it.title == "国际新闻A" })
        assertTrue(!result.any { it.title == "科技新闻B" })
        assertTrue(result.any { it.title == "热搜C" })
    }

    @Test
    fun `关键词是重点不是过滤器`() {
        // 命中的是重点，没命中的也要留在池子里一起推
        val settings = AppSettings(pushCategories = listOf("国际", "科技"), pushKeywords = listOf("APEC"))
        val all = listOf(
            article("APEC会议开始", "中新网·国际"),
            article("无关新闻", "IT之家")
        )
        val result = PushFilter.apply(all, settings, feeds)
        assertEquals("两条都要保留", 2, result.items.size)
        assertEquals("APEC会议开始", result.priority.first().title)
        assertEquals("APEC", result.hitKeywordOf["APEC会议开始"])
    }

    @Test
    fun `关键词一条都没命中时内容照样保留`() {
        val settings = AppSettings(pushCategories = listOf("国际"), pushKeywords = listOf("不存在的词"))
        val all = listOf(article("国际新闻A", "中新网·国际"))
        val result = PushFilter.apply(all, settings, feeds)
        assertEquals(1, result.items.size)
        assertTrue("没有命中就不该有重点", result.priority.isEmpty())
    }

    @Test
    fun `重点条目即使分类没选中也会保留`() {
        // 用户说「有就一定要推」：命中的条目不受分类限制
        val settings = AppSettings(pushCategories = listOf("国际"), pushKeywords = listOf("米哈游"))
        val all = listOf(
            article("国际新闻A", "中新网·国际"),
            article("米哈游发布新游戏", "游研社")
        )
        val result = PushFilter.apply(all, settings, feeds)
        assertEquals(2, result.items.size)
        assertEquals("米哈游发布新游戏", result.priority.first().title)
    }

    @Test
    fun `不包含热搜时热搜被过滤掉`() {
        val settings = AppSettings(includeHot = false)
        val all = listOf(
            article("国际新闻A", "中新网·国际"),
            article("热搜C", "微博热搜", hot = true)
        )
        val result = PushFilter.apply(all, settings, feeds).items
        assertEquals(1, result.size)
        assertEquals("国际新闻A", result.first().title)
    }
}
