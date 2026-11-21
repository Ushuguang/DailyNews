package com.dailypush.news.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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

/**
 * 「决策痕迹」页：今天有哪些新闻、选中了哪些、每一个为什么选 / 为什么没选。
 * 这个页面永远不会作为通知推给用户，只在这里随时查看。
 */
@Composable
fun TraceScreen(
    dates: List<String>,
    trace: PushTrace?,
    onPickDate: (String) -> Unit,
    onOpen: (String) -> Unit,
    scrollTopSignal: Int = 0
) {
    var showFiltered by remember { mutableStateOf(false) }

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
        if (dates.isNotEmpty()) {
            item(key = "dates") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dates.forEach { date ->
                        val isCurrent = trace?.date == date
                        AssistChip(
                            onClick = { onPickDate(date) },
                            label = { Text(if (date == dates.firstOrNull()) "$date（最新）" else date) },
                            colors = if (isCurrent) {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                AssistChipDefaults.assistChipColors()
                            }
                        )
                    }
                }
            }
        }

        if (trace == null) {
            item(key = "empty") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("这一天没有推送记录", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "痕迹在每次定时推送时自动生成，先等到点推送一次。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            return@LazyColumn
        }

        item(key = "summary") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "${trace.date} 的选稿过程",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    StatLine("抓取到的全部新闻", "${trace.candidates.size + trace.filteredOut.size} 条")
                    StatLine("进入候选池", "${trace.candidates.size} 条")
                    StatLine(
                        "最终推送",
                        "${trace.selectedIds.size} 条",
                        highlight = true
                    )
                    StatLine("被筛掉", "${trace.filteredOut.size} 条")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "筛选条件：${trace.filterSummary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (trace.aiUsed) "合并方式：AI 判断重复并合并" else "合并方式：未使用 AI",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (trace.aiError.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "AI 说明：${trace.aiError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (trace.note.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "AI 总结：${trace.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        val selected = trace.selectedItems
        val rejected = trace.candidates.filter { !it.selected }

        item(key = "h_sel") {
            SectionHeader("已选中并推送（${selected.size}）")
        }
        items(selected, key = { "s_" + it.id }) { item ->
            TraceItemCard(item = item, selected = true, onOpen = onOpen)
        }

        if (rejected.isNotEmpty()) {
            item(key = "h_rej") {
                SectionHeader("候选池里没选中的（${rejected.size}）")
            }
            items(rejected, key = { "r_" + it.id }) { item ->
                TraceItemCard(item = item, selected = false, onOpen = onOpen)
            }
        }

        if (trace.filteredOut.isNotEmpty()) {
            item(key = "h_flt") {
                Column {
                    SectionHeader("筛选阶段就被剔除的（${trace.filteredOut.size}）")
                    TextButton(onClick = { showFiltered = !showFiltered }) {
                        Text(if (showFiltered) "收起" else "展开看看")
                    }
                }
            }
            if (showFiltered) {
                items(trace.filteredOut, key = { "f_" + it.id }) { item ->
                    TraceItemCard(item = item, selected = false, onOpen = onOpen)
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
private fun StatLine(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun TraceItemCard(item: TraceItem, selected: Boolean, onOpen: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (selected) "已推送" else "未推送",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "为什么：${item.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.link.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onOpen(item.link) }) {
                    Text("看原文", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!selected && item.mergedInto.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    text = "已合并进另一条，不会重复推送",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
