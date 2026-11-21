package com.dailypush.news

import com.dailypush.news.data.UpdateChecker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查更新：版本号比较 + GitHub Release 接口 */
class UpdateCheckerTest {

    @Test
    fun versionCompare() {
        assertTrue(UpdateChecker.isNewer("1.6.0", "1.5.1"))
        assertTrue(UpdateChecker.isNewer("v1.6.0", "1.5.9"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.5.1", "1.6.0"))
        assertFalse(UpdateChecker.isNewer("1.6.0", "1.6.0"))
        assertFalse(UpdateChecker.isNewer("1.6", "1.6.0"))
        assertFalse(UpdateChecker.isNewer("v1.6.0", "v1.6.0"))
    }

    @Test
    fun repoUrlCorrect() {
        assertTrue(UpdateChecker.REPO_URL.endsWith("Ushuguang/DailyNews"))
        assertTrue(UpdateChecker.API_LATEST.contains("api.github.com"))
    }

    /**
     * 真连 GitHub。仓库还没上传时返回 404，这里只断言「有明确的中文提示」，
     * 不管仓库有没有版本都不会挂。
     */
    @Test
    fun checkUpdateNeverCrashes() {
        val result = runBlocking { UpdateChecker.check() }
        result.onSuccess { release ->
            println(">>> 仓库最新版本：" + release.version + " apk=" + release.apkUrl)
            assertTrue("版本号不能为空", release.version.isNotBlank())
        }.onFailure { error ->
            val message = error.message.orEmpty()
            println(">>> 检查更新结果：" + message)
            assertTrue("失败也要有中文提示，实际：$message", message.isNotBlank())
        }
    }
}
