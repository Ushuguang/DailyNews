package com.dailypush.news

import com.dailypush.news.data.AppSettings
import com.dailypush.news.data.Article
import com.dailypush.news.data.Feed
import com.dailypush.news.data.MergeGroup
import com.dailypush.news.data.MergeOutcome
import com.dailypush.news.data.PushFilter
import com.dailypush.news.data.PushSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「关键词 = 重点关注」这套新语义：
 *  - 命中的内容优先推送、而且保证有名额就一定进；
 *  - 没命中的内容照常推，不筛掉；
 *  - 每个选中的分类都要有份额，不能被单一分类吃光。
 */
class PushPriorityTest {

    private val feeds = listOf(
        Feed("f1", "中新网·要闻", "http://a", "要闻"),
        Feed("f2", "中新网·国际", "http://b", "国际"),
        Feed("f3", "游研社", "http://c", "游戏"),
        Feed("f4", "IT之家", "http://d", "科技")
    )

    private fun news(
        title: String,
        category: String,
        id: String = title,
        source: String = "来源"
    ) = Article(
        id = id,
        title = title,
        link = "http://x/$id",
        summary = "",
        source = source,
        category = category
    )

    // ---------- 1. 重点一定被推，且排在最前 ----------

    @Test
    fun keywordHitIsAlwaysPushedFirst() {
        val settings = AppSettings(
            feeds = feeds,
            pushCategories = listOf("要闻", "国际"),
            pushKeywords = listOf("米哈游"),
            includeHot = true
        )
        val all = listOf(
            news("要闻一", "要闻"),
            news("米哈游发布新游戏", "游戏"),
            news("国际一", "国际"),
            news("要闻二", "要闻"),
            news("国际二", "国际")
        )
        val filtered = PushFilter.apply(all, settings, feeds)
        // 重点条目即使分类没选中也在池子里
        assertTrue(filtered.items.any { it.title == "米哈游发布新游戏" })
        assertEquals(1, filtered.priority.size)

        val pushed = PushSelector.select(filtered.items, maxItems = 3, priorityIds = filtered.priorityIds)
        assertTrue("命中的重点必须在推送名单里", pushed.any { it.title == "米哈游发布新游戏" })
        assertEquals("重点排在最前", "米哈游发布新游戏", pushed.first { !it.isHot }.title)
    }

    // ---------- 2. 没命中也不影响其它内容 ----------

    @Test
    fun noKeywordHitKeepsEverything() {
        val settings = AppSettings(
            feeds = feeds,
            pushCategories = listOf("要闻"),
            pushKeywords = listOf("不存在的词"),
            includeHot = true
        )
        val all = listOf(news("要闻一", "要闻"), news("要闻二", "要闻"))
        val filtered = PushFilter.apply(all, settings, feeds)
        assertEquals(2, filtered.items.size)
        assertTrue(filtered.priority.isEmpty())

        val pushed = PushSelector.select(filtered.items, maxItems = 10, priorityIds = filtered.priorityIds)
        assertEquals("一条都没命中也要照常推", 2, pushed.count { !it.isHot })
    }

    // ---------- 3. 每个分类都有份额 ----------

    @Test
    fun everyCategoryGetsAShare() {
        val settings = AppSettings(feeds = feeds, pushCategories = listOf("要闻", "国际", "游戏"))
        val all = buildList {
            repeat(30) { add(news("要闻$it", "要闻")) }
            repeat(2) { add(news("国际$it", "国际")) }
            repeat(3) { add(news("游戏$it", "游戏")) }
        }
        val filtered = PushFilter.apply(all, settings, feeds)
        val pushed = PushSelector.select(filtered.items, maxItems = 12, priorityIds = filtered.priorityIds)
        val news = pushed.filter { !it.isHot }
        assertEquals(12, news.size)
        assertTrue("国际必须有份额", news.any { it.title.startsWith("国际") })
        assertTrue("游戏必须有份额", news.any { it.title.startsWith("游戏") })
        assertTrue("要闻必须有份额", news.any { it.title.startsWith("要闻") })
    }

    // ---------- 4. 分类按抓取时写进数据的字段匹配（源名对不上也能匹配） ----------

    @Test
    fun categoryComesFromArticleFieldNotSourceName() {
        // source 是 RSS 频道名，和订阅源名字对不上；靠 article.category 才能匹配上
        val settings = AppSettings(feeds = feeds, pushCategories = listOf("国际"))
        val all = listOf(
            news("国际新闻", "国际", source = "中新网时政新闻"),
            news("科技新闻", "科技", source = "中新网时政新闻")
        )
        val filtered = PushFilter.apply(all, settings, feeds)
        assertEquals(1, filtered.items.size)
        assertEquals("国际新闻", filtered.items.first().title)
    }

    // ---------- 5. 合并时重点当代表，重点不会被合并掉 ----------

    @Test
    fun priorityWinsAsRepresentativeWhenMerged() {
        val candidates = listOf(
            news("普通报道", "要闻", id = "a"),
            news("米哈游新品发布", "游戏", id = "b")
        )
        val merge = MergeOutcome(
            groups = listOf(
                MergeGroup(
                    topic = "同一条",
                    representativeIndex = 0,
                    memberIndexes = listOf(0, 1),
                    reason = "两家同报"
                )
            ),
            note = "合并了 1 组"
        )
        val merged = PushSelector.applyMerge(candidates, merge, setOf("b"))
        assertEquals("重点条目要当代表，不能被合并掉", 1, merged.size)
        assertEquals("b", merged.first().id)
    }

    // ---------- 6. 热搜有独立配额，且命中的热搜排前面 ----------

    @Test
    fun hotSearchHasItsOwnQuota() {
        val hot = (1..5).map {
            Article(
                id = "h$it",
                title = if (it == 1) "米哈游热搜" else "热搜$it",
                link = "http://h/$it",
                source = "微博",
                kind = Article.KIND_HOT
            )
        }
        val pushed = PushSelector.select(hot, maxItems = 3, priorityIds = setOf("h1"))
        assertEquals("微博最多 3 条", 3, pushed.count { it.isHot })
        assertEquals("命中的热搜排最前", "h1", pushed.first { it.isHot }.id)
    }
}
