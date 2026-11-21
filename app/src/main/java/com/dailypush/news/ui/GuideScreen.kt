package com.dailypush.news.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dailypush.news.util.XiaomiCompat

@Composable
fun GuideScreen() {
    val context = LocalContext.current

    // 每次进入本页都重新读取系统状态
    val notificationsOk = XiaomiCompat.notificationsEnabled(context)
    val exactAlarmOk = XiaomiCompat.canScheduleExactAlarms(context)
    val batteryOk = XiaomiCompat.isIgnoringBatteryOptimizations(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "推送体检",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "澎湃 OS 对后台管控很严，四项全部处理完，定时推送才会准时到。\n" +
                        "改完设置后，回到这个页面，状态会自动刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        GuideItem(
            title = "1. 通知权限",
            ok = notificationsOk,
            okText = "已允许",
            okDetail = "通知权限已开启，推送能正常送达",
            failText = "未允许 · 允许后才能收到推送",
            actionText = "去开启",
            onAction = { XiaomiCompat.openNotificationSettings(context) }
        )

        GuideItem(
            title = "2. 精确闹钟（闹钟与提醒）",
            ok = exactAlarmOk,
            okText = "已授权",
            okDetail = "定时任务会按设定时间准点触发",
            failText = "未授权 · 定时任务可能被延迟",
            actionText = "去授权",
            onAction = { XiaomiCompat.requestExactAlarms(context) }
        )

        GuideItem(
            title = "3. 电池优化白名单",
            ok = batteryOk,
            okText = "已加入",
            okDetail = "息屏时也不会被系统冻结",
            failText = "未加入 · 息屏后容易被系统冻结",
            actionText = "去设置",
            onAction = { XiaomiCompat.requestIgnoreBatteryOptimizations(context) }
        )

        GuideItem(
            title = "4. 自启动管理（澎湃/小米）",
            ok = null,
            okText = "",
            okDetail = "",
            failText = "需要在系统里手动打开「自启动」开关",
            actionText = "去开启",
            onAction = {
                val opened = XiaomiCompat.openAutostartSettings(context)
                if (!opened) {
                    Toast.makeText(context, "没找到自启动入口，请在系统设置的「应用管理」里找到本应用并开启自启动", Toast.LENGTH_LONG).show()
                }
            }
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("澎湃 OS 额外建议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Bullet("在最近任务界面下拉本应用卡片（或长按），选择「锁定」，避免一键清理时被杀掉。")
                Bullet("设置 → 应用设置 → 应用管理 → 本应用 → 省电策略，选择「无限制」。")
                Bullet("关闭系统级的「省电模式」，或把它加入省电模式白名单。")
                Bullet("通知设置里把本应用的锁屏通知设为「显示」，并允许声音与震动。")
                Bullet("如果到点没推送，先看应用内「设置 → 运行状态」里的下次推送时间，再检查上面四项。")
            }
        }

        CopyrightFooter()
    }
}

@Composable
private fun GuideItem(
    title: String,
    ok: Boolean?,
    okText: String,
    okDetail: String,
    failText: String,
    actionText: String,
    onAction: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (ok == true) {
                    Text(
                        okText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (ok == true) okDetail else failText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ok != true) {
                    Button(onClick = onAction) { Text(actionText) }
                }
                OutlinedButton(onClick = onAction) { Text("重新检查") }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(bottom = 6.dp)) {
        Text("· ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
