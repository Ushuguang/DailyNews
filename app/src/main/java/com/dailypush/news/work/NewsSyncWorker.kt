package com.dailypush.news.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.dailypush.news.R
import com.dailypush.news.data.AiClient
import com.dailypush.news.data.Article
import com.dailypush.news.data.MergeOutcome
import com.dailypush.news.data.NewsRepository
import com.dailypush.news.data.NotifyStyle
import com.dailypush.news.data.PushFilter
import com.dailypush.news.data.PushSelector
import com.dailypush.news.data.PushTraceStore
import com.dailypush.news.data.SecretStore
import com.dailypush.news.data.TraceBuilder
import com.dailypush.news.data.SettingsRepository
import com.dailypush.news.notify.Notifier
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * 抓取 + 推送的执行体。
 *
 * KEY_NOTIFY = true  → 抓取完发通知（定时任务）
 * KEY_NOTIFY = false → 只抓取存缓存（手动刷新）
 */
class NewsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val settingsRepo = SettingsRepository(app)
        val settings = settingsRepo.settingsFlow.first()

        val shouldNotify = inputData.getBoolean(KEY_NOTIFY, true)
        // 哪个时间点触发的（08:00 / 12:00 …）；空 = 手动触发
        val slot = inputData.getString(KEY_SLOT).orEmpty()
        // force = 手动触发「立即生成今日推送」，不受定时开关和 3 小时去重限制
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (shouldNotify && !settings.pushEnabled && !force) return Result.success()

        val feeds = settings.feeds.filter { it.enabled }
        if (feeds.isEmpty()) return Result.success()

        runCatching { setForegroundCompat() }

        val result = NewsRepository(app).fetchAndCache(feeds)
        settingsRepo.setLastFetchSummary(
            if (result.failedCount == 0) {
                "全部 ${result.successCount} 个源抓取成功"
            } else {
                "${result.successCount} 个成功 / ${result.failedCount} 个失败"
            }
        )

        if (shouldNotify && result.articles.isNotEmpty()) {
            val today = LocalDate.now().toString()
            val now = System.currentTimeMillis()
            // 同一天短时间内只推一次，避免「闹钟 + 兜底周期任务」重复打扰
            // 同一个时间点一天只推一次；不同时间点（早中晚）各推各的
            val slotKey = if (slot.isBlank()) today else "$today@$slot"
            val alreadyPushedThisSlot = settings.lastPushSlot == slotKey ||
                (slot.isBlank() && settings.lastPushDate == today &&
                    (now - settings.lastPushAt) < 3 * 60 * 60 * 1000L)
            if (!alreadyPushedThisSlot || force) {
                // 1) 分类 / 热搜筛选 + 标记「重点」（关键词命中）
                val filterResult = PushFilter.apply(result.articles, settings, settings.feeds)
                val priorityIds = filterResult.priorityIds
                // 只留最近 24 小时的，避免把前几天的旧缓存推出去
                // 重点条目排前面，保证不会被候选池上限（40）挤掉
                val pool = pickFresh(filterResult.items)
                // 候选池按分类分层取，保证 AI 看到的候选里各个分类都有；
                // 重点条目额外留一份名额，不会被上限挤掉
                val priorityPool = pool.filter { it.id in priorityIds }
                val restPool = pool.filter { it.id !in priorityIds }
                val priorityTake = priorityPool.take(PushSelector.MAX_CANDIDATES / 3)
                val candidates = priorityTake +
                    PushSelector.stratified(restPool, PushSelector.MAX_CANDIDATES - priorityTake.size)

                // 2) AI 合并重复报道
                var merge: MergeOutcome? = null
                var aiError = ""
                val apiKey = SecretStore(app).apiKey
                if (settings.aiMerge && apiKey.isNotBlank()) {
                    val mergeResult = AiClient.mergeSimilar(
                        baseUrl = settings.aiBaseUrl,
                        apiKey = apiKey,
                        model = settings.aiModel,
                        candidates = candidates
                    )
                    merge = mergeResult.getOrNull()
                    if (merge == null) {
                        aiError = "AI 合并失败：${mergeResult.exceptionOrNull()?.message ?: "未知错误"}"
                    }
                } else if (settings.aiMerge) {
                    aiError = "未填写 API Key，本次未做合并"
                }

                // 3) 选稿（合并后只留代表条目）
                var afterMerge = if (merge != null) {
                    // 合并时让「重点」当代表，保证重点条目不会被合并掉
                    PushSelector.applyMerge(candidates, merge, priorityIds)
                } else {
                    candidates
                }
                // 保险：AI 偶尔会把不相干的条目也并进一组，导致推送只剩一两条。
                // 压得太狠就整份丢弃这次合并结果，按原顺序推。
                if (merge != null && candidates.size >= 5 &&
                    afterMerge.size * 3 < candidates.size
                ) {
                    aiError = "AI 合并过于激进（把 ${candidates.size} 条压到 ${afterMerge.size} 条），已忽略本次合并"
                    merge = null
                    afterMerge = candidates
                }
                val pushed = PushSelector.select(afterMerge, settings.maxItems, priorityIds)

                // 4) 写决策痕迹：今天有哪些、选了哪些、为什么
                PushTraceStore.save(
                    app,
                    TraceBuilder.build(
                        date = today,
                        pushedAt = now,
                        all = result.articles,
                        filtered = pool,
                        candidates = candidates,
                        pushed = pushed,
                        merge = merge,
                        aiError = aiError,
                        settings = settings,
                        priorityIds = priorityIds,
                        hitKeywordOf = filterResult.hitKeywordOf
                    )
                )

                // 5) 推送
                if (pushed.isNotEmpty()) {
                    Notifier.notifyDaily(app, pushed, settings.notifyStyle, settings.maxItems)
                    settingsRepo.markPushed(today, now, slotKey)
                }
            }
        }

        return Result.success()
    }

    /** 只挑最近的新闻（24 小时内发布的，或本次刚抓到的） */
    private fun pickFresh(all: List<Article>): List<Article> {
        val now = System.currentTimeMillis()
        val window = now - 24 * 60 * 60 * 1000L
        val recent = all.filter { article ->
            val time = if (article.publishedAt > 0) article.publishedAt else article.fetchedAt
            time >= window
        }
        return if (recent.isNotEmpty()) recent else all.take(10)
    }

    private suspend fun setForegroundCompat() {
        val notification = NotificationCompat.Builder(applicationContext, Notifier.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在获取今日要闻…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1001, notification)
        }
        setForeground(foregroundInfo)
    }

    companion object {
        const val KEY_NOTIFY = "notify"
        const val KEY_FORCE = "force"
        /** 触发本次推送的时间点标记 */
        const val KEY_SLOT = "slot"
    }
}
