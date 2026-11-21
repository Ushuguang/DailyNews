package com.dailypush.news

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailypush.news.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * 重点关键词生效时，**不能按分类把订阅源停用** ——
 * 关掉的源永远抓不到内容，「有就一定要推」就成了一句空话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class KeywordKeepsSourcesTest {

    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun keywordsKeepAllSourcesEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = MainViewModel(context as android.app.Application)
        settle()

        // 只选「国际」，但加了重点关键词「米哈游」→ 所有源都要保持启用
        vm.updateFilter(listOf("国际"), listOf("米哈游"), true)
        settle()

        val feeds = vm.settings.value.feeds
        assertTrue("有重点关键词时不该停用任何源", feeds.all { it.enabled })
    }

    @Test
    fun withoutKeywordsCategoriesStillDisableSources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = MainViewModel(context as android.app.Application)
        settle()

        // 没有关键词时，分类筛选照常停用不相关的源
        vm.updateFilter(listOf("国际"), emptyList(), true)
        settle()

        val feeds = vm.settings.value.feeds
        assertTrue("国际的源要留着", feeds.any { it.enabled && it.category == "国际" })
        assertTrue("不相关的源要停掉", feeds.any { !it.enabled })
    }
}
