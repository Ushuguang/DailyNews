package com.dailypush.news.data

/**
 * 根据抓取结果、筛选条件、AI 合并结果，生成一份完整的「决策痕迹」。
 *
 * 痕迹里能看到：今天有哪些新闻、选中了哪些、为什么选、为什么没选。
 * 它不会作为通知推给用户，但随时可以在「痕迹」页查看。
 */
object TraceBuilder {

    fun build(
        date: String,
        pushedAt: Long,
        all: List<Article>,
        filtered: List<Article>,
        candidates: List<Article>,
        pushed: List<Article>,
        merge: MergeOutcome?,
        aiError: String,
        settings: AppSettings,
        priorityIds: Set<String> = emptySet(),
        hitKeywordOf: Map<String, String> = emptyMap()
    ): PushTrace {
        val categoryOf = settings.feeds.associate { it.name to it.category }
        val filteredIds = filtered.map { it.id }.toSet()
        val pushedIds = pushed.map { it.id }.toSet()

        // 合并组：代表条目 → 其它成员
        val representativeOf = mutableMapOf<Int, MergeGroup>()
        val memberOf = mutableMapOf<Int, MergeGroup>()
        merge?.groups?.forEach { group ->
            representativeOf[group.representativeIndex] = group
            group.memberIndexes.forEach { index ->
                if (index != group.representativeIndex) memberOf[index] = group
            }
        }

        val candidateItems = candidates.mapIndexed { index, article ->
            val group = representativeOf[index]
            val merged = memberOf[index]
            val hit = hitKeywordOf[article.id]
            val reason = when {
                pushedIds.contains(article.id) && hit != null ->
                    "命中重点关键词「$hit」，优先推送" +
                        (group?.let { "（同时是这组重复报道的代表）" } ?: "")
                pushedIds.contains(article.id) && group != null ->
                    "被选为这组重复报道的代表（${group.reason.ifBlank { "信息更完整" }}）"
                pushedIds.contains(article.id) && merged == null ->
                    "AI 判定为独立内容，未发现重复报道"
                merged != null ->
                    "与「${merged.topic}」是同一件事，已合并到：${candidates.getOrNull(merged.representativeIndex)?.title.orEmpty()}" +
                        (merged.reason.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
                else -> "超出条数上限，未推送（分类：${PushFilter.categoryOf(article, categoryOf).ifBlank { "其它" }}）"
            }
            TraceItem(
                id = article.id,
                title = article.title,
                source = article.source,
                link = article.link,
                summary = article.summary,
                publishedAt = article.publishedAt,
                selected = pushedIds.contains(article.id),
                reason = reason,
                mergedInto = merged?.let { candidates.getOrNull(it.representativeIndex)?.id.orEmpty() }.orEmpty(),
                mergedFrom = group?.memberIndexes
                    ?.filter { it != index }
                    ?.mapNotNull { candidates.getOrNull(it)?.id }
                    .orEmpty(),
                topic = group?.topic ?: merged?.topic.orEmpty(),
                linkKind = article.linkKind
            )
        }

        // 被分类/关键词筛掉的
        val filteredItems = all
            .filter { !filteredIds.contains(it.id) }
            .take(MAX_FILTERED)
            .map { article ->
                TraceItem(
                    id = article.id,
                    title = article.title,
                    source = article.source,
                    link = article.link,
                    summary = article.summary,
                    publishedAt = article.publishedAt,
                    selected = false,
                    reason = filterReason(article, settings, categoryOf),
                    linkKind = article.linkKind
                )
            }

        val filterSummary = buildString {
            val cats = settings.pushCategories.filter { it != "热搜" }
            append("分类：")
            append(if (cats.isEmpty()) "不限" else cats.joinToString("、"))
            val keywords = settings.pushKeywords.filter { it.isNotBlank() }
            if (keywords.isNotEmpty()) {
                append("　重点：")
                append(keywords.joinToString("、"))
                append(if (priorityIds.isEmpty()) "（本次没命中相关内容）" else "（命中 ${priorityIds.size} 条，已优先推送）")
            }
            append("　热搜：")
            append(if (settings.includeHot) "含" else "不含")
        }

        return PushTrace(
            date = date,
            pushedAt = pushedAt,
            candidates = candidateItems,
            filteredOut = filteredItems,
            selectedIds = pushed.map { it.id },
            note = merge?.note.orEmpty(),
            filterSummary = filterSummary,
            aiUsed = merge != null,
            aiError = aiError
        )
    }

    private const val MAX_FILTERED = 80

    private fun filterReason(
        article: Article,
        settings: AppSettings,
        categoryOf: Map<String, String>
    ): String {
        if (article.isHot && !settings.includeHot) return "按设置不推送热搜"
        val categories = settings.pushCategories.filter { it != "热搜" }
        val category = PushFilter.categoryOf(article, categoryOf)
        if (categories.isNotEmpty() && !categories.contains(category)) {
            return "分类「${category.ifBlank { "未知" }}」不在推送范围（${categories.joinToString("、")}）"
        }
        // 关键词只是「重点关注」，没命中不会导致内容被筛掉，这里只说明情况
        return "不在最近 24 小时内，属于旧内容"
    }
}
