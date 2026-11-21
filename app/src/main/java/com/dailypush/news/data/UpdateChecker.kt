package com.dailypush.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.io.File

/**
 * 检查更新：从 GitHub 仓库的 Releases 拿最新版本，可以在 App 内直接下载安装。
 *
 * 约定（发布新版本时照着做）：
 *  1. 在仓库里建一个 Release，tag 用 `v1.6.0` 这种格式；
 *  2. 往 Release 里传一个 **.apk 附件**（正式签名包，和本 App 同一个密钥 → 可覆盖安装）；
 *  3. App 就会在「关于」页显示新版本并提供「下载并安装」。
 *
 * 仓库还没上传 / 没有 Release 时会返回 404，这里翻译成一句人话，不弹堆栈。
 */
object UpdateChecker {

    /** GitHub 仓库 */
    const val REPO = "Ushuguang/DailyNews"

    /** 仓库主页 */
    const val REPO_URL = "https://github.com/$REPO"

    /** 最新 Release 接口 */
    const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** Release 页面（打不开下载时用它兜底） */
    const val RELEASE_PAGE = "$REPO_URL/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    /** 一个可安装的版本 */
    data class Release(
        /** 版本号，不带 v，例如 1.6.0 */
        val version: String,
        /** tag 原样，例如 v1.6.0 */
        val tag: String,
        /** 更新说明 */
        val notes: String,
        /** 安装包下载地址（Release 里的 .apk 附件） */
        val apkUrl: String,
        /** Release 页面 */
        val pageUrl: String,
        /** 发布时间 */
        val publishedAt: String
    )

    /**
     * 查最新版本。
     *
     * 优先走 GitHub 官方 API；**API 被限流（共享出口 IP 很容易 403）时自动改走网页**，
     * 从 `releases/latest` 的跳转地址里取版本号，再去 expanded_assets 页面找 .apk。
     *
     * 失败时给出能直接显示给用户的中文原因（仓库不存在 / 没有发布版本 / 网络不通）。
     */
    suspend fun check(): Result<Release> = withContext(Dispatchers.IO) {
        runCatching {
            val api = runCatching { checkByApi() }
            api.getOrElse { apiError ->
                // API 失败（限流 / 仓库还没建 / 没有 Release）→ 退到网页方式再试一次
                runCatching { checkByWeb() }.getOrElse { throw apiError }
            }
        }
    }

    /** 方式一：GitHub 官方 API */
    private fun checkByApi(): Release {
        val request = Request.Builder()
            .url(API_LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "DailyNews-Android")
            .build()

        return Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(friendlyError(response.code, body))
            }
            parse(body)
        }
    }

    /** 方式二：网页（不受 API 限流影响） */
    private fun checkByWeb(): Release {
        val request = Request.Builder()
            .url("$REPO_URL/releases/latest")
            .header("User-Agent", Http.UA)
            .build()

        return Http.client.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val tag = finalUrl.substringAfterLast("/releases/tag/").substringAfterLast("/")
            if (tag.isBlank() || finalUrl.endsWith("/releases") || finalUrl.endsWith("/releases/latest")) {
                throw IllegalStateException("仓库 $REPO 还没有发布版本（建一个 Release 并传上 .apk 就能更新了）")
            }
            val version = tag.removePrefix("v").trim()
            if (version.isBlank()) throw IllegalStateException("这个 Release 没有版本号")

            // 附件列表在 expanded_assets 里
            var apkUrl = ""
            runCatching {
                val assetsRequest = Request.Builder()
                    .url("$REPO_URL/releases/expanded_assets/$tag")
                    .header("User-Agent", Http.UA)
                    .build()
                Http.client.newCall(assetsRequest).execute().use { assetsResponse ->
                    val html = assetsResponse.body?.string().orEmpty()
                    val match = Regex("/Ushuguang/DailyNews/releases/download/[^\\\"']+?\\.apk").find(html)
                    if (match != null) apkUrl = "https://github.com" + match.value
                }
            }

            Release(
                version = version,
                tag = tag,
                notes = "",
                apkUrl = apkUrl,
                pageUrl = finalUrl,
                publishedAt = ""
            )
        }
    }

    /** 把 GitHub 的错误翻译成人话 */
    private fun friendlyError(code: Int, body: String): String = when (code) {
        404 -> "仓库 $REPO 还没有发布版本（可能仓库还没上传，或还没有建 Release）"
        403 -> "访问 GitHub 太频繁或被限流了，过一会儿再试"
        401 -> "GitHub 要求登录才能读这个仓库"
        else -> {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull()
            if (!message.isNullOrBlank()) "检查更新失败：$message" else "检查更新失败：HTTP $code"
        }
    }

    /** 解析 Release JSON */
    private fun parse(body: String): Release {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
            .ifBlank { root["name"]?.jsonPrimitive?.content.orEmpty() }
        val version = tag.removePrefix("v").trim()
        if (version.isBlank()) throw IllegalStateException("这个 Release 没有版本号")

        // 附件里找 .apk
        var apkUrl = ""
        var published = ""
        val assets = root["assets"]
        if (assets is kotlinx.serialization.json.JsonArray) {
            assets.jsonArray.forEach { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                val url = obj["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                if (apkUrl.isBlank() && name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    apkUrl = url
                }
            }
        }
        published = root["published_at"]?.jsonPrimitive?.content.orEmpty()

        return Release(
            version = version,
            tag = tag,
            notes = root["body"]?.jsonPrimitive?.content.orEmpty().trim(),
            apkUrl = apkUrl,
            pageUrl = root["html_url"]?.jsonPrimitive?.content.orEmpty().ifBlank { RELEASE_PAGE },
            publishedAt = published
        )
    }

    /**
     * 比较版本号：远端比本地新就返回 true。
     *
     * 只比数字段（1.6.0 > 1.5.9），`v` 前缀无所谓，多余的段按 0 处理。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote)
        val l = parseVersion(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun parseVersion(text: String): List<Int> =
        text.trim().removePrefix("v").split(".").map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }

    /**
     * 下载安装包到 `filesDir/updates/`。
     *
     * @param onProgress 已下载字节 / 总字节（总字节未知时为 -1）
     */
    suspend fun download(
        context: android.content.Context,
        url: String,
        fileName: String,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, fileName)
        val temp = File(dir, fileName + ".part")

        val request = Request.Builder().url(url).header("User-Agent", "DailyNews-Android").build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("下载失败：HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("下载失败：响应为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target
    }
}
