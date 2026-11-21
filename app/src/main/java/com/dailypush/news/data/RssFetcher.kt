package com.dailypush.news.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 轻量 RSS / Atom 解析器。
 * 只用系统自带的 DOM 解析，不引入额外依赖，兼容 RSS 2.0 / RSS 1.0(RDF) / Atom。
 */
object RssFetcher {

    suspend fun fetch(feed: Feed): List<Article> {
        var text = Http.getText(feed.url).stripBom()
        if (!looksLikeXml(text)) {
            // 有些站点（少数派等）对桌面 UA 返回 HTML，换移动端 UA 重试
            text = Http.getText(feed.url, mobile = true).stripBom()
        }
        return parse(feed, text)
    }

    /** 去掉 UTF-8 BOM 和前导空白——东方财富的 RSS 带 BOM，不去掉会解析失败 */
    private fun String.stripBom(): String = removePrefix("\uFEFF").trimStart()

    private fun looksLikeXml(text: String): Boolean {
        val head = text.take(200)
        return head.startsWith("<?xml") || head.startsWith("<rss") ||
            head.startsWith("<feed") || head.startsWith("<rdf:RDF")
    }

    private fun parse(feed: Feed, rawText: String): List<Article> {
        val text = rawText.stripBom()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            isExpandEntityReferences = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder()
            .parse(InputSource(java.io.StringReader(text)))
        val root = document.documentElement ?: return emptyList()

        val channel = root.childElements().firstOrNull { it.localName() == "channel" } ?: root
        val feedTitle = channel.childText("title")?.takeIf { it.isNotBlank() } ?: feed.name

        val items = channel.childElements().filter { it.localName() == "item" }
            .ifEmpty { root.childElements().filter { it.localName() == "item" } }
            .ifEmpty { root.childElements().filter { it.localName() == "entry" } }
            .ifEmpty { root.descendants().filter { it.localName() == "entry" } }

        val now = System.currentTimeMillis()
        return items.mapNotNull { item ->
            val title = item.childText("title")?.let { clean(it) }.orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val link = item.linkUrl()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val summary = item.childText("description")
                ?: item.childText("summary")
                ?: item.childText("content")
                ?: ""
            Article(
                id = UUID.nameUUIDFromBytes(link.toByteArray()).toString(),
                title = title,
                link = link,
                summary = clean(summary).take(220),
                source = feedTitle,
                publishedAt = item.publishedAt(),
                fetchedAt = now,
                category = feed.category
            )
        }
    }

    // ---------- DOM 辅助 ----------

    private fun Element.localName(): String =
        (tagName ?: "").substringAfterLast(':').lowercase(Locale.US)

    private fun Element.childElements(): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node is Element) result.add(node)
        }
        return result
    }

    private fun Element.descendants(): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node is Element) {
                result.add(node)
                result.addAll(node.descendants())
            }
        }
        return result
    }

    private fun Element.childText(name: String): String? =
        childElements().firstOrNull { it.localName() == name.lowercase(Locale.US) }
            ?.textContent?.trim()

    /** RSS 的 <link>文本</link> 与 Atom 的 <link href="..."/> 都要支持 */
    private fun Element.linkUrl(): String? {
        val links = childElements().filter { it.localName() == "link" }
        links.forEach { link ->
            val text = link.textContent?.trim().orEmpty()
            if (text.startsWith("http")) return text
            val href = link.getAttribute("href")?.trim().orEmpty()
            if (href.startsWith("http")) return href
            val guid = link.getAttribute("guid")?.trim().orEmpty()
            if (guid.startsWith("http")) return guid
        }
        // 有些源把链接放在 guid 里
        val guid = childText("guid")?.trim().orEmpty()
        if (guid.startsWith("http")) return guid
        return null
    }

    private fun Element.publishedAt(): Long {
        val raw = childText("pubDate")
            ?: childText("published")
            ?: childText("updated")
            ?: childText("date")
            ?: childText("created")
            ?: return 0L
        return parseDate(raw)
    }

    private fun parseDate(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0L
        runCatching {
            return ZonedDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        }
        runCatching {
            return ZonedDateTime.parse(text, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant().toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm zzz",
            "dd MMM yyyy HH:mm:ss zzz",
            "dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("GMT+8")
                return format.parse(text)?.time ?: 0L
            }
        }
        return 0L
    }

    private fun clean(raw: String): String {
        val noTag = raw.replace(Regex("<[^>]*>"), " ")
        return noTag.replace(Regex("\\s+"), " ").trim()
    }
}
