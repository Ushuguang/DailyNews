package com.dailypush.news

import com.dailypush.news.data.Feed
import com.dailypush.news.data.RssFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实抓取 + 解析的联调测试（需要网络）。
 * 目的：确认 RSS 解析器对真实源能稳定取出标题、链接、发布时间。
 */
class RssFetcherTest {

    private fun fetch(name: String, url: String) = runBlocking {
        val feed = Feed("test_$name", name, url)
        val articles = RssFetcher.fetch(feed)
        println(">>> $name 抓到 ${articles.size} 条")
        articles.take(2).forEach {
            println("    title=${it.title}")
            println("    link=${it.link}")
            println("    source=${it.source}  time=${it.publishedAt}")
        }
        articles
    }

    @Test
    fun `人民网时政可以解析`() {
        val articles = fetch("人民网·时政", "http://www.people.com.cn/rss/politics.xml")
        assertTrue("应至少解析出 10 条，实际 ${articles.size}", articles.size >= 10)
        assertTrue("标题不应为空", articles.all { it.title.isNotBlank() })
        assertTrue("链接都应是 http(s)", articles.all { it.link.startsWith("http") })
        assertTrue("大部分条目应解析出发布时间", articles.count { it.publishedAt > 0 } > articles.size / 2)
    }

    @Test
    fun `IT之家可以解析`() {
        val articles = fetch("IT之家", "https://www.ithome.com/rss/")
        assertTrue("应至少解析出 5 条，实际 ${articles.size}", articles.size >= 5)
        assertTrue(articles.all { it.title.isNotBlank() && it.link.startsWith("http") })
        assertTrue("大部分条目应解析出发布时间", articles.count { it.publishedAt > 0 } > articles.size / 2)
    }

    @Test
    fun `少数派可以解析`() {
        val articles = fetch("少数派", "https://sspai.com/feed")
        assertTrue("应至少解析出 3 条，实际 ${articles.size}", articles.size >= 3)
    }

    @Test
    fun `界面新闻可以解析`() {
        val articles = fetch("界面新闻", "https://a.jiemian.com/index.php?m=article&a=rss")
        assertTrue("应至少解析出 10 条，实际 ${articles.size}", articles.size >= 10)
        assertTrue(articles.all { it.title.isNotBlank() && it.link.startsWith("http") })
    }

    @Test
    fun `东方财富可以解析`() {
        val articles = fetch("东方财富", "http://rss.eastmoney.com/rss_partener.xml")
        assertTrue("应至少解析出 20 条，实际 ${articles.size}", articles.size >= 20)
    }

    @Test
    fun `中新网可以解析`() {
        val articles = fetch("中新网·滚动", "https://www.chinanews.com.cn/rss/scroll-news.xml")
        assertTrue("应至少解析出 10 条，实际 ${articles.size}", articles.size >= 10)
        assertTrue("大部分条目应解析出发布时间", articles.count { it.publishedAt > 0 } > articles.size / 2)
    }
}
