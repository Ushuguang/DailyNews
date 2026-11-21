package com.dailypush.news.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 抓取结果 */
data class FetchResult(
    val articles: List<Article>,
    val successCount: Int,
    val failedCount: Int,
    val errors: List<String>
)

/**
 * 负责「抓取所有订阅源 → 合并去重 → 落盘」的流程。
 */
class NewsRepository(private val context: Context) {

    /**
     * 抓取所有启用的订阅源。
     * @param onlyToday 是否只保留今天的内容（推送用 false，保留全部供阅读）
     */
    suspend fun fetchAndCache(feeds: List<Feed>): FetchResult = coroutineScope {
        val enabled = feeds.filter { it.enabled && it.url.isNotBlank() }
        if (enabled.isEmpty()) {
            return@coroutineScope FetchResult(emptyList(), 0, 0, listOf("没有启用任何订阅源"))
        }

        val limiter = Semaphore(6)
        val errors = mutableListOf<String>()

        val deferred = enabled.map { feed ->
            async(Dispatchers.IO) {
                limiter.withPermit {
                    runCatching {
                        when (feed.kind) {
                            Feed.KIND_WEB -> WebPageFetcher.fetch(feed)
                            Feed.KIND_HOT -> HotFetcher.fetch(feed)
                            else -> RssFetcher.fetch(feed)
                        }
                    }
                }
            }
        }

        val results = deferred.awaitAll()
        val collected = mutableListOf<Article>()
        var ok = 0
        var fail = 0
        results.forEachIndexed { index, result ->
            val feed = enabled[index]
            result.onSuccess { list ->
                ok++
                collected.addAll(list)
            }.onFailure { error ->
                fail++
                errors.add("${feed.name}: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        val merged = mergeWithCache(context, collected)
        ArticleCache.save(context, merged)

        FetchResult(merged, ok, fail, errors)
    }

    /** 合并新抓到的内容与本地缓存，按链接去重 */
    private suspend fun mergeWithCache(context: Context, fresh: List<Article>): List<Article> {
        val cached = ArticleCache.load(context)
        val map = LinkedHashMap<String, Article>()
        // 先放缓存，保证已有内容的展示顺序稳定
        cached.forEach { map[it.link] = it }
        fresh.forEach { map[it.link] = it }
        return map.values
            .sortedWith(compareByDescending<Article> { it.publishedAt.takeIf { t -> t > 0 } ?: it.fetchedAt }
                .thenByDescending { it.fetchedAt })
    }
}
