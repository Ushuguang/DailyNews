package com.dailypush.news

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.dailypush.news.notify.Notifier
import com.dailypush.news.ui.AppRoot
import com.dailypush.news.ui.theme.DailyNewsTheme

class MainActivity : ComponentActivity() {

    /** 从通知点进来时需要切换到的页签；null 表示不干预用户当前所在页 */
    private val pendingTab = mutableStateOf<String?>(null)

    /** 直接打开某个二级页（guide / trace） */
    private val pendingSubPage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)

        if (savedInstanceState == null) {
            pendingTab.value = intent?.getStringExtra(EXTRA_TAB) ?: TAB_NEWS
            pendingSubPage.value = intent?.getStringExtra(EXTRA_SUBPAGE)
        }

        setContent {
            DailyNewsTheme {
                AppRoot(initialTab = pendingTab.value, initialSubPage = pendingSubPage.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTab.value = intent.getStringExtra(EXTRA_TAB) ?: TAB_NEWS
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_SUBPAGE = "subpage"
        const val TAB_NEWS = "news"
        const val TAB_TODAY = "today"
    }
}
