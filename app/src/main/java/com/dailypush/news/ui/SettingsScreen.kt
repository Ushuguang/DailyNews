package com.dailypush.news.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailypush.news.data.AppSettings
import com.dailypush.news.data.NotifyStyle
import com.dailypush.news.util.TimeUtil
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    vm: MainViewModel,
    onOpenGuide: () -> Unit,
    onOpenTrace: () -> Unit,
    onOpenAbout: () -> Unit
) {
    var keywordText by remember(settings.pushKeywords) {
        mutableStateOf(settings.pushKeywords.joinToString("，"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- 推送 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每日定时推送", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "关掉后不再自动推送，但仍可手动刷新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = settings.pushEnabled, onCheckedChange = vm::setPushEnabled)
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                PushTimeEditor(
                    times = settings.pushTimes,
                    onChange = { vm.setPushTimes(it) }
                )
            }
        }

        // ---------- 通知方式 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("通知方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val options = listOf(
                    NotifyStyle.SUMMARY to "汇总通知：一条通知里列出所有要闻（推荐）",
                    NotifyStyle.ITEMS to "逐条通知：每条新闻推一条，点击直接看原文",
                    NotifyStyle.BOTH to "两者都要：先来一条汇总，再逐条提醒"
                )
                options.forEach { (value, label) ->
                    val selected = settings.notifyStyle == value
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { vm.setNotifyStyle(value) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { vm.setNotifyStyle(value) }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        if (selected) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最多条数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${settings.maxItems} 条",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = settings.maxItems.toFloat(),
                    onValueChange = { vm.setMaxItems(it.roundToInt()) },
                    valueRange = 3f..40f,
                    steps = 36
                )
            }
        }

        // ---------- 状态 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("运行状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                StatusLine("上次推送", if (settings.lastPushAt > 0) TimeUtil.formatDateTime(settings.lastPushAt) else "还没有推送过")
                StatusLine(
                    "下次推送",
                    if (settings.pushTimes.size > 1) {
                        settings.pushTimes.joinToString("、") + "（每天 ${settings.pushTimes.size} 次）"
                    } else {
                        TimeUtil.formatClock(settings.pushHour, settings.pushMinute) + "（每天）"
                    }
                )
                StatusLine("订阅源", "${settings.feeds.count { it.enabled }} 个已启用")
            }
        }

        // ---------- 推送内容筛选（可手动编辑） ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("推送内容筛选", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "这里可以手动改；「AI」页用一句话描述需求也能自动配置。分类一个都不选 = 不限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                val cats = settings.pushCategories
                val allCategories = listOf("时政", "要闻", "国际", "财经", "社会", "科技", "生活")

                Text(
                    "内容分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "选中的每一个分类都会推（不选 = 不限）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    allCategories.forEach { category ->
                        val selected = cats.contains(category)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = if (selected) cats - category else cats + category
                                vm.updateFilter(next, settings.pushKeywords, settings.includeHot)
                            },
                            label = {
                                Text(
                                    text = if (selected) "✓ $category" else category,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("附带热搜榜", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "微博 / 百度 / 抖音 / 知乎 / B站 / 头条 / 腾讯 的实时热搜",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.includeHot,
                        onCheckedChange = { vm.updateFilter(cats, settings.pushKeywords, it) }
                    )
                }
                Spacer(Modifier.height(10.dp))

                Text(
                    "重点关键词（逗号分隔）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "命中的内容会优先推送并保证入选；一条都没命中也不影响其它内容照常推送",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keywordText,
                        onValueChange = { keywordText = it },
                        placeholder = { Text("例如：APEC，新能源") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val list = keywordText.split(",", "，", "、")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        vm.updateFilter(cats, list, settings.includeHot)
                    }) { Text("保存") }
                }
                if (settings.pushKeywords.isNotEmpty()) {
                    Text(
                        "当前重点：${settings.pushKeywords.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (settings.aiNote.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "AI 的理解：${settings.aiNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- AI 合并 + 入口 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 合并重复报道", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "推送前让 AI 判断哪些新闻在讲同一件事，合并成一条；每条为什么选、为什么没选都记在决策痕迹里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = settings.aiMerge, onCheckedChange = vm::setAiMerge)
                }
                if (!settings.aiMerge) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "关闭后直接按抓取顺序推送，同一件事可能出现多次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onOpenGuide, modifier = Modifier.weight(1f)) {
                        Text("助手与权限")
                    }
                    OutlinedButton(onClick = onOpenTrace, modifier = Modifier.weight(1f)) {
                        Text("决策痕迹")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("关于 DailyNews", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "版本信息、作者、仓库，以及检查更新（在 App 内直接下载安装新版本）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenAbout) { Text("打开") }
                }
            }
        }

        // ---------- 操作 ----------
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.refreshNow() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("立即刷新")
            }
            OutlinedButton(onClick = { vm.testNotification() }, modifier = Modifier.weight(1f)) {
                Text("测试通知")
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("温馨提示", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "在小米 / 澎湃手机上，系统默认会限制后台运行。\n" +
                        "请到「助手」页依次打开：通知权限、精确闹钟、电池优化白名单、自启动。\n" +
                        "四项都打开后，定时推送才能准时到达。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        CopyrightFooter()
    }

}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

