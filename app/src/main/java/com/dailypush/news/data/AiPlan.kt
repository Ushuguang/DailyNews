package com.dailypush.news.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AI 根据用户自然语言需求解析出来的推送方案。
 */
@Serializable
data class AiPlan(
    /** 推送时间点（24 小时制 HH:mm），一天可以推多次 */
    val pushTimes: List<String> = listOf("08:00"),
    /** 每条推送里的新闻条数 */
    val maxItems: Int = 8,
    /** 内容分类，空表示不限 */
    val categories: List<String> = emptyList(),
    /** 关键词，空表示不过滤 */
    val keywords: List<String> = emptyList(),
    /** 是否附带热搜 */
    val includeHot: Boolean = false,
    /** summary / items / both */
    val notifyStyle: String = "summary",
    /** AI 的一句话解释 */
    val note: String = ""
) {
    /** 第一个时间点（兼容只关心单时间的旧逻辑） */
    val pushTime: String get() = pushTimes.firstOrNull() ?: "08:00"

    fun hour(): Int = pushTime.substringBefore(":").toIntOrNull()?.coerceIn(0, 23) ?: 8
    fun minute(): Int = pushTime.substringAfter(":").toIntOrNull()?.coerceIn(0, 59) ?: 0

    /** 规范化：排序、去重、格式统一，最多保留 MAX_PUSH_TIMES 个 */
    fun normalizedTimes(): List<String> = pushTimes
        .map { it.trim() }
        .filter { it.matches(Regex("\\d{1,2}:\\d{2}")) }
        .map {
            val h = it.substringBefore(":").toIntOrNull()?.coerceIn(0, 23) ?: 8
            val m = it.substringAfter(":").toIntOrNull()?.coerceIn(0, 59) ?: 0
            "%02d:%02d".format(h, m)
        }
        .distinct()
        .sorted()
        .take(MAX_PUSH_TIMES)

    companion object {
        /** 一天最多设几个推送时间点 */
        const val MAX_PUSH_TIMES = 6
    }
}
