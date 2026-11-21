package com.dailypush.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 统一的 HTTP 客户端。
 *
 * 用桌面版 Chrome UA：实测微博热搜、B站热搜用 Android UA 会 403，换桌面 UA 才通。
 */
object Http {

    /** 桌面 UA：微博/B站的热榜接口用移动端 UA 会 403 */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** 移动端 UA：少数派等站点用桌面 UA 会返回 HTML 而不是订阅源 */
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun getText(
        url: String,
        referer: String? = null,
        mobile: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", if (mobile) MOBILE_UA else UA)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8"
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        if (referer != null) builder.header("Referer", referer)

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw IllegalStateException("响应内容为空")
            decode(bytes)
        }
    }

    /** 优先按 UTF-8 解码；出现乱码替换符则回退 GBK（部分老站点仍是 GBK） */
    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('�')) return utf8
        return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
    }
}
