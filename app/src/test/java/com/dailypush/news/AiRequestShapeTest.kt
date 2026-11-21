package com.dailypush.news

import com.dailypush.news.data.AiClient
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体结构的回归测试。
 *
 * 重点：MiniMax 的 chatcompletion_v2 必须收到一条 content 非空的 user 消息，
 * 否则网关直接返回 `invalid params, chat content is empty (2013)`。
 */
class AiRequestShapeTest {

    private fun bodyOf(model: String, system: String, user: String) =
        AiClient.buildBody(model = model, system = system, user = user)

    @Test
    fun `请求体里必须有 user 消息`() {
        val body = bodyOf("MiniMax-M3", "你是助手", "你好")
        val messages = body["messages"]!!.jsonArray
        val roles = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertTrue("必须包含 user 消息，实际：$roles", roles.contains("user"))
        // system 必须排在第一条且只能有一条
        assertEquals("system", roles.first())
        assertEquals(1, roles.count { it == "system" })
    }

    @Test
    fun `user 消息内容不能为空`() {
        val body = bodyOf("MiniMax-M3", "你是助手", "   ")
        val messages = body["messages"]!!.jsonArray
        val userContent = messages
            .map { it.jsonObject }
            .first { it["role"]!!.jsonPrimitive.content == "user" }
            .getValue("content")!!.jsonPrimitive.content
        assertTrue("user content 不能是空串", userContent.isNotBlank())
    }

    @Test
    fun `每条消息都要有 role 和 content`() {
        val body = bodyOf("MiniMax-M3.1-Flash", "你是助手", "你好")
        body["messages"]!!.jsonArray.forEach { element ->
            val obj = element.jsonObject
            assertNotNull(obj["role"])
            assertNotNull(obj["content"])
            assertTrue(obj["content"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun `模型名留空时用默认模型`() {
        val body = bodyOf("", "你是助手", "你好")
        assertEquals(AiClient.DEFAULT_MODEL, body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `M3 系列用 max_completion_tokens 老模型用 max_tokens`() {
        val m3 = AiClient.buildBody("MiniMax-M3", "s", "u", maxTokens = 100)
        assertNotNull(m3["max_completion_tokens"])
        assertEquals(null, m3["max_tokens"])

        val legacy = AiClient.buildBody("MiniMax-Text-01", "s", "u", maxTokens = 100)
        assertNotNull(legacy["max_tokens"])
        assertEquals(null, legacy["max_completion_tokens"])
    }

    @Test
    fun `OpenAI 兼容端点排第一 原生端点垫底`() {
        val list = AiClient.endpoints("https://api.minimaxi.com/v1")
        assertEquals("https://api.minimaxi.com/v1/chat/completions", list.first())
        // 官方已把 /text/chatcompletion_v2 标为废弃，放最后
        assertEquals("https://api.minimaxi.com/v1/text/chatcompletion_v2", list.last())
        // Anthropic 兼容端点在中间，用来兜 Token Plan 的 Key
        assertTrue(list.contains("https://api.minimaxi.com/v1/anthropic/v1/messages"))
    }

    @Test
    fun `Anthropic 兼容请求体 system 单独放顶层 且必须有 max_tokens`() {
        val body = AiClient.bodyFor(
            AiClient.Flavor.ANTHROPIC,
            AiClient.Spec("MiniMax-M3", "你是助手", "你好", temperature = 0.2, maxTokens = 100)
        )
        assertEquals("你是助手", body["system"]!!.jsonPrimitive.content)
        // messages 里只有 user，system 不进 messages
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertNotNull(body["max_tokens"])
    }

    @Test
    fun `余额不足的提示要讲清两种 Key 和换平台的办法`() {
        val hint = AiClient.hintFor("insufficient balance (1008)")
        assertTrue(hint.contains("sk-cp-"))
        assertTrue(hint.contains("sk-api-"))
        assertTrue(hint.contains("接口地址"))
    }

    @Test
    fun `非 MiniMax 服务优先 OpenAI 兼容端点`() {
        val list = AiClient.endpoints("https://api.example.com/v1")
        assertEquals("https://api.example.com/v1/chat/completions", list.first())
    }

    @Test
    fun `地址没带 v1 时自动补`() {
        val list = AiClient.endpoints("https://api.minimaxi.com")
        assertTrue(list.contains("https://api.minimaxi.com/v1/text/chatcompletion_v2"))
    }

    @Test
    fun `错误提示能覆盖常见错误码`() {
        assertTrue(AiClient.hintFor("invalid params, chat content is empty (2013)").contains("user 消息"))
        assertTrue(AiClient.hintFor("interface error 1004").contains("API Key"))
        assertTrue(AiClient.hintFor("HTTP 402").contains("余额"))
    }
}
