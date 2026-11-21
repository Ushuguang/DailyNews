package com.dailypush.news.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/**
 * 热榜抓取：微博 / 百度 / 抖音 / 今日头条 / 腾讯新闻 / B站 / 知乎。
 *
 * 这些不是 RSS，而是各家自己的 JSON 接口或内嵌数据的网页，
 * 所以单独做一个抓取器，解析完统一转成 Article，走同一套列表与推送流程。
 */
object HotFetcher {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(feed: Feed): List<Article> {
        val now = System.currentTimeMillis()
        val list = when (feed.id) {
            "hot_weibo" -> weibo(feed, now)
            "hot_baidu" -> baidu(feed, now)
            "hot_douyin" -> douyin(feed, now)
            "hot_toutiao" -> toutiao(feed, now)
            "hot_tencent" -> tencent(feed, now)
            "hot_bilibili" -> bilibili(feed, now)
            "hot_bilibili_video" -> bilibiliPopular(feed, now)
            "hot_zhihu" -> zhihu(feed, now)
            else -> emptyList()
        }
        // 每个平台保留前 20 条，避免热榜条目把新闻缓存挤掉
        return list.take(MAX_PER_SOURCE)
    }

    private const val MAX_PER_SOURCE = 20

    // ---------------- 各平台解析 ----------------

    private suspend fun weibo(feed: Feed, now: Long): List<Article> {
        val text = Http.getText("https://weibo.com/ajax/side/hotSearch", "https://weibo.com/")
        val list = json.parseToJsonElement(text).jsonObject
            .obj("data")?.arr("realtime") ?: return emptyList()

        return list.mapNotNull { element ->
            val obj = element.jsonObject
            val word = obj.str("word")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            article(
                feed = feed,
                title = word,
                link = "https://s.weibo.com/weibo?q=" + encode(word),
                hot = obj.num("num"),
                now = now,
                linkKind = Article.LINK_SEARCH
            )
        }
    }

    private suspend fun baidu(feed: Feed, now: Long): List<Article> {
        val html = Http.getText("https://top.baidu.com/board?tab=realtime", "https://top.baidu.com/board")
        val payload = Regex("""<!--s-data:(.*?)-->""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val cards = json.parseToJsonElement(payload).jsonObject
            .obj("data")?.arr("cards") ?: return emptyList()

        val result = mutableListOf<Article>()
        for (card in cards) {
            val content = card.jsonObject["content"]?.jsonArray ?: continue
            for (item in content) {
                val obj = item.jsonObject
                val word = obj.str("word")?.takeIf { it.isNotBlank() } ?: continue
                val link = obj.str("rawUrl")?.takeIf { it.startsWith("http") }
                    ?: ("https://www.baidu.com/s?wd=" + encode(word))
                result.add(
                    article(
                        feed = feed,
                        title = word,
                        link = link,
                        hot = obj.num("hotScore"),
                        now = now,
                        summary = obj.str("desc")?.take(80).orEmpty()
                    )
                )
            }
        }
        return result
    }

    private suspend fun douyin(feed: Feed, now: Long): List<Article> {
        val text = Http.getText(
            "https://www.douyin.com/aweme/v1/web/hot/search/list/?device_platform=webapp&aid=6383",
            "https://www.douyin.com/"
        )
        val list = json.parseToJsonElement(text).jsonObject
            .obj("data")?.arr("word_list") ?: return emptyList()

        return list.mapNotNull { element ->
            val obj = element.jsonObject
            val word = obj.str("word")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            article(
                feed = feed,
                title = word,
                link = "https://www.douyin.com/search/" + encode(word),
                hot = obj.num("hot_value"),
                now = now,
                linkKind = Article.LINK_SEARCH
            )
        }
    }

    private suspend fun toutiao(feed: Feed, now: Long): List<Article> {
        val text = Http.getText(
            "https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc",
            "https://www.toutiao.com/"
        )
        val list = json.parseToJsonElement(text).jsonObject
            .arr("data") ?: return emptyList()

        return list.mapNotNull { element ->
            val obj = element.jsonObject
            val title = obj.str("Title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = obj.str("Url")?.takeIf { it.startsWith("http") }
                ?: ("https://www.toutiao.com/search/?keyword=" + encode(title))
            article(feed = feed, title = title, link = link, hot = obj.num("HotValue"), now = now)
        }
    }

    private suspend fun tencent(feed: Feed, now: Long): List<Article> {
        val text = Http.getText(
            "https://i.news.qq.com/gw/event/hot_ranking_list?ids_hash=&offset=0&page_size=20" +
                "&appver=15.5_qqnews_7.1.50",
            "https://news.qq.com/"
        )
        val list = json.parseToJsonElement(text).jsonObject
            .arr("idlist")?.firstOrNull()?.jsonObject
            ?.arr("newslist") ?: return emptyList()

        return list.mapNotNull { element ->
            val obj = element.jsonObject
            val title = obj.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (title.contains("用户最关注的热点")) return@mapNotNull null
            val id = obj.str("id").orEmpty()
            val link = if (id.isNotBlank()) "https://news.qq.com/rain/a/$id"
            else "https://news.qq.com/search?query=" + encode(title)
            article(feed = feed, title = title, link = link, hot = obj.num("hotEvent"), now = now)
        }
    }

    private suspend fun bilibili(feed: Feed, now: Long): List<Article> {
        val text = Http.getText(
            "https://api.bilibili.com/x/web-interface/search/square?limit=30",
            "https://www.bilibili.com/"
        )
        val list = json.parseToJsonElement(text).jsonObject
            .obj("data")?.obj("trending")?.arr("list") ?: return emptyList()

        return list.mapNotNull { element ->
            val obj = element.jsonObject
            val title = (obj.str("show_name") ?: obj.str("keyword"))?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            article(
                feed = feed,
                title = title,
                link = "https://search.bilibili.com/all?keyword=" + encode(title),
                hot = obj.num("heat_score"),
                now = now,
                // B站热搜榜给的只是关键词，接口里 uri / goto 都是空的，没有原文页
                linkKind = Article.LINK_SEARCH
            )
        }
    }

    /**
     * B站「综合热门」视频榜。
     * 和热搜词榜不同，这里每条都有 bvid，能直接打开视频页。
     */
    private suspend fun bilibiliPopular(feed: Feed, now: Long): List<Article> {
        val text = Http.getText(
            "https://api.bilibili.com/x/web-interface/popular?ps=30&pn=1",
            "https://www.bilibili.com/"
        )
        val list = json.parseToJsonElement(text).jsonObject
            .obj("data")?.arr("list") ?: return emptyList()

        return list.mapNotNull { element ->
            val obj = element.jsonObject
            val title = obj.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val bvid = obj.str("bvid")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val owner = obj.obj("owner")?.str("name").orEmpty()
            val view = obj.obj("stat")?.num("view")
            article(
                feed = feed,
                title = title,
                link = "https://www.bilibili.com/video/$bvid",
                hot = view,
                now = now,
                summary = listOf(owner, formatHot(view)).filter { it.isNotBlank() }.joinToString(" · ")
            )
        }
    }

    private suspend fun zhihu(feed: Feed, now: Long): List<Article> {
        val text = Http.getText(
            "https://api.zhihu.com/topstory/hot-list?limit=50&reverse_order=0",
            "https://www.zhihu.com/"
        )
        val list = json.parseToJsonElement(text).jsonObject
            .arr("data") ?: return emptyList()

        return list.mapNotNull { element ->
            val obj = element.jsonObject
            val target = obj.obj("target") ?: return@mapNotNull null
            val title = target.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // 接口给的 url 是 api.zhihu.com/questions/{id} —— 那是 API 地址不是文章页，
            // 用浏览器打开会报「请求参数异常，请升级客户端后重试」。真实页面是 www.zhihu.com/question/{id}
            val questionId = target.str("id")?.takeIf { it.isNotBlank() }
            val rawUrl = target.str("url")?.takeIf { it.startsWith("http") }
            val link = when {
                questionId != null -> "https://www.zhihu.com/question/$questionId"
                rawUrl != null && rawUrl.contains("api.zhihu.com") ->
                    rawUrl.replace("api.zhihu.com/questions/", "www.zhihu.com/question/")
                rawUrl != null -> rawUrl
                else -> "https://www.zhihu.com/search?q=" + encode(title)
            }
            article(
                feed = feed,
                title = title,
                link = link,
                summary = obj.str("detail_text").orEmpty(),
                hot = null,
                now = now
            )
        }
    }

    // ---------------- 工具 ----------------

    private fun article(
        feed: Feed,
        title: String,
        link: String,
        hot: Long?,
        now: Long,
        summary: String = "",
        linkKind: String = Article.LINK_PAGE
    ): Article {
        val hotText = formatHot(hot)
        val desc = listOf(summary, hotText).filter { it.isNotBlank() }.joinToString(" · ")
        return Article(
            id = UUID.nameUUIDFromBytes("$feed.id|$title".toByteArray()).toString(),
            title = title,
            link = link,
            summary = desc,
            source = feed.name,
            publishedAt = now,
            fetchedAt = now,
            kind = Article.KIND_HOT,
            linkKind = linkKind,
            category = feed.category.ifBlank { "热搜" }
        )
    }

    private fun formatHot(value: Long?): String {
        if (value == null || value <= 0) return ""
        return when {
            value >= 100_000_000 -> String.format(Locale.US, "热度 %.1f亿", value / 100_000_000.0)
            value >= 10_000 -> String.format(Locale.US, "热度 %.1f万", value / 10_000.0)
            else -> "热度 $value"
        }
    }

    private fun encode(text: String): String =
        runCatching { URLEncoder.encode(text, "UTF-8") }.getOrDefault(text)

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.num(key: String): Long? {
        val element = this[key] ?: return null
        // 字段可能是对象/数组，统一容错返回 null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key]?.jsonArray
}
