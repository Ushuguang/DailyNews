package com.dailypush.news

import com.dailypush.news.data.AiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 回复的健壮解析。
 *
 * 背景：推理模型（M1/M2/M3）的思考 token 也算进 max_completion_tokens，
 * 上限给小了 JSON 会写到一半被截断；模型还经常把数字写成字符串、少给字段。
 * 这些都不能当成「解析失败」直接甩给用户。
 */
class AiRobustParseTest {

    @Test
    fun `被截断的 JSON 能修复`() {
        val truncated = """{"pushTime":"09:30","maxItems":12,"categories":["国际","科技"],"keywords":["APEC"],"includeHot":true,"notifyStyle":"both","note":"每天9点半12条""".dropLast(1)
        val plan = AiClient.extractJson(truncated)
        assertNotNull("截断的 JSON 应该能修复", plan)
        assertEquals(9, plan!!.hour())
        assertEquals(12, plan.maxItems)
        assertEquals(listOf("国际", "科技"), plan.categories)
    }

    @Test
    fun `数字写成字符串也能解析`() {
        val reply = """{"pushTime":"09:30","maxItems":"12","categories":["国际"],"keywords":[],"includeHot":"true","notifyStyle":"both","note":"x"}"""
        val plan = AiClient.extractJson(reply)
        assertNotNull(plan)
        assertEquals(12, plan!!.maxItems)
        assertTrue(plan.includeHot)
    }

    @Test
    fun `缺字段用默认值`() {
        val plan = AiClient.extractJson("""{"categories":["科技"]}""")
        assertNotNull(plan)
        assertEquals(8, plan!!.maxItems)
        assertEquals(listOf("科技"), plan.categories)
        assertEquals("08:00", plan.pushTime)
    }

    @Test
    fun `多出来的未知字段不影响`() {
        val reply = """{"maxItems":6,"categories":["财经"],"keywords":["黄金"],"includeHot":false,"notifyStyle":"items","note":"n","extraField":123,"another":{"a":1}}"""
        val plan = AiClient.extractJson(reply)
        assertNotNull(plan)
        assertEquals(6, plan!!.maxItems)
        assertEquals(listOf("黄金"), plan.keywords)
    }

    @Test
    fun `关键词写成逗号分隔的字符串也能解析`() {
        val plan = AiClient.extractJson("""{"keywords":"APEC，新能源","categories":"国际"}""")
        assertNotNull(plan)
        assertEquals(listOf("APEC", "新能源"), plan!!.keywords)
        assertEquals(listOf("国际"), plan.categories)
    }

    @Test
    fun `不是 JSON 时才返回空`() {
        assertNull(AiClient.extractJson("抱歉，我无法理解"))
        assertNull(AiClient.extractJson(""))
    }

    @Test
    fun `推理模型识别`() {
        assertTrue(AiClient.isReasoningModel("MiniMax-M3"))
        assertTrue(AiClient.isReasoningModel("MiniMax-M3.1-Flash"))
        assertTrue(AiClient.isReasoningModel("MiniMax-M2"))
        assertTrue(AiClient.isReasoningModel("MiniMax-M1"))
        assertTrue(AiClient.isReasoningModel("gpt-5-thinking"))
        assertTrue(!AiClient.isReasoningModel("MiniMax-Text-01"))
        assertTrue(!AiClient.isReasoningModel("abab6.5s-chat"))
    }

    @Test
    fun `合并结果被截断也能解析`() {
        val truncated = """{"groups":[{"topic":"APEC峰会","representative":2,"members":[1,2],"reason":"同一次会议"}],"note":"合并了1组"""
        val dto = AiClient.extractMerge(truncated)
        assertNotNull("截断的合并结果应该能修复", dto)
        assertEquals(1, dto!!.groups.size)
        assertEquals("APEC峰会", dto.groups[0].topic)
        assertEquals(2, dto.groups[0].representative)
    }

    @Test
    fun `合并结果里序号写成字符串也能解析`() {
        val dto = AiClient.extractMerge("""{"groups":[{"topic":"A","representative":"3","members":["1","3"]}],"note":"n"}""")
        assertNotNull(dto)
        assertEquals(3, dto!!.groups[0].representative)
        assertEquals(listOf(1, 3), dto.groups[0].members)
    }
}
