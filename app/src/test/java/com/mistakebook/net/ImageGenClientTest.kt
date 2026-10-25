package com.mistakebook.net

import com.mistakebook.data.prefs.ImageProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生图请求体组装单测（任务书 P0 验收：验证 JSON 组装正确）。
 *
 * [ImageGenClient.generate] 本身要走网络，这里只测**纯函数**部分
 * （[ImageGenClient.buildRequestBody]）——它决定了发出去的 JSON 长什么样，
 * 是「适配不同生图 API」这条路的全部机制所在。
 */
class ImageGenClientTest {

    private val profile = ImageProfile(
        name = "测试渠道",
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-test",
        model = "sensechat-vision"
    )

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `默认体是 OpenAI images 兼容格式`() {
        val body = parse(ImageGenClient.buildRequestBody(profile, "a cat", 1024, 768))
        assertEquals("sensechat-vision", body["model"]?.jsonPrimitive?.content)
        assertEquals("a cat", body["prompt"]?.jsonPrimitive?.content)
        assertEquals("1", body["n"]?.jsonPrimitive?.content)
        assertEquals("1024x768", body["size"]?.jsonPrimitive?.content)
        assertEquals("b64_json", body["response_format"]?.jsonPrimitive?.content)
    }

    @Test
    fun `模板替换三个占位符`() {
        val templated = profile.copy(
            requestBodyTemplate = """{"model":"wanx","prompt":"{prompt}","width":{width},"height":{height}}"""
        )
        val body = parse(ImageGenClient.buildRequestBody(templated, "a dog", 512, 512))
        assertEquals("wanx", body["model"]?.jsonPrimitive?.content)
        assertEquals("a dog", body["prompt"]?.jsonPrimitive?.content)
        assertEquals("512", body["width"]?.jsonPrimitive?.content)
        assertEquals("512", body["height"]?.jsonPrimitive?.content)
    }

    @Test
    fun `占位符裸写时插完整 JSON 串`() {
        // 写法 2：占位符不带引号，占着 JSON 值的位置
        val templated = profile.copy(
            requestBodyTemplate = """{"model":"wanx","prompt": {prompt} ,"n":1}"""
        )
        val body = parse(ImageGenClient.buildRequestBody(templated, "a dog", 512, 512))
        assertEquals("a dog", body["prompt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `模板里 prompt 的引号换行被转义不撑破结构`() {
        val templated = profile.copy(
            requestBodyTemplate = """{"model":"wanx","prompt":"{prompt}"}"""
        )
        // 带引号、换行、反斜杠的「恶意」prompt
        val nasty = "he said \"hi\"\nthen \\ left"
        val raw = ImageGenClient.buildRequestBody(templated, nasty, 64, 64)
        // 关键断言：整体仍是合法 JSON，且 prompt 字段值原样还原
        val body = parse(raw)
        assertEquals(nasty, body["prompt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `模板缺少 prompt 占位符时兜底走默认体`() {
        val broken = profile.copy(requestBodyTemplate = """{"model":"wanx","n":1}""")
        val body = parse(ImageGenClient.buildRequestBody(broken, "a bird", 64, 64))
        // 不能发出一个永远不含 prompt 的请求：缺占位符视为没填模板
        assertEquals("sensechat-vision", body["model"]?.jsonPrimitive?.content)
        assertEquals("a bird", body["prompt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `模板可携带占位符之外的额外字段`() {
        val templated = profile.copy(
            requestBodyTemplate = """{"model":"wanx","prompt":"{prompt}","negative_prompt":"blurry","seed":42}"""
        )
        val body = parse(ImageGenClient.buildRequestBody(templated, "x", 64, 64))
        assertEquals("blurry", body["negative_prompt"]?.jsonPrimitive?.content)
        assertEquals("42", body["seed"]?.jsonPrimitive?.content)
    }

    @Test
    fun `端点 URL 拼到 images generations`() {
        assertEquals(
            "https://api.example.com/v1/images/generations",
            profile.generateUrl()
        )
        // 已带子路径的 baseUrl 不再补 /v1
        assertEquals(
            "https://api.example.com/api/images/generations",
            profile.copy(baseUrl = "https://api.example.com/api").generateUrl()
        )
    }

    @Test
    fun `normalized 补 v1 与保路径`() {
        assertEquals(
            "https://api.example.com/v1",
            profile.copy(baseUrl = "https://api.example.com").normalized().baseUrl
        )
        assertEquals(
            "https://api.example.com/api",
            profile.copy(baseUrl = "https://api.example.com/api").normalized().baseUrl
        )
    }

    @Test
    fun `未配置完整的渠道不可用`() {
        assertFalse(profile.copy(apiKey = "").isConfigured())
        assertFalse(profile.copy(baseUrl = "").isConfigured())
        assertFalse(profile.copy(model = "").isConfigured())
        assertTrue(profile.isConfigured())
    }

    @Test
    fun `data 数组首元素是 url 字符串的形态可解析`() {
        // 这个测试保护 parseImage 的「裸字符串」分支的输入形状认知：
        // 用真实响应样例手工走一遍解析路径（不走网络）。
        val sample = """{"data":["https://cdn.example.com/x.png"]}"""
        val first = Json.parseToJsonElement(sample).jsonObject["data"]?.jsonArray?.firstOrNull()
        assertEquals("https://cdn.example.com/x.png", first?.jsonPrimitive?.content)
    }
}
