package com.dailypush.news.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dailypush.news.data.PushTrace
import com.dailypush.news.data.TraceItem
import com.dailypush.news.util.TimeUtil

/**
 * 「今日推送」页：今天到底推了什么，每条都能直接点开各家原文。
 */
@Composable
fun TodayScreen(
    trace: PushTrace?,
    nextPushTime: String,
    busy: Boolean,
    onPushNow: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenTrace: () -> Unit,
    scrollTopSignal: Int = 0
) {
    if (trace == null) {
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
            Text("今天还没有推送记录", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "下次推送：$nextPushTime。到点之后这里会列出推送的全部内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPushNow, enabled = !busy) {
                Text(if (busy) "正在生成…" else "立即生成今日推送")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "会按定时推送的完整流程走一遍：筛选 → AI 合并 → 记录痕迹 → 发通知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val selected = trace.selectedItems
    val mergedGroupCount = trace.candidates.map { it.topic }.filter { it.isNotBlank() }.distinct().size

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
        item(key = "header") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "今天推送了 ${selected.size} 条",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append("推送时间 ")
                            append(TimeUtil.formatDateTime(trace.pushedAt))
                            if (mergedGroupCount > 0) {
                                append("　合并了 $mergedGroupCount 组重复报道")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (trace.note.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = trace.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onOpenTrace) {
                        Text("查看决策痕迹（为什么选这些）", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    TextButton(onClick = onPushNow, enabled = !busy) {
                        Text(
                            if (busy) "正在生成…" else "重新生成",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        items(selected, key = { it.id }) { item ->
            PushedItemCard(
                item = item,
                trace = trace,
                onOpen = onOpen
            )
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
private fun PushedItemCard(item: TraceItem, trace: PushTrace, onOpen: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val others = item.mergedFrom.mapNotNull { id -> trace.candidates.find { it.id == id } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.source,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.reason.startsWith("命中重点关键词")) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "重点",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.tertiaryContainer,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = TimeUtil.formatTime(item.publishedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            if (item.linkKind == "search") {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "这条是热搜关键词，平台只给词没有原文，点开会进站内搜索",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (item.topic.isNotBlank() && others.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "话题：${item.topic}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpen(item.link) }) {
                    Text(if (item.linkKind == "search") "站内搜索" else "看原文")
                }
                if (others.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起其他来源" else "另有 ${others.size} 家也报道了")
                    }
                }
            }

            if (expanded && others.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                others.forEach { other ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(other.link) }
                            .padding(vertical = 6.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = other.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = other.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "看原文",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
