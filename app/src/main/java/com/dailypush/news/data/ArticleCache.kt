package com.dailypush.news.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 新闻本地缓存（文件 + JSON） */
object ArticleCache {

    private const val FILE_NAME = "articles.json"
    private const val MAX_CACHED = 800

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(context: Context): List<Article> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString<List<Article>>(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun save(context: Context, articles: List<Article>) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, FILE_NAME)
        runCatching { file.writeText(json.encodeToString(articles.take(MAX_CACHED))) }
        Unit
    }
}
