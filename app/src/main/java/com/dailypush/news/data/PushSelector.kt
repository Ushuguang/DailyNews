package com.dailypush.news.data

/**
 * 最终推送名单的挑选规则。
 *
 * 三条规矩：
 *  1. **重点（关键词命中）优先**：有名额就必须推，且排在最前面；
 *  2. **每个分类都有份额**：剩下的名额按分类轮流取，
 *     不会出现「时政条目多就把国际、科技挤没了」；
 *  3. **热搜有独立配额**：每个平台 3 条、总共 12 条，不占新闻名额。
 *
 * 推送和痕迹记录用的是同一套规则，保证「痕迹里看到的 = 实际推出去的」。
 */
object PushSelector {

    const val HOT_PER_SOURCE = 3
    const val HOT_TOTAL_MAX = 12

    /** 送进 AI 挑选的候选上限（太多费 token 也难选） */
    const val MAX_CANDIDATES = 40

    /**
     * 应用 AI 的合并结果：每组只留代表条目，没进任何组的条目单独保留。
     *
     * 如果一组里有「重点」（关键词命中）的条目，就让它当代表，
     * 保证「重点一定在」不会被合并掉。
     */
    fun applyMerge(
        candidates: List<Article>,
        merge: MergeOutcome,
        priorityIds: Set<String> = emptySet()
    ): List<Article> {
        val grouped = merge.groups.flatMap { it.memberIndexes }.toSet()
        val selected = mutableSetOf<Int>()
        merge.groups.forEach { group ->
            val representativeId = candidates.getOrNull(group.representativeIndex)?.id.orEmpty()
            val representative = if (priorityIds.contains(representativeId)) {
                group.representativeIndex
            } else {
                // 代表条目不是重点，但组里有一条是重点 → 让重点当代表
                group.memberIndexes.firstOrNull { index ->
                    val id = candidates.getOrNull(index)?.id
                    id != null && priorityIds.contains(id)
                } ?: group.representativeIndex
            }
            selected.add(representative)
        }
        candidates.indices.filter { it !in grouped }.forEach { selected.add(it) }
        return selected.sorted().mapNotNull { candidates.getOrNull(it) }
    }

    /**
     * 选出最终推送的名单。
     *
     * @param priorityIds 重点条目（关键词命中）的 id —— 这些必须推、且排在最前
     */
    fun select(
        articles: List<Article>,
        maxItems: Int,
        priorityIds: Set<String> = emptySet()
    ): List<Article> {
        val limit = maxItems.coerceIn(3, 40)

        val hot = articles.filter { it.isHot }
            .groupBy { it.source }
            .values
            .flatMap { group ->
                // 命中的排前面，再按原顺序补足
                val hits = group.filter { it.id in priorityIds }
                val rest = group.filter { it.id !in priorityIds }
                (hits + rest).take(HOT_PER_SOURCE)
            }
            .take(HOT_TOTAL_MAX)

        val picked = pickNews(articles.filter { !it.isHot }, limit, priorityIds)
        return picked + hot
    }

    /**
     * 选新闻：
     *  1. 重点优先，且给其它分类留够位置（每个分类至少 1 条）；
     *  2. 剩下的名额按分类轮流取，保证每个分类都出现。
     */
    private fun pickNews(
        news: List<Article>,
        limit: Int,
        priorityIds: Set<String>
    ): List<Article> {
        if (news.isEmpty() || limit <= 0) return emptyList()

        val priority = news.filter { it.id in priorityIds }
        val rest = news.filter { it.id !in priorityIds }

        // 重点最多占多少名额：给其它分类各留 1 条
        val otherCategories = rest.map { it.category.ifBlank { "其它" } }.distinct().size
        val priorityQuota = minOf(priority.size, maxOf(1, limit - otherCategories))

        val picked = ArrayList<Article>(limit)
        picked.addAll(priority.take(priorityQuota))
        picked.addAll(stratified(rest, limit - picked.size))
        return picked
    }

    /**
     * 分层轮流取：每个分类按轮次各出一条。
     *
     * 分类内的条目保持原顺序（一般是按时间倒序），所以每个分类露出来的都是它最新的几条。
     *
     * 候选池也用它 —— 否则前 40 条全被同一个分类占满，AI 根本看不到别的分类。
     */
    fun stratified(items: List<Article>, limit: Int): List<Article> {
        if (limit <= 0 || items.isEmpty()) return emptyList()

        val groups = LinkedHashMap<String, ArrayDeque<Article>>()
        items.groupBy { it.category.ifBlank { "其它" } }
            .forEach { (category, list) -> groups[category] = ArrayDeque(list) }

        // 条目多的分类排前面，每轮先取
        val order = groups.keys.sortedByDescending { groups[it]?.size ?: 0 }

        val out = ArrayList<Article>(minOf(limit, items.size))
        var progress = true
        while (out.size < limit && progress) {
            progress = false
            for (key in order) {
                if (out.size >= limit) break
                val queue = groups[key] ?: continue
                if (queue.isNotEmpty()) {
                    out.add(queue.removeFirst())
                    progress = true
                }
            }
        }
        return out
    }
}
