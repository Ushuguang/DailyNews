package com.dailypush.news.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dailypush.news.data.AiClient
import com.dailypush.news.data.AiPlan
import com.dailypush.news.data.NotifyStyle
import kotlin.math.roundToInt

@Composable
fun AiScreen(vm: MainViewModel) {
    val state by vm.ai.collectAsState()
    val settings by vm.settings.collectAsState()
    var addTimeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------------- API 连接 ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("AI 接口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Key 只存在你手机里，不上传任何服务器。默认是 MiniMax 国内站。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = vm::setAiBaseUrl,
                    label = { Text("接口地址") },
                    placeholder = { Text(AiClient.DEFAULT_BASE_URL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                var showKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = vm::setAiApiKey,
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.model,
                    onValueChange = vm::setAiModel,
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "常用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { vm.loadAiModels() },
                        enabled = !state.loadingModels
                    ) {
                        Text(if (state.loadingModels) "读取中…" else "查看可用模型")
                    }
                }
                if (state.availableModels.isNotEmpty()) {
                    Text(
                        "这个 Key 实际可用的模型（点一下直接填入）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        state.availableModels.forEach { model ->
                            AssistChip(
                                onClick = { vm.setAiModel(model) },
                                label = {
                                    Text(
                                        model,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    AiClient.suggestedModels.forEach { model ->
                        AssistChip(
                            onClick = { vm.setAiModel(model) },
                            label = {
                                Text(
                                    model,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::testAiConnection, enabled = !state.testing) {
                        if (state.testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.testing) "测试中" else "测试连接")
                    }
                }
                if (state.status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------------- 合并开关 ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("推送时合并重复报道", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "同一件事被多家媒体报道时，只推一条代表消息，其余折叠在「今日」页里。选与不选的理由都会记进决策痕迹。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = settings.aiMerge, onCheckedChange = vm::setAiMerge)
                }
            }
        }

        // ---------------- 需求描述 ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("你想要什么内容？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "用大白话写清楚数量、时间、内容，AI 会自动填好下面的配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.requirement,
                    onValueChange = vm::setAiRequirement,
                    placeholder = { Text("例如：每天早上 8 点给我 10 条国际和科技新闻，另外带上热搜") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = { vm.setAiRequirement("每天早上8点，给我10条国际和科技新闻") },
                        label = { Text("国际+科技 10 条", style = MaterialTheme.typography.labelSmall) }
                    )
                    AssistChip(
                        onClick = { vm.setAiRequirement("每天早上7点半推5条财经新闻，只要人工智能相关的，带上热搜") },
                        label = { Text("财经+AI+热搜", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = vm::parseWithAi,
                    enabled = !state.parsing && state.requirement.isNotBlank()
                ) {
                    if (state.parsing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.parsing) "AI 解析中…" else "AI 解析")
                }
            }
        }

        // ---------------- 解析结果 ----------------
        state.plan?.let { plan ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "解析结果",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (plan.note.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            plan.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                    Spacer(Modifier.height(12.dp))

                    // 推送时间（可多个）
                    Text(
                        "推送时间点",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    plan.pushTimes.forEach { time ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .background(
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                        ) {
                            Text(
                                text = "✓ $time",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    vm.updateAiPlan { p -> p.copy(pushTimes = p.pushTimes - time) }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "删除",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { addTimeDialog = true },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "+ 添加一个推送时间",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "条数：${plan.maxItems}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Slider(
                        value = plan.maxItems.toFloat(),
                        onValueChange = { v -> vm.updateAiPlan { it.copy(maxItems = v.roundToInt()) } },
                        valueRange = 3f..40f,
                        steps = 36
                    )

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "内容分类（每个选中的分类都会推）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    val categories = AiClient.categories + "热搜"
                    categories.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { category ->
                                FilterChip(
                                    selected = plan.categories.contains(category),
                                    onClick = {
                                        vm.updateAiPlan { current ->
                                            val next = if (current.categories.contains(category)) {
                                                current.categories - category
                                            } else {
                                                current.categories + category
                                            }
                                            current.copy(categories = next)
                                        }
                                    },
                                    label = {
                                        val on = plan.categories.contains(category)
                                        Text(
                                            text = if (on) "✓ $category" else category,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "附带热搜",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = plan.includeHot,
                            onCheckedChange = { v -> vm.updateAiPlan { it.copy(includeHot = v) } }
                        )
                    }

                    if (plan.keywords.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "重点关键词：${plan.keywords.joinToString("、")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "命中的会优先推送并保证入选；没命中也不影响其它内容",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "通知方式：${NotifyStyle.label(plan.notifyStyle)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            NotifyStyle.SUMMARY to "汇总",
                            NotifyStyle.ITEMS to "逐条",
                            NotifyStyle.BOTH to "都要"
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = plan.notifyStyle == value,
                                onClick = { vm.updateAiPlan { it.copy(notifyStyle = value) } },
                                label = {
                                    val on = plan.notifyStyle == value
                                    Text(
                                        text = if (on) "✓ $label" else label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = vm::applyAiPlan,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("应用到推送") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { vm.updateAiPlan { AiPlan() } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("清空重来") }
                }
            }
        }

        // ---------------- 当前生效的配置 ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("当前推送配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildString {
                        append("时间 ${settings.pushTimes.joinToString("、")}")
                        append("　条数 ${settings.maxItems}")
                        val cats = settings.pushCategories
                        append("　分类 ")
                        append(if (cats.isEmpty()) "不限" else cats.joinToString("、"))
                        val keywords = settings.pushKeywords
                        if (keywords.isNotEmpty()) {
                            append("\n重点关键词 ${keywords.joinToString("、")}")
                        }
                        append("\n热搜 ${if (settings.includeHot) "包含" else "不包含"}")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        CopyrightFooter()
    }

    if (addTimeDialog) {
        PushTimePickerDialog(
            initialHour = state.plan?.pushTimes?.lastOrNull()?.substringBefore(":")?.toIntOrNull() ?: 8,
            initialMinute = state.plan?.pushTimes?.lastOrNull()?.substringAfter(":")?.toIntOrNull() ?: 0,
            onDismiss = { addTimeDialog = false },
            onConfirm = { hour, minute ->
                val value = "%02d:%02d".format(hour, minute)
                vm.updateAiPlan { current ->
                    if (current.pushTimes.contains(value)) current
                    else current.copy(pushTimes = (current.pushTimes + value).sorted())
                }
                addTimeDialog = false
            }
        )
    }
}

