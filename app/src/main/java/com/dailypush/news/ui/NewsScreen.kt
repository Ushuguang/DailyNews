package com.dailypush.news.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dailypush.news.data.Feed
import com.dailypush.news.data.Article
import com.dailypush.news.util.TimeUtil

@Composable
fun NewsScreen(
    articles: List<Article>,
    feeds: List<Feed>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onGoGuide: () -> Unit,
    scrollTopSignal: Int = 0
) {
    if (articles.isEmpty()) {
        EmptyNewsState(busy = busy, onRefresh = onRefresh, onGoGuide = onGoGuide)
        return
    }

    val hotBySource = remember(articles) { articles.filter { it.isHot }.groupBy { it.source } }
    val news = remember(articles) { articles.filter { !it.isHot } }
    // 源名 → 分类（旧缓存没有分类字段时兜底）
    val categoryOf = remember(feeds) { feeds.associate { it.name to it.category } }
    val groups = remember(news) {
        news.groupBy { TimeUtil.dayLabel(it.publishedAt, it.fetchedAt) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(scrollTopSignal) {
        if (scrollTopSignal > 0) listState.animateScrollToItem(0)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "summary") { SummaryHeader(news.size, hotBySource.values.sumOf { it.size }) }

        if (hotBySource.isNotEmpty()) {
            item(key = "hot_title") {
                Text(
                    text = "热搜榜",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = "标「搜索」的是热搜关键词（平台只给词、没有原文），点开是站内搜索页；" +
                        "没标的点开就是具体内容页。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            hotBySource.forEach { (source, items) ->
                item(key = "hot_$source") {
                    HotCard(source = source, items = items, onOpen = onOpen)
                }
            }
        }

        groups.forEach { (label, items) ->
            item(key = "header_$label") {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            // 日期之下再按新闻分类分组
            val byCategory = items.groupBy {
                it.category.ifBlank { categoryOf[it.source] ?: "其它" }
            }
            byCategory.forEach { (category, categoryItems) ->
                item(key = "cat_${label}_$category") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${categoryItems.size} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                items(categoryItems, key = { it.id }) { article ->
                    ArticleCard(
                        article = article,
                        category = category,
                        onOpen = onOpen
                    )
                }
            }
        }

        item(key = "footer") { CopyrightFooter() }
    }
        ScrollTopButton(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }
}

@Composable
private fun SummaryHeader(newsCount: Int, hotCount: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "共 $newsCount 条要闻" + if (hotCount > 0) " · $hotCount 条热搜" else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "点标题直接在浏览器里读原文",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun HotCard(source: String, items: List<Article>, onOpen: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = source,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(6.dp))
            items.take(6).forEachIndexed { index, article ->
                if (index > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(article.link) }
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (article.isSearchLink) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "搜索",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (article.summary.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = article.summary.substringAfter("· ").trim(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
            if (items.size > 6) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "另有 ${items.size - 6} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun ArticleCard(article: Article, category: String, onOpen: (String) -> Unit) {
    Card(
        onClick = { onOpen(article.link) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = article.source,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (article.publishedAt > 0) {
                        TimeUtil.formatTime(article.publishedAt)
                    } else {
                        TimeUtil.relative(article.fetchedAt)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (article.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = article.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyNewsState(busy: Boolean, onRefresh: () -> Unit, onGoGuide: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Text("还没有内容", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "点下面的按钮抓一次，或者到「助手」页完成通知与自启动授权。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRefresh, enabled = !busy) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "抓取中…" else "立即抓取")
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(Modifier.width(120.dp))
        Spacer(Modifier.height(8.dp))
        Button(onClick = onGoGuide) {
            Text("去「助手」页检查权限")
        }
    }
}

