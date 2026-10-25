package com.mistakebook.net.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 多模态工具：识图（[DESCRIBE_IMAGE]）与生图（[GENERATE_IMAGE]）。
 *
 * ## 为什么做成工具而不是「每轮都塞」
 *
 * 识图以前是**预处理**：用户发图，客户端先把图丢给 MinerU，
 * 把 OCR 出来的整段原文贴在消息下面再发给模型。三个问题：
 * 1. **该读的不该读的都读**——用户只是顺手发张无关的图，也烧一次额度；
 * 2. **结果直接暴露给用户**——对话底部挂一大段识别原文，很生硬；
 * 3. **只能出 OCR 文字**——问「这图里画了什么」答不上来。
 *
 * 做成工具后：模型自己决定要不要看图、看图要什么（[DESCRIBE_IMAGE] 带
 * question 参数），结果只进上下文、不直接糊在界面上；识图能力由用户在
 * 设置里单独配一套模型提供（也可用既有的 MinerU）。
 *
 * 生图同理：模型判断「这里画个图更好懂」时自己调，
 * 图以 Markdown 图片的形式出现在回答里。
 */
object MediaTools {

    const val DESCRIBE_IMAGE = "describe_image"
    const val GENERATE_IMAGE = "generate_image"

    /** 一次回答里每种媒体工具各允许调几次（防模型反复调）。 */
    const val MAX_CALLS_PER_TOOL = 2

    fun describeImageTool(): LlmTool = LlmTool(
        function = ToolFunction(
            name = DESCRIBE_IMAGE,
            description = "读取并理解用户发来的图片。" +
                "当你看不到图片内容、或需要看清图片里的文字、公式、图形时调用。" +
                "用 question 说明你想从图里知道什么（例如「完整转录图中的题目」" +
                "或「这张示意图表达了什么」）。" +
                "用户没发图片、或问题与图片无关时不要调用。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "question",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "你想从这张图里知道什么")
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("question")) })
            }
        )
    )

    fun generateImageTool(): LlmTool = LlmTool(
        function = ToolFunction(
            name = GENERATE_IMAGE,
            description = "根据一段英文描述生成一张示意图，用于帮助理解。" +
                "仅当一张图确实比文字更好懂时才调用（场景示意、结构示意、流程示意）；" +
                "数学公式、几何图形、函数图像不要生图——那些应该由你直接给出 SVG 或推导。" +
                "prompt 用英文，不超过 50 个词。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "prompt",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "英文描述，50 词以内")
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("prompt")) })
            }
        )
    )

    /**
     * 按当前配置组装要下发给模型的媒体工具。
     *
     * - 识图工具：配了专门的识图模型就给（否则给了也执行不了）；
     * - 生图工具：配了生图渠道就给。
     */
    fun all(visionConfigured: Boolean, imageConfigured: Boolean): List<LlmTool> = buildList {
        if (visionConfigured) add(describeImageTool())
        if (imageConfigured) add(generateImageTool())
    }

    /**
     * 从模型的 arguments JSON 里取一个字符串字段（**纯函数，可单测**）。
     *
     * 模型给 arguments 时常带多余空白、包裹引号、偶尔给成对象——
     * 取不到就返回 null，让调用方按「参数缺失」处理。
     */
    fun argument(arguments: String, key: String): String? {
        if (arguments.isBlank()) return null
        val element = runCatching { Json.parseToJsonElement(arguments) }.getOrNull() ?: return null
        val value = runCatching { element.jsonObject[key] }.getOrNull() ?: return null
        return runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
