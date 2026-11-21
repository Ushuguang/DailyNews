package com.dailypush.news

import com.dailypush.news.data.AiClient
import com.dailypush.news.data.AppSettings
import com.dailypush.news.data.Article
import com.dailypush.news.data.Feed
import com.dailypush.news.data.MergeGroup
import com.dailypush.news.data.MergeOutcome
import com.dailypush.news.data.PushSelector
import com.dailypush.news.data.PushTraceStore
import com.dailypush.news.data.TraceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString

/** AI 合并 + 决策痕迹 的单元测试 */
private val testJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

class MergeAndTraceTest {

    private fun article(id: String, title: String, source: String = "新华社", hot: Boolean = false) =
        Article(
            id = id,
            title = title,
            link = "https://example.com/$id",
            source = source,
            publishedAt = 1_700_000_000_000L,
            kind = if (hot) Article.KIND_HOT else Article.KIND_NEWS
        )

    // ---------- 解析 AI 返回的合并结果 ----------

    @Test
    fun `解析裸 JSON`() {
        val json = """{"groups":[{"topic":"APEC峰会","representative":2,"members":[1,2],"reason":"同一次会议"}],"note":"合并了1组"}"""
        val dto = AiClient.extractMerge(json)
        assertNotNull(dto)
        assertEquals(1, dto!!.groups.size)
        assertEquals("APEC峰会", dto.groups[0].topic)
        assertEquals(2, dto.groups[0].representative)
        assertEquals(listOf(1, 2), dto.groups[0].members)
        assertEquals("合并了1组", dto.note)
    }

    @Test
    fun `解析带代码块的回复`() {
        val reply = """
            好的，这是分析结果：
            ```json
            {"groups":[{"topic":"A","representative":1,"members":[1,3],"reason":"重复"}],"note":"ok"}
            ```
            以上。
        """.trimIndent()
        val dto = AiClient.extractMerge(reply)
        assertNotNull(dto)
        assertEquals(1, dto!!.groups.size)
        assertEquals("A", dto.groups[0].topic)
    }

    @Test
    fun `解析失败时返回 null`() {
        assertEquals(null, AiClient.extractMerge("我不知道你在说什么"))
    }

    @Test
    fun `没有代表条目的组会被丢掉`() {
        // 宽松解析下，缺 representative 的组没法用（不知道该保留哪条），直接丢掉更安全，
        // 丢掉的组会退回「两条都保留」，不会误合并
        val dto = AiClient.extractMerge("""{"groups":[{"topic":"只有主题"}]}""")
        assertNotNull(dto)
        assertTrue("缺代表条目的组应被丢弃", dto!!.groups.isEmpty())
    }

    // ---------- 合并后如何选稿 ----------

    @Test
    fun `每组只留代表 没进组的单独保留`() {
        val candidates = listOf(
            article("a1", "同一条 A"),
            article("a2", "同一条 A 另一家"),
            article("b1", "不相干的 B")
        )
        val merge = MergeOutcome(
            groups = listOf(MergeGroup("同一条 A", representativeIndex = 0, memberIndexes = listOf(0, 1), reason = "两家同报")),
            note = "合并 1 组"
        )
        val result = PushSelector.applyMerge(candidates, merge)
        assertEquals(listOf("a1", "b1"), result.map { it.id })
    }

    @Test
    fun `合并结果为空时保留全部`() {
        val candidates = listOf(article("a1", "1"), article("a2", "2"))
        val result = PushSelector.applyMerge(candidates, MergeOutcome(emptyList(), "无重复"))
        assertEquals(2, result.size)
    }

    // ---------- 推送条数配额 ----------

    @Test
    fun `新闻按条数上限 热搜按平台配额`() {
        val articles = (1..8).map { article("n$it", "新闻$it") } +
            listOf(
                article("h1", "热搜1", "微博", hot = true),
                article("h2", "热搜2", "微博", hot = true),
                article("h3", "热搜3", "微博", hot = true),
                article("h4", "热搜4", "微博", hot = true),
                article("h5", "热搜5", "百度", hot = true)
            )
        val selected = PushSelector.select(articles, maxItems = 3)
        assertEquals(3, selected.count { !it.isHot })
        // 微博最多 3 条，百度 1 条
        assertEquals(3, selected.count { it.source == "微博" })
        assertEquals(1, selected.count { it.source == "百度" })
    }

    // ---------- 痕迹 ----------

    private val settings = AppSettings(
        feeds = listOf(
            Feed("f1", "新华社·要闻", "https://news.cn", "时政"),
            Feed("f2", "IT之家", "https://ithome.com", "科技")
        ),
        pushCategories = listOf("时政"),
        pushKeywords = emptyList(),
        includeHot = true
    )

    @Test
    fun `痕迹记录了选中与未选中的理由`() {
        val all = listOf(
            article("a1", "同一条 A", "新华社"),
            article("a2", "同一条 A 另一家", "人民网"),
            article("b1", "不相干的 B", "新华社"),
            article("t1", "科技新闻", "IT之家")
        )
        val candidates = listOf(all[0], all[1], all[2])
        val merge = MergeOutcome(
            groups = listOf(MergeGroup("同一条 A", representativeIndex = 0, memberIndexes = listOf(0, 1), reason = "两家同报")),
            note = "合并了 1 组"
        )
        val pushed = PushSelector.select(PushSelector.applyMerge(candidates, merge), maxItems = 10)

        val trace = TraceBuilder.build(
            date = "2026-11-18",
            pushedAt = 1_700_000_000_000L,
            all = all,
            filtered = candidates,
            candidates = candidates,
            pushed = pushed,
            merge = merge,
            aiError = "",
            settings = settings
        )

        assertEquals(2, trace.selectedIds.size)
        assertTrue(trace.aiUsed)
        assertEquals("合并了 1 组", trace.note)

        val kept = trace.candidates.first { it.id == "a1" }
        assertTrue(kept.selected)
        assertTrue(kept.reason.contains("被选为这组重复报道的代表"))
        assertTrue(kept.reason.contains("两家同报"))
        assertEquals(listOf("a2"), kept.mergedFrom)

        val merged = trace.candidates.first { it.id == "a2" }
        assertFalse(merged.selected)
        assertTrue(merged.reason.contains("已合并到"))
        assertEquals("a1", merged.mergedInto)

        val alone = trace.candidates.first { it.id == "b1" }
        assertTrue(alone.selected)
        assertTrue(alone.reason.contains("独立内容"))

        // 分类不符的进 filteredOut
        assertEquals(1, trace.filteredOut.size)
        assertEquals("t1", trace.filteredOut[0].id)
        assertTrue(trace.filteredOut[0].reason.contains("分类"))
    }

    @Test
    fun `没有 AI 时痕迹标记为未使用 AI`() {
        val candidates = listOf(article("a1", "1"))
        val trace = TraceBuilder.build(
            date = "2026-11-18",
            pushedAt = 0L,
            all = candidates,
            filtered = candidates,
            candidates = candidates,
            pushed = candidates,
            merge = null,
            aiError = "未填写 API Key，本次未做合并",
            settings = settings
        )
        assertFalse(trace.aiUsed)
        assertEquals("未填写 API Key，本次未做合并", trace.aiError)
        assertEquals(1, trace.selectedIds.size)
    }

    @Test
    fun `痕迹文件读写`() {
        // 存储路径需要真实 Context，这里只验证序列化结构可往返
        val trace = com.dailypush.news.data.PushTrace(
            date = "2026-11-18",
            pushedAt = 1L,
            selectedIds = listOf("x")
        )
        val encoded = testJson.encodeToString(trace)
        val decoded = testJson.decodeFromString<com.dailypush.news.data.PushTrace>(encoded)
        assertEquals(trace.date, decoded.date)
        assertEquals(trace.selectedIds, decoded.selectedIds)
    }
}
