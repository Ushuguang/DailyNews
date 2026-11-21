package com.dailypush.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 调用大模型接口，把用户的一句话需求解析成 [AiPlan]。
 *
 * 兼容两种端点：
 *  1. OpenAI 兼容：{base}/chat/completions
 *  2. MiniMax 原生：{base}/text/chatcompletion_v2
 * 默认 MiniMax 国内站 https://api.minimaxi.com/v1，海外站把 base 换成 https://api.minimax.io/v1 即可。
 */
object AiClient {

    const val DEFAULT_BASE_URL = "https://api.minimaxi.com/v1"
    const val DEFAULT_MODEL = "MiniMax-M3.1-Flash"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * AI 请求专用客户端。
     * M1/M2/M3 是推理模型，思考时间长，用抓新闻那套 30 秒超时会经常被掐断，
     * 这里把读超时放宽到 150 秒。
     */
    private val aiHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** App 里可选的内容分类 */
    val categories = listOf("时政", "要闻", "国际", "财经", "社会", "科技", "生活", "游戏")

    /** 可选的模型（官方 chatcompletion_v2 当前支持 MiniMax-M3.1-Flash 与 MiniMax-M3，老模型也仍可用） */
    val suggestedModels = listOf(
        "MiniMax-M3.1-Flash",
        "MiniMax-M3",
        "MiniMax-M2",
        "MiniMax-M1",
        "MiniMax-Text-01"
    )

    // ---------------- 对外接口 ----------------

    /**
     * 统一的请求体构造。
     *
     * 关键：**messages 里必须有一条 content 非空的 user 消息**。
     * MiniMax 网关只认 user 消息里的内容作为「chat content」，
     * 只发 system 消息（或 user 的 content 为空）会直接被拒：
     * `invalid params, chat content is empty (2013)`。
     */
    internal fun buildBody(
        model: String,
        system: String,
        user: String,
        temperature: Double? = null,
        maxTokens: Int? = null
    ): JsonObject = bodyFor(
        Flavor.OPENAI,
        Spec(model, system, user, temperature, maxTokens)
    )

    /** 测试连通性 */
    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("还没填 API Key"))
        // 注意：必须带一条 user 消息，否则 MiniMax 会报 2013 chat content is empty
        val spec = Spec(
            model = model,
            system = "你是一个连通性测试助手，请用一句简短的中文回应。",
            user = "你好，请回复「连接成功」。",
            maxTokens = 64
        )
        runCatching {
            val reply = call(baseUrl, apiKey, spec)
            val modelName = reply.first
            val content = reply.second
            if (content.isBlank()) "已连接 $modelName，但模型返回了空内容" else "连接成功（$modelName）"
        }
    }

    /** 把自然语言需求解析成配置方案 */
    suspend fun parsePlan(
        baseUrl: String,
        apiKey: String,
        model: String,
        requirement: String,
        currentPlan: AiPlan
    ): Result<AiPlan> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("还没填 API Key"))
        }
        if (requirement.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("先写下你想要什么内容"))
        }

        val spec = Spec(
            model = model,
            system = systemPrompt(currentPlan),
            user = requirement,
            temperature = 0.2,
            // 推理模型的思考 token 也算在这个上限里，1024 会让 JSON 写到一半被截断
            maxTokens = 8192
        )

        runCatching {
            var content = call(baseUrl, apiKey, spec).second
            var plan = extractJson(content)
            if (plan == null) {
                // 第一次没给成合法 JSON，换个更严格的说法再来一次
                val retry = call(
                    baseUrl,
                    apiKey,
                    spec.copy(
                        system = systemPrompt(currentPlan) +
                            "\n\n【重要】上一条回复无法被解析成 JSON。这一次请只输出一个 JSON 对象，" +
                            "第一个字符必须是 {，最后一个字符必须是 }，中间不要有任何解释文字，也不要用代码块。",
                        user = requirement + "\n\n（只输出 JSON 对象）",
                        maxTokens = (spec.maxTokens ?: 8192) * 2
                    )
                )
                content = retry.second
                plan = extractJson(content)
            }
            if (plan == null) {
                throw IllegalStateException(
                    "模型没有返回可解析的 JSON（原始回复前 300 字）：\n${content.take(300)}"
                )
            }
            plan
        }
    }

    /**
     * 让 AI 判断哪些新闻在讲同一件事，把重复报道合并成一条。
     * 候选里传的是序号（从 1 开始），模型好对齐；返回后我再转回 0 基索引。
     */
    suspend fun mergeSimilar(
        baseUrl: String,
        apiKey: String,
        model: String,
        candidates: List<Article>
    ): Result<MergeOutcome> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("还没填 API Key，无法合并"))
        }
        if (candidates.size < 2) {
            return@withContext Result.success(MergeOutcome(emptyList(), "候选不足两条，无需合并"))
        }

        val list = candidates.mapIndexed { index, article ->
            "${index + 1}. [${article.source}] ${article.title}"
        }.joinToString("\n")

        val spec = Spec(
            model = model,
            system = mergePrompt(),
            user = "待合并的新闻列表：\n$list",
            temperature = 0.2,
            maxTokens = 8192
        )

        runCatching {
            var content = call(baseUrl, apiKey, spec).second
            var dto = extractMerge(content)
            if (dto == null) {
                val retry = call(
                    baseUrl,
                    apiKey,
                    spec.copy(
                        system = mergePrompt() +
                            "\n\n【重要】上一条回复无法被解析成 JSON。这一次请只输出一个 JSON 对象，" +
                            "第一个字符必须是 {，最后一个字符必须是 }。",
                        user = "待合并的新闻列表：\n$list\n\n（只输出 JSON 对象）",
                        maxTokens = (spec.maxTokens ?: 8192) * 2
                    )
                )
                content = retry.second
                dto = extractMerge(content)
            }
            dto ?: throw IllegalStateException(
                "模型没有返回可解析的 JSON（原始回复前 300 字）：\n${content.take(300)}"
            )
            MergeOutcome(
                groups = dto.groups.mapNotNull { group ->
                    val rep = group.representative - 1
                    val members = group.members.map { it - 1 }.filter { it in candidates.indices }
                    if (rep !in candidates.indices) return@mapNotNull null
                    MergeGroup(
                        topic = group.topic,
                        representativeIndex = rep,
                        memberIndexes = (members + rep).distinct(),
                        reason = group.reason
                    )
                },
                note = dto.note
            )
        }
    }

    /** 从回复里抠出合并结果的 JSON（同样走宽松解析 + 截断修复） */
    internal fun extractMerge(content: String): MergeDto? {
        val obj = findJsonObject(content) ?: return null
        val groups = obj["groups"]?.jsonArray.orEmpty().mapNotNull { item ->
            val group = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            fun str(key: String): String =
                runCatching { group[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
            fun int(key: String): Int? =
                runCatching { group[key]?.jsonPrimitive?.intOrNull }.getOrNull()
                    ?: runCatching { group[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull()
            val representative = int("representative") ?: return@mapNotNull null
            val members = group["members"]?.jsonArray.orEmpty().mapNotNull { m ->
                runCatching { m.jsonPrimitive.intOrNull }.getOrNull()
                    ?: runCatching { m.jsonPrimitive.contentOrNull?.toIntOrNull() }.getOrNull()
            }
            MergeGroupDto(
                topic = str("topic"),
                representative = representative,
                members = members,
                reason = str("reason")
            )
        }
        val note = runCatching { obj["note"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
        return MergeDto(groups = groups, note = note)
    }

    private fun mergePrompt(): String {
        return """
你是一个新闻编辑，负责把「多家媒体报道同一件事」的重复新闻合并成一条，避免同一件事在推送里出现多次。

输入是一串带序号（从 1 开始）的新闻列表。请判断哪些条目在讲同一个事件或同一件事的不同角度。

严格要求：
1. 只输出一个 JSON 对象，不要输出解释、不要用 Markdown 代码块。
2. JSON 结构：
{
  "groups": [
    {
      "topic": "这一组新闻共同讲的事件，一句话概括",
      "representative": 3,
      "members": [1, 3, 7],
      "reason": "为什么它们是同一件事，以及为什么选第 3 条作为代表"
    }
  ],
  "note": "一句话总结这次合并情况，例如：共 4 组重复报道，合并后从 20 条减少到 12 条"
}

判断规则：
- 只有确实是同一事件/同一新闻的报道才放进一组；同一话题但不同事件不算重复
- 每个组里 representative 必须是 members 中的一个，且要选**信息最完整、最权威**的那条（一般优先中央级媒体、有完整标题的）
- 代表条目用「代表原文」，其他成员用「换一种来源」
- 没有重复的条目不要出现在任何组里，它们会被单独保留
- groups 里只放确实重复的组；一条都没重复就返回空数组

示例输入：
1. [中新网·滚动] 习近平会见智利总统卡斯特
2. [新华社·要闻] 习近平会见智利总统卡斯特
3. [IT之家] 某手机新品发布

示例输出：
{"groups":[{"topic":"习近平会见智利总统卡斯特","representative":2,"members":[1,2],"reason":"两家媒体报道的同一次会见，标题基本一致，保留新华社稿件"}],"note":"合并了 1 组重复报道，从 3 条减少到 2 条"}
""".trimIndent()
    }

    // ---------------- 内部 ----------------

    /** 兼容层：同一套模型有两套协议 */
    internal enum class Flavor { OPENAI, ANTHROPIC }

    private fun flavorOf(endpoint: String): Flavor =
        if (endpoint.contains("/anthropic/")) Flavor.ANTHROPIC else Flavor.OPENAI

    /** 一次请求要带的东西 */
    internal data class Spec(
        val model: String,
        val system: String,
        val user: String,
        val temperature: Double? = null,
        val maxTokens: Int? = null
    )

    /**
     * 发起请求。失败时如果怀疑是「token 上限参数不被当前模型接受」，
     * 就去掉这个参数再试一次（不同版本的接口对这个参数的支持不一致）。
     */
    private fun call(baseUrl: String, apiKey: String, spec: Spec): Pair<String, String> {
        val first = callOnce(baseUrl, apiKey, spec)
        if (first.isSuccess) return first.getOrThrow()

        val message = first.exceptionOrNull()?.message.orEmpty()
        val looksLikeTokenParamIssue =
            (message.contains("max_completion_tokens") || message.contains("max_tokens")) &&
                (message.contains("param") || message.contains("invalid") ||
                    message.contains("unknown") || message.contains("不支持") || message.contains("参数"))
        if (looksLikeTokenParamIssue && spec.maxTokens != null) {
            val second = callOnce(baseUrl, apiKey, spec.copy(maxTokens = null))
            if (second.isSuccess) return second.getOrThrow()
            throw IllegalStateException(
                (second.exceptionOrNull()?.message ?: "请求失败") +
                    "\n（提示：已去掉 token 上限参数重试过一次）"
            )
        }
        throw first.exceptionOrNull() ?: IllegalStateException("请求失败")
    }

    /** 返回 (模型名, 回复内容) */
    private fun callOnce(baseUrl: String, apiKey: String, spec: Spec): Result<Pair<String, String>> {
        val failures = mutableListOf<String>()

        for (endpoint in endpoints(baseUrl)) {
            try {
                val flavor = flavorOf(endpoint)
                val payload = bodyFor(flavor, spec).toString().toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(payload)
                    .build()

                aiHttp.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val detail = extractError(text).ifBlank { text.take(200) }
                        throw IllegalStateException(
                            "HTTP ${response.code}：$detail" + hintFor(detail)
                        )
                    }
                    val root = json.parseToJsonElement(text).jsonObject
                    checkApiError(root)
                    return Result.success(parseReply(flavor, root))
                }
            } catch (e: Throwable) {
                failures.add("${shortEndpoint(endpoint)} → ${e.message ?: e.javaClass.simpleName}")
            }
        }
        // 所有端点都失败时，把每个端点的错误都列出来，方便定位是哪一步的问题
        val detail = failures.joinToString("\n")
        val hint = hintFor(failures.joinToString(" "))
        return Result.failure(
            IllegalStateException("所有接口都尝试失败：\n$detail$hint")
        )
    }

    /** 按协议拼请求体 */
    internal fun bodyFor(flavor: Flavor, spec: Spec): JsonObject {
        val modelName = spec.model.ifBlank { DEFAULT_MODEL }
        return when (flavor) {
            Flavor.OPENAI -> buildJsonObject {
                put("model", modelName)
                put("stream", false)
                // 推理模型（M1/M2/M3）对 temperature 有额外要求，官方推荐用默认值，
                // 传非 1 的值容易被判为参数非法，所以干脆不发。
                if (!isReasoningModel(modelName)) {
                    spec.temperature?.let { put("temperature", it) }
                }
                spec.maxTokens?.let {
                    // 新模型用 max_completion_tokens，老模型沿用 max_tokens（官方已把 max_tokens 标为废弃）
                    if (modelName.contains("M3")) {
                        put("max_completion_tokens", it)
                    } else {
                        put("max_tokens", it)
                    }
                }
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", spec.system)
                        }
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", spec.user.ifBlank { "你好" })
                        }
                    )
                }
            }

            Flavor.ANTHROPIC -> buildJsonObject {
                put("model", modelName)
                put("stream", false)
                // Anthropic 兼容层要求必须给 max_tokens
                put("max_tokens", spec.maxTokens ?: 4096)
                if (!isReasoningModel(modelName)) {
                    spec.temperature?.let { put("temperature", it) }
                }
                put("system", spec.system)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", spec.user.ifBlank { "你好" })
                        }
                    )
                }
            }
        }
    }

    /** 挑出接口自己的错误（MiniMax 两种协议的错误位置不一样） */
    private fun checkApiError(root: JsonObject) {
        root["base_resp"]?.jsonObject?.let { base ->
            val code = base["status_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (code != 0) {
                val msg = base["status_msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
                throw IllegalStateException(
                    "接口报错(${code})：$msg" + hintFor("($code) $msg")
                )
            }
        }
        root["error"]?.jsonObject?.let { err ->
            val msg = err["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (msg.isNotBlank()) {
                throw IllegalStateException("接口报错：$msg" + hintFor(msg))
            }
        }
    }

    /** 从返回里取出 (模型名, 正文) */
    private fun parseReply(flavor: Flavor, root: JsonObject): Pair<String, String> {
        val model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (flavor) {
            Flavor.OPENAI -> {
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw IllegalStateException("返回里没有 choices")
                val message = choice["message"]?.jsonObject
                    ?: throw IllegalStateException("返回里没有 message")
                val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                model to content
            }

            Flavor.ANTHROPIC -> {
                val blocks = root["content"]?.jsonArray
                    ?: throw IllegalStateException("返回里没有 content")
                val text = blocks.mapNotNull { block ->
                    val obj = block.jsonObject
                    if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                        obj["text"]?.jsonPrimitive?.contentOrNull
                    } else {
                        null
                    }
                }.joinToString("\n")
                model to text
            }
        }
    }

    /**
     * 列出这个 Key 实际能用的模型。
     * 官方文档：GET {base}/models（Bearer），GET {base}/anthropic/v1/models（X-Api-Key）。
     * 这是判断「Key 是否有效 / 属于哪个地区 / 模型名怎么写」最快的办法。
     */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("还没填 API Key"))
            }
            val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
            val bases = buildList {
                add(base)
                if (!base.endsWith("/v1")) add("$base/v1")
            }
            val urls = mutableListOf<String>()
            bases.forEach { b ->
                urls.add("$b/models")
                urls.add("$b/anthropic/v1/models")
            }
            val failures = mutableListOf<String>()
            for (url in urls.distinct()) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $apiKey")
                        .header("X-Api-Key", apiKey)
                        .get()
                        .build()
                    val response = aiHttp.newCall(request).execute()
                    val text = response.body?.string().orEmpty()
                    val code = response.code
                    response.close()
                    if (code in 200..299) {
                        val ids = runCatching {
                            json.parseToJsonElement(text).jsonObject["data"]?.jsonArray.orEmpty()
                                .mapNotNull { item -> item.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                                .filter { it.isNotBlank() }
                        }.getOrDefault(emptyList())
                        if (ids.isNotEmpty()) {
                            return@withContext Result.success(ids.distinct())
                        }
                    }
                    failures.add("${shortEndpoint(url)} → HTTP $code")
                } catch (e: Throwable) {
                    failures.add("${shortEndpoint(url)} → ${e.message ?: e.javaClass.simpleName}")
                }
            }
            Result.failure(
                IllegalStateException(
                    "没能取到模型列表：\n" + failures.joinToString("\n") +
                        "\n提示：Key 无效、地区不匹配（国内 api.minimaxi.com / 海外 api.minimax.io），" +
                        "或者这个地址不支持 /models，都可以导致取不到。"
                )
            )
        }

    /** 常见错误码的中文提示 */
    internal fun hintFor(detail: String): String {
        val d = detail.lowercase()
        return when {
            d.contains("2013") && d.contains("chat content is empty") ->
                "\n提示：请求内容为空。MiniMax 要求 messages 里必须有一条 content 非空的 user 消息。"
            d.contains("2013") && d.contains("role") ->
                "\n提示：MiniMax 要求 system 消息必须在第一条且只能有一条。"
            d.contains("2013") ->
                "\n提示：参数不合法，请检查模型名是否正确（可用「查看可用模型」确认）。"
            d.contains("1008") || d.contains("2056") || d.contains("402") ->
                "\n提示：这个 Key 所属账户的资源不足。MiniMax 有两套互不通用的 Key：" +
                    "\n· sk-api- 开头的按量付费 Key，扣账户余额（余额不足要去控制台充值）；" +
                    "\n· sk-cp- 开头的 Token Plan 订阅 Key，扣套餐额度（额度按 5 小时滚动窗口重置，套餐到期或没分配席位就会报余额不足）。" +
                    "\n如果这个 Key 在别的平台能用，把「接口地址」和「API Key」换成那个平台的值即可，本 App 支持任何 OpenAI 兼容接口。"
            d.contains("1004") || d.contains("2049") || d.contains("401") ->
                "\n提示：API Key 无效、已过期，或与接口地址地区不匹配。" +
                    "\n国内站：platform.minimaxi.com ↔ https://api.minimaxi.com/v1" +
                    "\n海外站：platform.minimax.io ↔ https://api.minimax.io/v1" +
                    "\n两站的 Key 不能互相使用。"
            d.contains("1002") || d.contains("429") ->
                "\n提示：请求过于频繁触发限流，请稍后再试。"
            d.contains("1026") || d.contains("422") ->
                "\n提示：请求内容命中敏感词，请换个说法。"
            d.contains("model") && (d.contains("not found") || d.contains("not support") || d.contains("invalid model")) ->
                "\n提示：模型名不存在，请点「查看可用模型」确认这个 Key 能用的模型名。"
            d.contains("404") ->
                "\n提示：接口地址不存在，请检查「接口地址」是否填对（国内站 https://api.minimaxi.com/v1）。"
            d.contains("timeout") || d.contains("timed out") || d.contains("超时") ->
                "\n提示：模型响应超时。推理模型（M3/M2/M1）思考较慢，可以换 MiniMax-Text-01 再试。"
            else -> ""
        }
    }

    /** 是不是推理模型（M1/M2/M3 系列，以及名字里带 reason/thinking 的） */
    internal fun isReasoningModel(model: String): Boolean {
        val m = model.trim()
        if (Regex("(?i)(^|[-_.])M[123](\\D|$)").containsMatchIn(m)) return true
        return m.contains("reason", ignoreCase = true) || m.contains("thinking", ignoreCase = true)
    }

    /** 把长 URL 缩成便于阅读的端点名 */
    private fun shortEndpoint(endpoint: String): String {
        val host = endpoint.substringBefore("/v1")
        val path = endpoint.substringAfter("/v1").ifBlank { "/" }
        return "$host$path"
    }

    /**
     * 按顺序尝试的候选端点。
     * 官方现已把 /v1/text/chatcompletion_v2 标为废弃，
     * 推荐的是 OpenAI 兼容的 /v1/chat/completions 与 Anthropic 兼容的 /anthropic/v1/messages。
     */
    internal fun endpoints(baseUrl: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return listOf("$DEFAULT_BASE_URL/chat/completions")
        val bases = buildList {
            add(base)
            if (!base.endsWith("/v1")) add("$base/v1")
        }
        val openAi = "/chat/completions"
        val anthropic = "/anthropic/v1/messages"
        val legacy = "/text/chatcompletion_v2"
        return bases.flatMap { b ->
            listOf("$b$openAi", "$b$anthropic", "$b$legacy")
        }.distinct()
    }

    /** 从错误响应里抠出可读信息 */
    private fun extractError(body: String): String {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["base_resp"]?.jsonObject?.get("status_msg")?.jsonPrimitive?.contentOrNull
                ?: root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
    }

    private fun systemPrompt(current: AiPlan): String {
        val sourceList = DefaultFeeds.all
            .groupBy { it.category }
            .entries
            .joinToString("\n") { (category, feeds) ->
                "- $category：${feeds.joinToString("、") { it.name }}"
            }

        return """
你是「每日新闻推送」App 的配置助手。用户会用中文描述他每天想收到什么新闻，你要把它转成一份 JSON 配置。

严格要求：
1. 只输出一个 JSON 对象，不要输出解释、不要用 Markdown 代码块、不要加任何前后缀。
2. JSON 必须严格使用下面这些字段名。

字段说明：
- pushTimes：推送时间点数组，元素是 24 小时制 "HH:mm"。**一天可以推多次**：用户说"早中晚""每天三次"就给 2~3 个时间点，最少 1 个、最多 6 个；用户只说一个时间就只给一个
- maxItems：每条推送里的新闻条数，3~40 的整数
- categories：内容分类数组，只能从 ${categories.joinToString("、")} 里选；用户没指定就填全部。
  含义是"这些类别是我想看的"，选中的每个类别都会出现在推送里
- keywords：**重点关注**的关键词数组，0~8 个。含义是"这些内容如果出现了，一定要推"，**不是只推命中的**；
  没命中任何关键词也完全正常，其它内容照常推送。用户没指定具体话题就填空数组；
  只有当用户明确说了关注某类主题（如"人工智能""APEC""米哈游"）时才填
- includeHot：是否附带热搜榜，布尔值；用户没提就填 false
- notifyStyle：通知方式，只能是 "summary"（汇总成一条通知）/ "items"（每条各推一条）/ "both"（两者都要）
- note：一句话中文，说明你是怎么理解用户需求的

当前 App 里的推送配置：
- pushTimes=${current.pushTimes.joinToString("、")}
- maxItems=${current.maxItems}
- categories=${if (current.categories.isEmpty()) "全部" else current.categories.joinToString("、")}
- includeHot=${current.includeHot}

判断规则：
- 没提到的项，一律保持上面「当前推送配置」里的值不变
- 说了"早中晚""上午和晚上各一次""每天推三次"这类话 → pushTimes 给多个时间点（自己推算合理的时间，比如 08:00 / 12:00 / 20:00）
- 只说了一个时间 → pushTimes 只给一个
- 说了"只要/仅/重点看"某几类 → categories 只填这几类
- 说了"热搜/热度榜" → includeHot 填 true，并把 "热搜" 归入 categories
- 说了"不要热搜/别带热搜" → includeHot 填 false
- 说了"重点关注X""特别想看X""如果有X一定要推" → 把 X 放进 keywords（重点关键词），
  **同时不要因此删掉其它分类**，用户的意思是"重点之外的我照样要看"

当前 App 里的内容源（分类 → 源）：
$sourceList

示例：
用户：每天早上9点给我8条国际新闻和科技新闻
输出：{"pushTimes":["09:00"],"maxItems":8,"categories":["国际","科技"],"keywords":[],"includeHot":false,"notifyStyle":"summary","note":"每天9点推送8条国际与科技新闻"}

用户：每天早上八点、中午十二点、晚上八点各推一次，给我10条国际新闻
输出：{"pushTimes":["08:00","12:00","20:00"],"maxItems":10,"categories":["国际"],"keywords":[],"includeHot":false,"notifyStyle":"summary","note":"每天三个时间点各推10条国际新闻"}
""".trimIndent()
    }

    /**
     * 从模型回复里解析出方案。
     *
     * 刻意不用严格的反序列化：模型经常把数字写成字符串（"maxItems":"8"）、
     * 少给字段、或者多给几个没见过的字段，严格解析会直接失败。
     * 这里逐字段手动取值，容错度高得多。
     */
    internal fun extractJson(content: String): AiPlan? {
        val obj = findJsonObject(content) ?: return null
        return planFrom(obj)
    }

    /** 找到回复里的 JSON 对象；找不到就尝试修复被截断的 JSON */
    internal fun findJsonObject(text: String): JsonObject? {
        val raw = text.trim()
        val candidates = mutableListOf<String>()

        // 去掉 ``` 代码块
        Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1)?.let { candidates.add(it.trim()) }

        // 第一个 { 到最后一个 }
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        if (first >= 0 && last > first) candidates.add(raw.substring(first, last + 1))
        // 截断的（没有结尾的 }）
        if (first >= 0) candidates.add(raw.substring(first))
        candidates.add(raw)

        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            runCatching { return json.parseToJsonElement(candidate).jsonObject }
            // 被截断的话，逐字砍尾巴再补齐括号试一次
            repairTruncated(candidate)?.let { fixed ->
                runCatching { return json.parseToJsonElement(fixed).jsonObject }
            }
        }
        return null
    }

    /**
     * 修复被 max_completion_tokens 截断的 JSON：
     * 从末尾往前一点点砍，砍到能补齐括号并解析成功为止。
     */
    internal fun repairTruncated(text: String): String? {
        var candidate = text.trim()
        var dropped = 0
        while (candidate.isNotEmpty() && dropped < 120) {
            val closed = closeBalanced(candidate) ?: run {
                // 字符串没闭合，只能继续砍
                candidate = candidate.dropLast(1)
                dropped++
                null
            }
            if (closed != null) {
                runCatching { json.parseToJsonElement(closed) }.getOrNull()?.let { return closed }
            }
            candidate = candidate.dropLast(1)
            dropped++
        }
        return null
    }

    /** 给 JSON 补上缺失的右括号 / 右方括号 */
    private fun closeBalanced(text: String): String? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (ch in text) {
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (ch) {
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}' -> if (stack.isNotEmpty()) stack.removeLast()
                ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        if (inString) return null
        var out = text.trimEnd().trimEnd(',').trimEnd()
        if (out.isEmpty()) return null
        while (stack.isNotEmpty()) out += stack.removeLast()
        return out
    }

    /** 把 "8:30" / "08:30" 统一成 "08:30"，不合法返回 null */
    private fun normalizeTime(raw: String): String? {
        val text = raw.trim()
        if (!text.matches(Regex("\\d{1,2}:\\d{2}"))) return null
        val h = text.substringBefore(":").toIntOrNull() ?: return null
        val m = text.substringAfter(":").toIntOrNull() ?: return null
        return "%02d:%02d".format(h.coerceIn(0, 23), m.coerceIn(0, 59))
    }

    /** 从 JSON 对象里宽松地取字段 */
    private fun planFrom(obj: JsonObject): AiPlan {
        fun str(key: String): String? =
            obj[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim()

        fun bool(key: String): Boolean? {
            val element = obj[key] ?: return null
            runCatching { element.jsonPrimitive.booleanOrNull }.getOrNull()?.let { return it }
            return str(key)?.lowercase()?.let {
                when (it) {
                    "true", "yes", "1" -> true
                    "false", "no", "0" -> false
                    else -> null
                }
            }
        }

        fun int(key: String): Int? {
            val element = obj[key] ?: return null
            runCatching { element.jsonPrimitive.intOrNull }.getOrNull()?.let { return it }
            return str(key)?.toDoubleOrNull()?.toInt()
        }

        fun strings(key: String): List<String> {
            val element = obj[key] ?: return emptyList()
            runCatching {
                return element.jsonArray.mapNotNull { item ->
                    runCatching { item.jsonPrimitive.contentOrNull }.getOrNull()
                }
            }
            // 退化成逗号分隔的字符串
            return str(key)
                ?.split(",", "、", "，", " ")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

        // pushTimes 是数组；老模型可能只给 pushTime 字符串，两个都认
        val timeList = strings("pushTimes").ifEmpty {
            listOfNotNull(str("pushTime"))
        }.mapNotNull { normalizeTime(it) }
        val pushTimes = timeList.ifEmpty { listOf("08:00") }.distinct().sorted().take(6)

        val style = str("notifyStyle")?.lowercase()?.let {
            if (it in listOf("summary", "items", "both")) it else null
        } ?: "summary"

        return AiPlan(
            pushTimes = pushTimes,
            maxItems = (int("maxItems") ?: 8).coerceIn(3, 40),
            categories = strings("categories"),
            keywords = strings("keywords"),
            includeHot = bool("includeHot") ?: false,
            notifyStyle = style,
            note = str("note").orEmpty()
        )
    }
}

/** AI 给出的合并分组（原始 DTO，索引从 1 开始） */
@kotlinx.serialization.Serializable
data class MergeGroupDto(
    val topic: String = "",
    val representative: Int = 0,
    val members: List<Int> = emptyList(),
    val reason: String = ""
)

@kotlinx.serialization.Serializable
data class MergeDto(
    val groups: List<MergeGroupDto> = emptyList(),
    val note: String = ""
)

/** 合并结果（索引已转成 0 基，便于和候选列表对齐） */
data class MergeGroup(
    val topic: String,
    val representativeIndex: Int,
    val memberIndexes: List<Int>,
    val reason: String
)

data class MergeOutcome(
    val groups: List<MergeGroup>,
    val note: String
)
