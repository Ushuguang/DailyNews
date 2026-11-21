package com.dailypush.news.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private data class TabItem(val key: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("news", "要闻", Icons.AutoMirrored.Filled.List),
    TabItem("feeds", "订阅源", Icons.Default.Star),
    TabItem("today", "今日", Icons.Default.DateRange),
    TabItem("ai", "AI", Icons.Default.Create),
    TabItem("settings", "设置", Icons.Default.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    initialTab: String?,
    initialSubPage: String? = null,
    vm: MainViewModel = viewModel()
) {
    val settings by vm.settings.collectAsState()
    val articles by vm.articles.collectAsState()
    val busy by vm.busy.collectAsState()
    val todayTrace by vm.todayTrace.collectAsState()
    val traceDates by vm.traceDates.collectAsState()
    val selectedTrace by vm.selectedTrace.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var tabIndex by rememberSaveable {
        mutableStateOf(tabs.indexOfFirst { it.key == initialTab }.coerceAtLeast(0))
    }
    /** 点标题栏或「回到顶部」按钮时递增，各列表页监听它回到最上面 */
    var scrollTopSignal by remember { mutableIntStateOf(0) }
    /** 二级页面：guide 助手页 / trace 痕迹页 */
    var subPage by rememberSaveable { mutableStateOf<String?>(initialSubPage) }

    LaunchedEffect(initialTab) {
        if (initialTab != null) {
            if (initialTab == "trace") {
                vm.loadTraces()
                subPage = "trace"
            } else if (initialTab == "guide") {
                subPage = "guide"
            } else {
                val index = tabs.indexOfFirst { it.key == initialTab }
                if (index >= 0) tabIndex = index
            }
        }
    }

    LaunchedEffect(tabIndex) {
        if (tabs[tabIndex].key == "today") {
            vm.loadTraces()
        }
    }

    // 打开「决策痕迹」页时把历史日期和当天痕迹拉起来
    LaunchedEffect(subPage) {
        if (subPage == "trace") {
            vm.loadTraces()
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    val nextPushTime = settings.pushTimes.joinToString("、")

    if (subPage != null) {
        // ---- 二级页面（助手 / 痕迹），带返回按钮 ----
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (subPage) {
                                "guide" -> "助手"
                                "about" -> "关于"
                                else -> "决策痕迹"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { subPage = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (subPage == "guide") {
                    GuideScreen()
                } else if (subPage == "about") {
                    AboutScreen(vm = vm, onOpenUrl = { openUrl(it) })
                } else {
                    TraceScreen(
                        dates = traceDates,
                        trace = selectedTrace,
                        onPickDate = { vm.loadTrace(it) },
                        onOpen = { openUrl(it) },
                        scrollTopSignal = scrollTopSignal
                    )
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = tabs[tabIndex].label,
                        modifier = Modifier
                            .clickable { scrollTopSignal++ }
                            .padding(vertical = 4.dp)
                    )
                },
                actions = {
                    when (tabs[tabIndex].key) {
                        "news", "feeds" -> {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 20.dp)
                                        .size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                IconButton(onClick = { vm.refreshNow() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                                }
                            }
                        }
                        "today" -> {
                            IconButton(onClick = { subPage = "trace" }) {
                                Icon(Icons.Default.Info, contentDescription = "决策痕迹")
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = index == tabIndex,
                        onClick = { tabIndex = index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tabs[tabIndex].key) {
                "news" -> NewsScreen(
                    articles = articles,
                    feeds = settings.feeds,
                    busy = busy,
                    onRefresh = { vm.refreshNow() },
                    onOpen = { openUrl(it) },
                    onGoGuide = { subPage = "guide" },
                    scrollTopSignal = scrollTopSignal
                )

                "feeds" -> FeedsScreen(
                    settings = settings,
                    vm = vm,
                    onOpenUrl = { openUrl(it) },
                    scrollTopSignal = scrollTopSignal
                )

                "today" -> TodayScreen(
                    trace = todayTrace,
                    nextPushTime = nextPushTime,
                    busy = busy,
                    onPushNow = { vm.pushNow() },
                    onOpen = { openUrl(it) },
                    onOpenTrace = { subPage = "trace" },
                    scrollTopSignal = scrollTopSignal
                )

                "ai" -> AiScreen(vm = vm)

                "settings" -> SettingsScreen(
                    settings = settings,
                    vm = vm,
                    onOpenGuide = { subPage = "guide" },
                    onOpenAbout = { subPage = "about" },
                    onOpenTrace = {
                        vm.loadTraces()
                        subPage = "trace"
                    }
                )
            }
        }
    }
}
