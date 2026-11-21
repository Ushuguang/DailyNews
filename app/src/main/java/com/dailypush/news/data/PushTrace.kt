package com.dailypush.news.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** 痕迹里的一条新闻记录 */
@Serializable
data class TraceItem(
    val id: String,
    val title: String,
    val source: String,
    val link: String,
    val summary: String = "",
    val publishedAt: Long = 0L,
    /** 最终是否被选中推送 */
    val selected: Boolean = false,
    /** 为什么选 / 为什么不选 */
    val reason: String = "",
    /** 被合并到哪一条（被合并项才有值） */
    val mergedInto: String = "",
    /** 被哪一条合并（代表项才有值） */
    val mergedFrom: List<String> = emptyList(),
    /** 该新闻所属的话题（合并组） */
    val topic: String = "",
    /** page = 具体内容页；search = 站内搜索（平台只给了关键词） */
    val linkKind: String = "page"
)

/** 一次推送的完整决策痕迹 */
@Serializable
data class PushTrace(
    val date: String,
    val pushedAt: Long,
    /** 送进 AI 挑选的候选池 */
    val candidates: List<TraceItem> = emptyList(),
    /** 在筛选阶段就被剔除的（分类不符 / 关键词没命中） */
    val filteredOut: List<TraceItem> = emptyList(),
    /** 实际推送出去的条目 id（有序） */
    val selectedIds: List<String> = emptyList(),
    /** AI 给的一句话总结 */
    val note: String = "",
    /** 这次用的筛选条件描述 */
    val filterSummary: String = "",
    /** 是否真的调用了 AI 做合并 */
    val aiUsed: Boolean = false,
    /** AI 合并失败的原因（成功则为空） */
    val aiError: String = ""
) {
    val selectedItems: List<TraceItem> get() = selectedIds.mapNotNull { id -> candidates.find { it.id == id } }
}

/**
 * 痕迹存储：每天一个文件，放 filesDir/traces/YYYY-MM-DD.json。
 * 只保留最近 30 天。
 */
object PushTraceStore {

    private const val DIR = "traces"
    private const val KEEP_DAYS = 30

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    fun save(context: Context, trace: PushTrace) {
        val file = File(dir(context), "${trace.date}.json")
        runCatching { file.writeText(json.encodeToString(trace)) }
        prune(context)
    }

    fun load(context: Context, date: String): PushTrace? {
        val file = File(dir(context), "$date.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<PushTrace>(file.readText()) }.getOrNull()
    }

    fun today(context: Context): PushTrace? = load(context, LocalDate.now().toString())

    /** 最近有记录的日期，从新到旧 */
    fun dates(context: Context): List<String> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sortedDescending()
            ?: emptyList()

    private fun prune(context: Context) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?: return
        files.drop(KEEP_DAYS).forEach { it.delete() }
    }
}
