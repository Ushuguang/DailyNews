package com.dailypush.news.data

import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 网页型订阅源：直接从网页 HTML 里抽取「标题 + 链接」。
 *
 * 用于那些 RSS 已停更、但官网首页/电子版仍然是服务端渲染的媒体。
 * 当前内置：新华网首页要闻、人民网首页要闻、人民日报电子版要闻。
 */
object WebPageFetcher {

    private data class Spec(
        val linkPattern: Regex,
        val referer: String? = null,
        val maxItems: Int = 40
    )

    private val specs = mapOf(
        "web_xinhua_home" to Spec(
            linkPattern = Regex("""/20\d{6}/"""),
            referer = "https://www.news.cn/"
        ),
        "web_people_home" to Spec(
            linkPattern = Regex("""people\.com\.cn/n1/\d{4}/\d{4}/"""),
            referer = "http://www.people.com.cn/"
        ),
        "web_rmrb_epaper" to Spec(
            linkPattern = Regex("""content/\d{6}/\d{2}/content_\d+\.html""")
        )
    )

    /** 自定义网页源：抓所有看起来像正文的链接 */
    private val genericSpec = Spec(
        linkPattern = Regex("""\.(html?|shtml)($|\?)"""),
        maxItems = 30
    )

    suspend fun fetch(feed: Feed): List<Article> {
        val spec = specs[feed.id] ?: genericSpec
        // 日期型地址（报纸电子版）今天还没上线时，退回昨天
        val offsets = if (feed.url.contains("{yyyyMM}")) listOf(0L, 1L) else listOf(0L)
        var lastError: Throwable? = null

        for (offset in offsets) {
            val url = resolveTemplate(feed.url, offset)
            try {
                val html = Http.getText(url, spec.referer)
                val articles = parse(feed, html, spec, url)
                if (articles.isNotEmpty()) return articles
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("没有抓到内容")
    }

    private fun parse(feed: Feed, html: String, spec: Spec, baseUrl: String): List<Article> {
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val result = mutableListOf<Article>()

        for ((href, text) in extractAnchors(html)) {
            if (result.size >= spec.maxItems) break
            if (text.length < 6 || text.length > 80) continue
            if (!spec.linkPattern.containsMatchIn(href)) continue

            val absolute = absolutize(href, baseUrl) ?: continue
            if (!absolute.startsWith("http")) continue
            if (!seen.add(absolute)) continue

            val published = parseDateFromUrl(absolute) ?: now
            result.add(
                Article(
                    id = UUID.nameUUIDFromBytes(absolute.toByteArray()).toString(),
                    title = text,
                    link = absolute,
                    summary = "",
                    source = feed.name,
                    publishedAt = published,
                    fetchedAt = now,
                    category = feed.category
                )
            )
        }
        return result.sortedByDescending { it.publishedAt }
    }

    /** 抽取页面里所有 <a href>文本</a> */
    private fun extractAnchors(html: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val anchorRegex = Regex(
            """<a\b([^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val hrefRegex = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (match in anchorRegex.findAll(html)) {
            val href = hrefRegex.find(match.groupValues[1])?.groupValues?.get(1) ?: continue
            val text = match.groupValues[2]
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            result.add(href to text)
        }
        return result
    }

    /** 绝对地址原样返回，协议相对地址补全，相对地址按基准地址解析 */
    private fun absolutize(href: String, baseUrl: String): String? = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("#") || href.startsWith("javascript:") -> null
        else -> runCatching {
            java.net.URI(baseUrl).resolve(href).toString()
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    /** 把 URL 里的 {yyyyMM} {dd} {yyyyMMdd} {yyyy} 换成对应日期 */
    private fun resolveTemplate(url: String, dayOffset: Long = 0L): String {
        val today = LocalDate.now().minusDays(dayOffset)
        return url
            .replace("{yyyyMMdd}", today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
            .replace("{yyyyMM}", today.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")))
            .replace("{yyyy}", today.format(java.time.format.DateTimeFormatter.ofPattern("yyyy")))
            .replace("{dd}", today.format(java.time.format.DateTimeFormatter.ofPattern("dd")))
    }

    /** 从 URL 路径里推断发布日期，识别三种常见写法 */
    private fun parseDateFromUrl(url: String): Long? {
        // /20261116/
        Regex("""/(\d{4})(\d{2})(\d{2})/""").find(url)?.let {
            return toEpoch(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }
        // /202611/18/
        Regex("""/(\d{4})(\d{2})/(\d{2})/""").find(url)?.let {
            return toEpoch(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }
        // /2026/1118/
        Regex("""/(\d{4})/(\d{2})(\d{2})/""").find(url)?.let {
            return toEpoch(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }
        return null
    }

    private fun toEpoch(year: String, month: String, day: String): Long? = runCatching {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}
