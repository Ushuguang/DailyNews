package com.dailypush.news.data

/**
 * 推送时的筛选器。
 *
 * 三种条件的语义完全不同，别混：
 *
 * - **分类**：「我还想要哪些类别」。只保留选中分类的条目（空 = 不限）。
 *   选中的每一个分类都会在推送里出现（配额由 [PushSelector] 保证），
 *   不会因为某一个分类条目多就把其它分类挤掉。
 * - **热搜**：[AppSettings.includeHot] 决定推不推热搜。
 * - **关键词**：**重点关注**，不是过滤器。
 *   命中关键词的条目标成「重点」，**优先推送并保证有名额就一定进**；
 *   一条都没命中也不影响其它内容照常推送（不强求、不报错、也不推空）。
 */
object PushFilter {

    /** 筛选结果 */
    data class Result(
        /** 通过分类 / 热搜过滤后的内容池（重点条目一定在里面） */
        val items: List<Article>,
        /** 命中重点关键词的条目 —— 这些必须推 */
        val priority: List<Article>,
        /** 条目 id → 命中的那个关键词 */
        val hitKeywordOf: Map<String, String>
    ) {
        val priorityIds: Set<String> get() = hitKeywordOf.keys
    }

    fun apply(
        articles: List<Article>,
        settings: AppSettings,
        feeds: List<Feed>
    ): Result {
        val categoryOf = feeds.associate { it.name to it.category }
        val keywords = settings.pushKeywords.filter { it.isNotBlank() }

        // 1) 重点：命中关键词的条目（**不受分类限制** —— 用户说「有就一定要推」）
        val priority = articles.filter { !it.isHot && hitKeyword(it, keywords).isNotBlank() }
        val hitKeywordOf = priority.associate { it.id to hitKeyword(it, keywords) }

        // 2) 分类过滤（只对普通新闻生效，热搜由 includeHot 单独控制）
        var result = articles
        val categories = settings.pushCategories.filter { it != "热搜" }
        if (categories.isNotEmpty()) {
            result = result.filter { article ->
                if (article.isHot) true
                else categories.contains(categoryOf(article, categoryOf))
            }
        }

        // 3) 热搜开关
        if (!settings.includeHot) {
            result = result.filterNot { it.isHot }
        }

        // 4) 重点条目无条件回到池子里（哪怕它所属的分类没被选中）
        val inPool = result.map { it.id }.toSet()
        val merged = result + priority.filter { !inPool.contains(it.id) }

        return Result(merged, priority, hitKeywordOf)
    }

    /**
     * 条目所属分类。
     *
     * 优先用抓取时写进数据的 [Article.category]；
     * 老数据没有这个字段时再退回按源名反查。
     */
    fun categoryOf(article: Article, categoryOf: Map<String, String>): String =
        article.category.ifBlank { categoryOf[article.source].orEmpty() }

    /** 命中了哪个关键词（没命中返回空串） */
    fun hitKeyword(article: Article, keywords: List<String>): String {
        if (keywords.isEmpty()) return ""
        val haystack = (article.title + " " + article.summary).lowercase()
        return keywords.firstOrNull {
            it.isNotBlank() && haystack.contains(it.lowercase())
        }.orEmpty()
    }

    /** 是否命中重点关键词 */
    fun matches(article: Article, keywords: List<String>): Boolean =
        hitKeyword(article, keywords).isNotBlank()
}
