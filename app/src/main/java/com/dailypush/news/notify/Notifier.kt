package com.dailypush.news.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dailypush.news.MainActivity
import com.dailypush.news.R
import com.dailypush.news.data.Article
import com.dailypush.news.data.NotifyStyle
import com.dailypush.news.data.PushSelector
import com.dailypush.news.work.PushAlarmReceiver
import com.dailypush.news.work.PushScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {

    const val CHANNEL_DAILY = "daily_news"
    const val CHANNEL_ITEM = "daily_news_items"
    const val CHANNEL_WORK = "fetch_status"

    private const val SUMMARY_ID = 1000
    private const val ITEM_ID_BASE = 2000
    private const val ITEM_ID_RANGE = 30

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DAILY, "每日要闻", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每天定时推送当天的要闻汇总"
                enableVibration(true)
                setShowBadge(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ITEM, "单条新闻", NotificationManager.IMPORTANCE_LOW).apply {
                description = "把每条新闻单独推一条，点击直接打开原文"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, "后台抓取状态", NotificationManager.IMPORTANCE_MIN).apply {
                description = "抓取新闻时的临时状态提示"
                setShowBadge(false)
            }
        )
    }

    /**
     * 推送当天的要闻。
     * @param style 汇总 / 逐条 / 两者都要
     */
    fun notifyDaily(
        context: Context,
        articles: List<Article>,
        style: String,
        maxItems: Int
    ) {
        if (articles.isEmpty()) return
        ensureChannels(context)

        val limit = maxItems.coerceIn(3, 40)

        // 新闻与热搜分开配额，避免几百条热搜把当天要闻挤掉（与痕迹记录同一套规则）
        val selected = PushSelector.select(articles, limit)
        val news = selected.filter { !it.isHot }
        val hot = selected.filter { it.isHot }

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        if (style == NotifyStyle.SUMMARY || style == NotifyStyle.BOTH) {
            postSummary(context, news, hot, articles.size)
        }
        if (style == NotifyStyle.ITEMS || style == NotifyStyle.BOTH) {
            postItems(context, news + hot.take(HOT_ITEM_MAX), timeFormat)
        }
    }

    private const val HOT_ITEM_MAX = 5

    /** 汇总通知正文最多列多少条要闻 */
    private const val MAX_BODY_LINES = 20

    private fun postSummary(context: Context, news: List<Article>, hot: List<Article>, total: Int) {
        val bigText = buildString {
            if (news.isNotEmpty()) {
                append("【今日要闻】\n")
                // 通知正文最多列这么多条，多的写「还有 N 条」，避免系统把通知截断
                val shown = news.take(MAX_BODY_LINES)
                // 保持推送名单的顺序（重点条目在最前），不再按来源重排，
                // 否则「重点」的条目会被打散到各个来源分组里
                val multiSource = news.map { it.source }.distinct().size > 1
                shown.forEach { article ->
                    append("· ").append(article.title)
                    if (multiSource) append("（${article.source}）")
                    append('\n')
                }
                if (news.size > shown.size) {
                    append("…… 还有 ${news.size - shown.size} 条，")
                    append("点开 App 看完整列表\n")
                }
            }
            if (hot.isNotEmpty()) {
                append("\n【热搜榜】\n")
                hot.groupBy { it.source }.forEach { (source, items) ->
                    append(source).append('\n')
                    items.forEach {
                        append("· ").append(it.title)
                        if (it.isSearchLink) append("（热搜词，点开是站内搜索）")
                        append('\n')
                    }
                }
            }
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TODAY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val refreshIntent = PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, PushAlarmReceiver::class.java).setAction(PushScheduler.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (hot.isEmpty()) {
            "今日要闻 · ${news.size} 条"
        } else {
            "今日要闻 ${news.size} 条 · 热搜 ${hot.size} 条"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(news.firstOrNull()?.title ?: hot.firstOrNull()?.title.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSubText("点击查看全部要闻")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                "立即刷新",
                refreshIntent
            ).build()
        )

        notify(context, SUMMARY_ID, builder.build())
    }

    private fun postItems(context: Context, top: List<Article>, timeFormat: SimpleDateFormat) {
        val manager = NotificationManagerCompat.from(context)
        // 清掉上一轮的单条通知，避免堆积
        for (i in 0 until ITEM_ID_RANGE) manager.cancel(ITEM_ID_BASE + i)

        top.forEachIndexed { index, article ->
            val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(article.link))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context,
                ITEM_ID_BASE + index,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val time = if (article.publishedAt > 0) timeFormat.format(Date(article.publishedAt)) else ""
            val builder = NotificationCompat.Builder(context, CHANNEL_ITEM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(article.title)
                .setContentText(article.source + if (time.isNotEmpty()) " · $time" else "")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        article.summary.ifBlank { article.title }
                    )
                )
                .setSubText(article.source)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            notify(context, ITEM_ID_BASE + index, builder.build())
        }
    }

    /** 手动测试推送 */
    fun notifyTest(context: Context) {
        ensureChannels(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("推送测试成功")
            .setContentText("如果你看到了这条通知，说明通知权限与后台运行都正常。")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "如果你看到了这条通知，说明通知权限与后台运行都正常。\n" +
                        "接下来可以在「助手」页完成自启动与电池优化的授权。"
                )
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notify(context, 9999, builder.build())
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
