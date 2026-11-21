package com.dailypush.news

import com.dailypush.news.data.Feed
import com.dailypush.news.data.WebPageFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 网页型源（新华网首页 / 人民网首页 / 人民日报电子版）联调测试。
 * 重点：抽出来的应该是「今天的」标题与链接。
 */
class WebPageFetcherTest {

    private fun fetch(id: String, name: String, url: String) = runBlocking {
        val articles = WebPageFetcher.fetch(Feed(id, name, url, "时政", true, Feed.KIND_WEB))
        println(">>> $name 抓到 ${articles.size} 条")
        articles.take(3).forEach {
            println("    ${it.title}  |  ${it.link.take(70)}")
        }
        articles
    }

    @Test
    fun `新华网首页要闻`() {
        val articles = fetch("web_xinhua_home", "新华社·要闻", "https://www.news.cn/")
        assertTrue("应至少 10 条，实际 ${articles.size}", articles.size >= 10)
        assertTrue(articles.all { it.title.length >= 6 && it.link.startsWith("http") })
    }

    @Test
    fun `人民网首页要闻`() {
        val articles = fetch("web_people_home", "人民网·要闻", "http://www.people.com.cn/")
        assertTrue("应至少 10 条，实际 ${articles.size}", articles.size >= 10)
        assertTrue(articles.all { it.link.contains("people.com.cn") })
    }

    @Test
    fun `人民日报电子版要闻`() {
        val articles = fetch(
            "web_rmrb_epaper",
            "人民日报·电子版",
            "http://paper.people.com.cn/rmrb/pc/layout/{yyyyMM}/{dd}/node_01.html"
        )
        // 头版头条数量会随出版进度变化（早版只有几条，版面上线后变多），
        // 所以只断言「抓到了东西」，具体条数不设死
        assertTrue("至少要抓到 3 条，实际 ${articles.size}", articles.size >= 3)
        assertTrue(articles.all { it.link.contains("content") })
    }

    @Test
    fun `网页源抽出的条目应当是今天的`() {
        val articles = fetch("web_xinhua_home", "新华社·要闻", "https://www.news.cn/")
        val today = LocalDate.now(ZoneId.systemDefault())
        val fresh = articles.filter {
            val date = java.time.Instant.ofEpochMilli(it.publishedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            date >= today.minusDays(1)
        }
        println(">>> 其中 ${fresh.size}/${articles.size} 条是今天或昨天的")
        assertTrue("应至少有 5 条是当天的，实际 ${fresh.size}", fresh.size >= 5)
    }
}
