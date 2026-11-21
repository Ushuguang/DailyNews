package com.dailypush.news.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "daily_news_settings")

/** 通知样式 */
object NotifyStyle {
    /** 只发一条汇总通知 */
    const val SUMMARY = "summary"

    /** 每条新闻各发一条通知 */
    const val ITEMS = "items"

    /** 汇总 + 逐条都发 */
    const val BOTH = "both"

    fun label(value: String): String = when (value) {
        ITEMS -> "逐条通知"
        BOTH -> "汇总 + 逐条"
        else -> "汇总通知"
    }
}

/** 全部设置项 */
data class AppSettings(
    val pushEnabled: Boolean = true,
    /** 推送时间点列表（HH:mm），一天可以推多次 */
    val pushTimes: List<String> = listOf(DEFAULT_PUSH_TIME),
    val notifyStyle: String = NotifyStyle.SUMMARY,
    val maxItems: Int = 8,
    val feeds: List<Feed> = DefaultFeeds.all,
    val lastPushDate: String = "",
    val lastPushAt: Long = 0L,
    val lastFetchSummary: String = "",
    /** AI 筛选：只推这些分类，空表示不限 */
    val pushCategories: List<String> = emptyList(),
    /** AI 筛选：标题含这些关键词才推，空表示不过滤 */
    val pushKeywords: List<String> = emptyList(),
    /** 推送里是否包含热搜 */
    val includeHot: Boolean = true,
    /** 推送时是否用 AI 合并重复报道 */
    val aiMerge: Boolean = true,
    /** AI 上次解析出来的说明 */
    val aiNote: String = "",
    /** 上次推送是哪个「日期@时间点」，用来保证同一时间点一天只推一次 */
    val lastPushSlot: String = "",
    /** AI 接口地址 */
    val aiBaseUrl: String = AiClient.DEFAULT_BASE_URL,
    /** AI 模型 */
    val aiModel: String = AiClient.DEFAULT_MODEL
) {
    /** 第一个推送时间点的小时（兼容旧代码） */
    val pushHour: Int get() = pushTimes.firstOrNull()?.substringBefore(":")?.toIntOrNull() ?: 8

    /** 第一个推送时间点的分钟（兼容旧代码） */
    val pushMinute: Int get() = pushTimes.firstOrNull()?.substringAfter(":")?.toIntOrNull() ?: 0

    companion object {
        const val DEFAULT_PUSH_TIME = "08:00"
        /** 一天最多几个推送时间点 */
        const val MAX_PUSH_TIMES = 6
    }
}

private fun String.toList(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotEmpty() }

class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settingsFlow: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        val feeds = prefs[Keys.FEEDS]?.let { raw ->
            runCatching { json.decodeFromString<List<Feed>>(raw) }.getOrNull()
        } ?: DefaultFeeds.all

        AppSettings(
            pushEnabled = prefs[Keys.PUSH_ENABLED] ?: true,
            pushTimes = readPushTimes(prefs),
            notifyStyle = prefs[Keys.NOTIFY_STYLE] ?: NotifyStyle.SUMMARY,
            maxItems = prefs[Keys.MAX_ITEMS] ?: 8,
            feeds = feeds,
            lastPushDate = prefs[Keys.LAST_PUSH_DATE] ?: "",
            lastPushAt = prefs[Keys.LAST_PUSH_AT] ?: 0L,
            lastFetchSummary = prefs[Keys.LAST_FETCH_SUMMARY] ?: "",
            pushCategories = prefs[Keys.PUSH_CATEGORIES].orEmpty().toList(),
            pushKeywords = prefs[Keys.PUSH_KEYWORDS].orEmpty().toList(),
            includeHot = prefs[Keys.INCLUDE_HOT] ?: true,
            aiMerge = prefs[Keys.AI_MERGE] ?: true,
            aiNote = prefs[Keys.AI_NOTE] ?: "",
            lastPushSlot = prefs[Keys.LAST_PUSH_SLOT] ?: "",
            aiBaseUrl = prefs[Keys.AI_BASE_URL] ?: AiClient.DEFAULT_BASE_URL,
            aiModel = prefs[Keys.AI_MODEL] ?: AiClient.DEFAULT_MODEL
        )
    }

    suspend fun setPushEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.PUSH_ENABLED] = enabled }
    }

    /** 读推送时间点：新 key 优先，老的 hour/minute 自动迁移过来 */
    private fun readPushTimes(prefs: Preferences): List<String> {
        val raw = prefs[Keys.PUSH_TIMES]?.toList()
        if (!raw.isNullOrEmpty()) return raw.take(AppSettings.MAX_PUSH_TIMES)
        val hour = prefs[Keys.PUSH_HOUR] ?: 8
        val minute = prefs[Keys.PUSH_MINUTE] ?: 0
        return listOf("%02d:%02d".format(hour, minute))
    }

    /** 设置推送时间点（一天可多个） */
    suspend fun setPushTimes(times: List<String>) {
        val cleaned = times
            .map { it.trim() }
            .filter { it.matches(Regex("\\d{1,2}:\\d{2}")) }
            .map {
                val h = it.substringBefore(":").toIntOrNull()?.coerceIn(0, 23) ?: 8
                val m = it.substringAfter(":").toIntOrNull()?.coerceIn(0, 59) ?: 0
                "%02d:%02d".format(h, m)
            }
            .distinct()
            .sorted()
            .take(AppSettings.MAX_PUSH_TIMES)
        context.settingsStore.edit {
            it[Keys.PUSH_TIMES] = cleaned.joinToString(",")
            // 同步老的单时间字段，别的地方（兼容代码）还能读到
            val first = cleaned.firstOrNull() ?: AppSettings.DEFAULT_PUSH_TIME
            it[Keys.PUSH_HOUR] = first.substringBefore(":").toIntOrNull() ?: 8
            it[Keys.PUSH_MINUTE] = first.substringAfter(":").toIntOrNull() ?: 0
        }
    }

    suspend fun setPushTime(hour: Int, minute: Int) {
        setPushTimes(listOf("%02d:%02d".format(hour, minute)))
    }

    suspend fun setNotifyStyle(style: String) {
        context.settingsStore.edit { it[Keys.NOTIFY_STYLE] = style }
    }

    suspend fun setMaxItems(value: Int) {
        context.settingsStore.edit { it[Keys.MAX_ITEMS] = value.coerceIn(3, 40) }
    }

    suspend fun setFeeds(feeds: List<Feed>) {
        context.settingsStore.edit { it[Keys.FEEDS] = json.encodeToString(feeds) }
    }

    suspend fun setAiMerge(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.AI_MERGE] = enabled }
    }

    suspend fun setAiConfig(baseUrl: String, model: String) {
        context.settingsStore.edit {
            it[Keys.AI_BASE_URL] = baseUrl.trim()
            it[Keys.AI_MODEL] = model.trim().ifBlank { AiClient.DEFAULT_MODEL }
        }
    }

    suspend fun setAiPlan(
        categories: List<String>,
        keywords: List<String>,
        includeHot: Boolean,
        note: String
    ) {
        context.settingsStore.edit {
            it[Keys.PUSH_CATEGORIES] = categories.joinToString(",")
            it[Keys.PUSH_KEYWORDS] = keywords.joinToString(",")
            it[Keys.INCLUDE_HOT] = includeHot
            it[Keys.AI_NOTE] = note
        }
    }

    suspend fun setLastFetchSummary(summary: String) {
        context.settingsStore.edit { it[Keys.LAST_FETCH_SUMMARY] = summary }
    }

    suspend fun markPushed(date: String, at: Long, slot: String = "") {
        context.settingsStore.edit {
            it[Keys.LAST_PUSH_SLOT] = slot
            it[Keys.LAST_PUSH_DATE] = date
            it[Keys.LAST_PUSH_AT] = at
        }
    }

    private object Keys {
        val PUSH_ENABLED = booleanPreferencesKey("push_enabled")
        val PUSH_TIMES = stringPreferencesKey("push_times")
        val PUSH_HOUR = intPreferencesKey("push_hour")
        val PUSH_MINUTE = intPreferencesKey("push_minute")
        val NOTIFY_STYLE = stringPreferencesKey("notify_style")
        val MAX_ITEMS = intPreferencesKey("max_items")
        val FEEDS = stringPreferencesKey("feeds_json")
        val LAST_PUSH_DATE = stringPreferencesKey("last_push_date")
        val LAST_PUSH_AT = longPreferencesKey("last_push_at")
        val LAST_PUSH_SLOT = stringPreferencesKey("last_push_slot")
        val LAST_FETCH_SUMMARY = stringPreferencesKey("last_fetch_summary")
        val PUSH_CATEGORIES = stringPreferencesKey("push_categories")
        val PUSH_KEYWORDS = stringPreferencesKey("push_keywords")
        val INCLUDE_HOT = booleanPreferencesKey("include_hot")
        val AI_MERGE = booleanPreferencesKey("ai_merge")
        val AI_NOTE = stringPreferencesKey("ai_note")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_MODEL = stringPreferencesKey("ai_model")
    }
}
