package com.dailypush.news.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dailypush.news.data.AppSettings
import com.dailypush.news.data.AiClient
import com.dailypush.news.data.AiPlan
import com.dailypush.news.data.Article
import com.dailypush.news.data.ArticleCache
import com.dailypush.news.data.Feed
import com.dailypush.news.data.HotFetcher
import com.dailypush.news.data.NewsRepository
import com.dailypush.news.data.RssFetcher
import com.dailypush.news.data.SecretStore
import com.dailypush.news.data.UpdateChecker
import com.dailypush.news.data.PushTrace
import com.dailypush.news.data.PushTraceStore
import com.dailypush.news.data.SettingsRepository
import com.dailypush.news.data.WebPageFetcher
import com.dailypush.news.notify.Notifier
import com.dailypush.news.work.PushScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val newsRepo = NewsRepository(application)

    private val _settings = MutableStateFlow(AppSettings())
    val settings = _settings.asStateFlow()

    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    val articles = _articles.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState = _updateState.asStateFlow()

    private val _todayTrace = MutableStateFlow<PushTrace?>(null)
    val todayTrace = _todayTrace.asStateFlow()

    private val _traceDates = MutableStateFlow<List<String>>(emptyList())
    val traceDates = _traceDates.asStateFlow()

    private val _selectedTrace = MutableStateFlow<PushTrace?>(null)
    val selectedTrace = _selectedTrace.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { _settings.value = it }
        }
        viewModelScope.launch {
            _articles.value = ArticleCache.load(getApplication())
        }
        viewModelScope.launch {
            _todayTrace.value = PushTraceStore.today(getApplication())
            _traceDates.value = PushTraceStore.dates(getApplication())
        }
    }

    // ---------- 决策痕迹 ----------

    /** 刷新今天的痕迹与可查看的历史日期 */
    fun loadTraces() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            _todayTrace.value = PushTraceStore.today(app)
            val dates = PushTraceStore.dates(app)
            _traceDates.value = dates
            if (dates.isNotEmpty()) {
                _selectedTrace.value = PushTraceStore.load(app, dates.first())
            }
        }
    }

    /** 查看某一天的痕迹 */
    fun loadTrace(date: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            _selectedTrace.value = PushTraceStore.load(app, date)
            _traceDates.value = PushTraceStore.dates(app)
        }
    }

    // ---------- 设置 ----------

    fun setPushEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setPushEnabled(enabled)
            val app = getApplication<Application>()
            if (enabled) {
                val s = _settings.value
                PushScheduler.schedule(app, s.pushTimes)
                emit("已开启每日推送")
            } else {
                PushScheduler.cancel(app)
                emit("已关闭每日推送")
            }
        }
    }

    /** 设置推送时间点（一天可以多个） */
    fun setPushTimes(times: List<String>) {
        viewModelScope.launch {
            val cleaned = times
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .take(AppSettings.MAX_PUSH_TIMES)
            if (cleaned.isEmpty()) return@launch
            settingsRepo.setPushTimes(cleaned)
            val app = getApplication<Application>()
            if (_settings.value.pushEnabled) {
                PushScheduler.schedule(app, cleaned)
            }
            emit("推送时间：${cleaned.joinToString("、")}")
        }
    }

    fun setPushTime(hour: Int, minute: Int) {
        setPushTimes(listOf("%02d:%02d".format(hour, minute)))
    }

    fun setNotifyStyle(style: String) {
        viewModelScope.launch {
            settingsRepo.setNotifyStyle(style)
        }
    }

    /**
     * 手动改推送筛选条件（AI 解析失败时也能自己配）。
     * 改完顺手把对应分类的订阅源启用/停用，和 AI 应用配置的行为保持一致。
     */
    fun updateFilter(categories: List<String>, keywords: List<String>, includeHot: Boolean) {
        viewModelScope.launch {
            val current = _settings.value
            settingsRepo.setAiPlan(categories, keywords, includeHot, current.aiNote)
            val allCategories = current.feeds.map { it.category }.toSet()
            val matched = categories.filter { it != "热搜" && allCategories.contains(it) }
            val usable = matched.isNotEmpty()
            val feeds = current.feeds.map { feed ->
                val wanted = if (feed.category == "热搜") {
                    includeHot
                } else if (keywords.isNotEmpty()) {
                    // 有重点关键词时**不按分类停用源**：
                    // 关掉的源永远抓不到东西，「有就一定要推」就成了一句空话
                    true
                } else if (!usable) {
                    true
                } else {
                    matched.contains(feed.category)
                }
                feed.copy(enabled = wanted)
            }
            settingsRepo.setFeeds(feeds)
            emit("筛选条件已更新")
        }
    }

    // ---------- 关于 / 检查更新 ----------

    /** 当前版本名，例如 1.6.0 */
    fun appVersionName(): String = runCatching {
        getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0)
            .versionName
    }.getOrNull().orEmpty().ifBlank { "1.6.0" }

    /** 当前 versionCode */
    fun appVersionCode(): Long = runCatching {
        val app = getApplication<Application>()
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    /** 检查 GitHub 上有没有新版本 */
    fun checkUpdate() {
        if (_updateState.value.checking || _updateState.value.downloading) return
        viewModelScope.launch {
            _updateState.value = _updateState.value.copy(checking = true, message = "")
            val result = UpdateChecker.check()
            result.onSuccess { release ->
                val newer = UpdateChecker.isNewer(release.version, appVersionName())
                _updateState.value = UpdateState(
                    checking = false,
                    latest = release,
                    hasUpdate = newer,
                    message = if (newer) {
                        "发现新版本 v${release.version}"
                    } else {
                        "当前已是最新版本 v${appVersionName()}"
                    }
                )
            }.onFailure { error ->
                _updateState.value = UpdateState(
                    checking = false,
                    message = error.message ?: "检查更新失败，请稍后再试"
                )
            }
        }
    }

    /** 下载新版本并调系统安装器 */
    fun downloadAndInstall() {
        val release = _updateState.value.latest ?: return
        if (release.apkUrl.isBlank()) {
            emit("这个版本没有提供安装包，请用网页下载")
            return
        }
        if (_updateState.value.downloading) return
        viewModelScope.launch {
            _updateState.value = _updateState.value.copy(downloading = true, progress = 0)
            val app = getApplication<Application>()
            runCatching {
                UpdateChecker.download(
                    context = app,
                    url = release.apkUrl,
                    fileName = "DailyNews-${release.version}.apk"
                ) { done, total ->
                    val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                    _updateState.value = _updateState.value.copy(progress = percent)
                }
            }.onSuccess { file ->
                _updateState.value = _updateState.value.copy(
                    downloading = false,
                    progress = 100,
                    downloadedApkPath = file.absolutePath
                )
                emit("下载完成，正在打开安装界面")
                if (!installApk(file)) {
                    emit("无法直接打开安装界面，请在「关于」页点「网页下载」")
                }
            }.onFailure { error ->
                _updateState.value = _updateState.value.copy(downloading = false)
                emit("下载失败：${error.message ?: "未知错误"}")
            }
        }
    }

    /** 重新打开安装界面（下载完没弹出来时用） */
    fun installDownloadedApk() {
        val path = _updateState.value.downloadedApkPath
        if (path.isBlank()) return
        val file = java.io.File(path)
        if (!file.exists()) {
            emit("安装包不见了，请重新下载")
            return
        }
        if (!installApk(file)) {
            emit("无法打开安装界面，请检查是否允许安装未知来源应用")
        }
    }

    /** 调系统安装器 */
    private fun installApk(file: java.io.File): Boolean {
        val app = getApplication<Application>()
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                file
            )
        }.getOrNull() ?: return false
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        return runCatching { app.startActivity(intent) }.isSuccess
    }

    /** 是否用 AI 合并重复报道 */
    fun setAiMerge(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setAiMerge(enabled)
            emit(if (enabled) "已开启 AI 合并重复报道" else "已关闭 AI 合并")
        }
    }

    fun setMaxItems(value: Int) {
        viewModelScope.launch {
            settingsRepo.setMaxItems(value)
        }
    }

    // ---------- 订阅源 ----------

    fun toggleFeed(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val updated = _settings.value.feeds.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            settingsRepo.setFeeds(updated)
        }
    }

    fun removeFeed(id: String) {
        viewModelScope.launch {
            val updated = _settings.value.feeds.filterNot { it.id == id }
            settingsRepo.setFeeds(updated)
            emit("已删除订阅源")
        }
    }

    fun addFeed(name: String, url: String, category: String, kind: String) {
        viewModelScope.launch {
            val normalized = url.trim()
            if (!normalized.startsWith("http")) {
                emit("网址需要以 http:// 或 https:// 开头")
                return@launch
            }
            val id = "custom_" + System.currentTimeMillis()
            val updated = _settings.value.feeds +
                Feed(id, name.trim().ifBlank { "自定义源" }, normalized, category, true, kind)
            settingsRepo.setFeeds(updated)
            emit("已添加：$name")
        }
    }

    /** 测试某个源能否抓取，返回条数；失败返回错误信息 */
    suspend fun testFeed(url: String, kind: String = Feed.KIND_RSS): Result<Int> {
        val normalized = url.trim()
        if (!normalized.startsWith("http")) {
            return Result.failure(IllegalArgumentException("网址需要以 http:// 或 https:// 开头"))
        }
        return runCatching {
            val feed = Feed("test", "测试", normalized, kind = kind)
            when (kind) {
                Feed.KIND_WEB -> WebPageFetcher.fetch(feed).size
                Feed.KIND_HOT -> HotFetcher.fetch(feed).size
                else -> RssFetcher.fetch(feed).size
            }
        }
    }

    // ---------- AI 配置 ----------

    private val secretStore = SecretStore(application)

    private val _ai = MutableStateFlow(AiUiState(apiKey = secretStore.apiKey))
    val ai = _ai.asStateFlow()

    fun setAiBaseUrl(value: String) {
        _ai.value = _ai.value.copy(baseUrl = value)
    }

    fun setAiModel(value: String) {
        _ai.value = _ai.value.copy(model = value)
    }

    fun setAiApiKey(value: String) {
        _ai.value = _ai.value.copy(apiKey = value)
        secretStore.apiKey = value
    }

    fun setAiRequirement(value: String) {
        _ai.value = _ai.value.copy(requirement = value)
    }

    fun updateAiPlan(transform: (AiPlan) -> AiPlan) {
        val current = _ai.value.plan ?: return
        _ai.value = _ai.value.copy(plan = transform(current))
    }

    /** 列出这个 Key 实际能用的模型（顺便验证 Key 和地区对不对） */
    fun loadAiModels() {
        val state = _ai.value
        if (state.loadingModels) return
        viewModelScope.launch {
            _ai.value = _ai.value.copy(loadingModels = true, status = "正在读取可用模型…")
            val result = AiClient.listModels(state.baseUrl, state.apiKey)
            _ai.value = _ai.value.copy(
                loadingModels = false,
                availableModels = result.getOrDefault(emptyList()),
                status = result.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) "这个 Key 没有返回任何模型" else "可用模型：${list.joinToString("、")}"
                    },
                    onFailure = { "读取失败：" + (it.message ?: "未知错误") }
                )
            )
        }
    }

    fun testAiConnection() {
        val state = _ai.value
        if (state.testing) return
        viewModelScope.launch {
            _ai.value = _ai.value.copy(testing = true, status = "正在连接…")
            val result = AiClient.testConnection(state.baseUrl, state.apiKey, state.model)
            _ai.value = _ai.value.copy(
                testing = false,
                status = result.getOrNull() ?: ("连接失败：" + (result.exceptionOrNull()?.message ?: "未知错误"))
            )
        }
    }

    fun parseWithAi() {
        val state = _ai.value
        if (state.parsing) return
        viewModelScope.launch {
            _ai.value = _ai.value.copy(parsing = true, status = "AI 正在解析你的需求…")
            val current = AiPlan(
                pushTimes = _settings.value.pushTimes,
                maxItems = _settings.value.maxItems,
                categories = _settings.value.pushCategories,
                keywords = _settings.value.pushKeywords,
                includeHot = _settings.value.includeHot,
                notifyStyle = _settings.value.notifyStyle,
                note = _settings.value.aiNote
            )
            val result = AiClient.parsePlan(
                baseUrl = state.baseUrl,
                apiKey = state.apiKey,
                model = state.model,
                requirement = state.requirement,
                currentPlan = current
            )
            result.onSuccess { plan ->
                _ai.value = _ai.value.copy(
                    parsing = false,
                    plan = plan,
                    status = "解析完成，确认后点「应用到推送」"
                )
            }.onFailure { error ->
                _ai.value = _ai.value.copy(
                    parsing = false,
                    status = "解析失败：" + (error.message ?: "未知错误")
                )
            }
        }
    }

    fun applyAiPlan() {
        val plan = _ai.value.plan ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            settingsRepo.setPushTimes(plan.normalizedTimes())
            settingsRepo.setMaxItems(plan.maxItems)
            settingsRepo.setNotifyStyle(plan.notifyStyle)
            settingsRepo.setAiPlan(plan.categories, plan.keywords, plan.includeHot, plan.note)

            // 按分类自动启用/停用订阅源，避免抓用不上的源。
            // 但如果 AI 给的分类一个都没匹配上（比如它自造了「军事」这种分类），
            // 就会把所有新闻源都关掉、只剩热搜 —— 这时宁可保留全部源并提示用户。
            val allCategories = _settings.value.feeds.map { it.category }.toSet()
            val matched = plan.categories.filter { allCategories.contains(it) }
            val categoriesUsable = matched.isNotEmpty()
            val feeds = _settings.value.feeds.map { feed ->
                val wanted = if (feed.category == "热搜") {
                    plan.includeHot
                } else if (plan.keywords.isNotEmpty()) {
                    // 有重点关键词时**不按分类停用源**：
                    // 关掉的源永远抓不到东西，「有就一定要推」就成了一句空话
                    true
                } else if (!categoriesUsable) {
                    true
                } else {
                    matched.contains(feed.category)
                }
                feed.copy(enabled = wanted)
            }
            settingsRepo.setFeeds(feeds)
            if (!categoriesUsable && plan.categories.isNotEmpty()) {
                emit("AI 给的分类（${plan.categories.joinToString("、")}）没有对应的订阅源，已保留全部源")
            }

            if (_settings.value.pushEnabled) {
                PushScheduler.schedule(app, plan.normalizedTimes())
            }
            _ai.value = _ai.value.copy(status = "已应用：${plan.note.ifBlank { "配置已更新" }}")
            emit("AI 配置已应用到推送")
        }
    }

    /**
     * 立即跑一次完整推送：抓取 → 筛选 → AI 合并 → 写痕迹 → 发通知。
     * 等任务跑完后刷新「今日」页和痕迹列表。
     */
    fun pushNow() {
        if (_busy.value) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            _busy.value = true
            val startedAt = System.currentTimeMillis()
            emit("正在抓取、合并并生成今日推送…")
            PushScheduler.runNow(app, notify = true, force = true)
            var done = false
            for (i in 0 until 30) {
                delay(3000)
                val trace = PushTraceStore.today(app)
                if (trace != null && trace.pushedAt >= startedAt - 2000) {
                    done = true
                    break
                }
            }
            _todayTrace.value = PushTraceStore.today(app)
            _traceDates.value = PushTraceStore.dates(app)
            _selectedTrace.value = PushTraceStore.today(app)
            _busy.value = false
            emit(
                if (done) "今日推送已生成，去「今日」页看看"
                else "这次没能生成推送，检查网络或订阅源后重试"
            )
        }
    }

    // ---------- 抓取 / 通知 ----------

    fun refreshNow() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = newsRepo.fetchAndCache(_settings.value.feeds)
                _articles.value = ArticleCache.load(getApplication())
                settingsRepo.setLastFetchSummary(
                    if (result.failedCount == 0) {
                        "全部 ${result.successCount} 个源抓取成功"
                    } else {
                        "${result.successCount} 个成功 / ${result.failedCount} 个失败"
                    }
                )
                val message = when {
                    result.articles.isEmpty() -> "没有抓到内容，请检查订阅源是否可用"
                    result.failedCount > 0 -> "更新完成，${result.successCount} 个源成功，${result.failedCount} 个失败"
                    else -> "更新完成，共 ${result.articles.size} 条"
                }
                emit(message)
            } catch (t: Throwable) {
                emit("更新失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }

    fun testNotification() {
        Notifier.notifyTest(getApplication())
        emit("已发送测试通知，请下拉通知栏查看")
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }
}

/** 「关于」页的检查更新状态 */
data class UpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    /** 下载进度 0~100 */
    val progress: Int = 0,
    val latest: UpdateChecker.Release? = null,
    val hasUpdate: Boolean = false,
    val message: String = "",
    /** 已下载的安装包路径 */
    val downloadedApkPath: String = ""
)

/** AI 页的表单状态 */
data class AiUiState(
    val baseUrl: String = AiClient.DEFAULT_BASE_URL,
    val model: String = AiClient.DEFAULT_MODEL,
    val apiKey: String = "",
    val requirement: String = "",
    val testing: Boolean = false,
    val parsing: Boolean = false,
    val plan: AiPlan? = null,
    val status: String = "",
    /** 从接口读到的、这个 Key 真正能用的模型名 */
    val availableModels: List<String> = emptyList(),
    val loadingModels: Boolean = false
)
