package com.dailypush.news

import com.dailypush.news.data.AiClient
import com.dailypush.news.data.AppSettings
import com.dailypush.news.data.Article
import com.dailypush.news.data.DefaultFeeds
import com.dailypush.news.data.PushSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 多个推送时间点、条数上限 40、游戏分类 */
class MultiPushTest {

    // ---------- 时间点解析 ----------

    @Test
    fun `AI 返回多个时间点能解析`() {
        val reply = """{"pushTimes":["08:00","12:00","20:00"],"maxItems":10,"categories":["国际"],"keywords":[],"includeHot":false,"notifyStyle":"summary","note":"早中晚"}"""
        val plan = AiClient.extractJson(reply)
        assertNotNull(plan)
        assertEquals(listOf("08:00", "12:00", "20:00"), plan!!.pushTimes)
    }

    @Test
    fun `AI 只返回单个时间也能解析`() {
        val plan = AiClient.extractJson("""{"pushTime":"09:30","maxItems":8}""")
        assertNotNull(plan)
        assertEquals(listOf("09:30"), plan!!.pushTimes)
        assertEquals("09:30", plan.pushTime)
    }

    @Test
    fun `时间点会排序去重并补零`() {
        val plan = com.dailypush.news.data.AiPlan(pushTimes = listOf("9:5", "09:05", "08:00", "乱写"))
        assertEquals(listOf("08:00", "09:05"), plan.normalizedTimes())
    }

    @Test
    fun `时间点最多保留六个`() {
        val many = (0..9).map { "%02d:00".format(it) }
        val plan = com.dailypush.news.data.AiPlan(pushTimes = many)
        assertEquals(6, plan.normalizedTimes().size)
    }


    // ---------- 条数上限 40 ----------

    @Test
    fun `最多条数上限是四十`() {
        val articles = (1..60).map {
            Article(id = "n$it", title = "新闻$it", link = "https://x/$it", source = "中新网")
        }
        val selected = PushSelector.select(articles, maxItems = 40)
        assertEquals(40, selected.count { !it.isHot })
        // 超过上限的会被夹到 40
        assertEquals(40, PushSelector.select(articles, maxItems = 99).count { !it.isHot })
    }

    @Test
    fun `AI 给的条数会被夹在 3 到 40`() {
        assertEquals(40, AiClient.extractJson("""{"maxItems":100}""")!!.maxItems)
        assertEquals(3, AiClient.extractJson("""{"maxItems":1}""")!!.maxItems)
        assertEquals(40, AiClient.extractJson("""{"maxItems":"40"}""")!!.maxItems)
    }

    // ---------- 游戏分类 ----------

    @Test
    fun `内置源里有游戏分类`() {
        val games = DefaultFeeds.all.filter { it.category == "游戏" }
        assertTrue("应该有游戏源，实际 ${games.size}", games.isNotEmpty())
        assertTrue(games.all { it.kind == com.dailypush.news.data.Feed.KIND_RSS })
    }

    @Test
    fun `AI 的分类列表包含游戏`() {
        assertTrue("AI 可选分类里要有游戏", AiClient.categories.contains("游戏"))
    }

    @Test
    fun `默认设置只有一个时间点`() {
        assertEquals(listOf("08:00"), AppSettings().pushTimes)
        assertEquals(8, AppSettings().pushHour)
    }
}
