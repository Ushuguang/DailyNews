package com.dailypush.news.data

import kotlinx.serialization.Serializable

/** 一个订阅源 */
@Serializable
data class Feed(
    val id: String,
    val name: String,
    val url: String,
    val category: String = "默认",
    val enabled: Boolean = true,
    /** 取源方式：rss = XML 订阅源，web = 网页链接抽取，hot = 热榜接口 */
    val kind: String = KIND_RSS
) {
    companion object {
        const val KIND_RSS = "rss"
        const val KIND_WEB = "web"
        const val KIND_HOT = "hot"

        fun kindLabel(kind: String): String = when (kind) {
            KIND_WEB -> "网页"
            KIND_HOT -> "热榜"
            else -> "RSS"
        }
    }
}

/** 一条新闻 */
@Serializable
data class Article(
    val id: String,
    val title: String,
    val link: String,
    val summary: String = "",
    val source: String = "",
    val publishedAt: Long = 0L,
    val fetchedAt: Long = 0L,
    /** news = 普通新闻，hot = 热搜条目 */
    val kind: String = KIND_NEWS,
    /**
     * 链接类型：
     *  - page   ：具体内容页，点开就是原文
     *  - search ：平台只给了关键词，没有原文，点开是站内搜索页
     */
    val linkKind: String = LINK_PAGE,
    /**
     * 所属分类（时政 / 财经 / 科技 / 国际 / 生活 / 游戏 …）。
     * 抓取时按订阅源带的分类填好，「要闻」页据此分组。
     */
    val category: String = ""
) {
    val isHot: Boolean get() = kind == KIND_HOT
    /** 点开只会进站内搜索（微博/抖音/B站热搜这类只有关键词的榜） */
    val isSearchLink: Boolean get() = linkKind == LINK_SEARCH

    companion object {
        const val KIND_NEWS = "news"
        const val KIND_HOT = "hot"
        const val LINK_PAGE = "page"
        const val LINK_SEARCH = "search"
    }
}
