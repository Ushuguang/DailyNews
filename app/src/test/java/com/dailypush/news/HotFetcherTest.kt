package com.dailypush.news

import com.dailypush.news.data.Feed
import com.dailypush.news.data.HotFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** 各平台热榜接口联调测试（真实联网） */
class HotFetcherTest {

    private fun fetch(id: String, name: String): List<com.dailypush.news.data.Article> = runBlocking {
        val articles = HotFetcher.fetch(Feed(id, name, "", "热搜", true, Feed.KIND_HOT))
        println(">>> $name 抓到 ${articles.size} 条")
        articles.take(3).forEach {
            println("    ${it.title}  |  ${it.summary}  |  ${it.link.take(60)}")
        }
        articles
    }

    @Test
    fun `微博热搜`() {
        val articles = fetch("hot_weibo", "微博热搜")
        assertTrue("应至少 10 条，实际 ${articles.size}", articles.size >= 10)
        assertTrue(articles.all { it.title.isNotBlank() && it.link.startsWith("http") })
        assertTrue("应标记为热搜", articles.all { it.isHot })
    }

    @Test
    fun `百度热搜`() {
        val articles = fetch("hot_baidu", "百度热搜")
        assertTrue("应至少 10 条，实际 ${articles.size}", articles.size >= 10)
    }

    @Test
    fun `抖音热搜`() {
        val articles = fetch("hot_douyin", "抖音热搜")
        assertTrue("应至少 10 条，实际 ${articles.size}", articles.size >= 10)
    }

    @Test
    fun `知乎热榜`() {
        val articles = fetch("hot_zhihu", "知乎热榜")
        assertTrue("应至少 10 条，实际 ${articles.size}", articles.size >= 10)
        // 接口返回的是 api.zhihu.com/questions/{id}，那是 API 地址不是文章页，
        // 用浏览器打开会报「请求参数异常，请升级客户端后重试」，必须换成真实问题页
        assertTrue(
            "知乎链接应指向 www.zhihu.com/question/，实际：${articles.first().link}",
            articles.all { it.link.startsWith("https://www.zhihu.com/question/") }
        )
        assertTrue("知乎是真实内容页，不该标成搜索", articles.all { !it.isSearchLink })
    }

    @Test
    fun `B站热门视频能打开到具体视频页`() {
        val articles = fetch("hot_bilibili_video", "B站热门视频")
        assertTrue("应至少 10 条，实际 ${articles.size}", articles.size >= 10)
        assertTrue(
            "B站视频链接应指向 /video/，实际：${articles.first().link}",
            articles.all { it.link.startsWith("https://www.bilibili.com/video/") }
        )
        assertTrue("热门视频是真实内容页，不该标成搜索", articles.all { !it.isSearchLink })
    }

    @Test
    fun `关键词型热搜要标成搜索`() {
        val weibo = fetch("hot_weibo", "微博热搜")
        val bilibili = fetch("hot_bilibili", "B站热搜")
        assertTrue("微博热搜只有关键词，应标成搜索", weibo.all { it.isSearchLink })
        assertTrue("B站热搜只有关键词，应标成搜索", bilibili.all { it.isSearchLink })
    }

    @Test
    fun `B站热搜`() {
        val articles = fetch("hot_bilibili", "B站热搜")
        assertTrue("应至少 10 条，实际 ${articles.size}", articles.size >= 10)
    }

    @Test
    fun `今日头条热榜`() {
        val articles = fetch("hot_toutiao", "今日头条热榜")
        assertTrue("应至少 10 条，实际 ${articles.size}", articles.size >= 10)
    }

    @Test
    fun `腾讯新闻热榜`() {
        val articles = fetch("hot_tencent", "腾讯新闻热榜")
        assertTrue("应至少 5 条，实际 ${articles.size}", articles.size >= 5)
    }
}
