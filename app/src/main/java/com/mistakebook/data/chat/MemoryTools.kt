package com.mistakebook.data.chat

import com.mistakebook.net.llm.LlmTool
import com.mistakebook.net.llm.ToolFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 记忆工具（v0.0.128，issue #42）。
 *
 * 用户：「对话中没有记忆工具，也不会根据对话内容总结部分重要信息丢进记忆库」。
 *
 * 记忆库早就存在（设置 → 记忆，单文档存 DataStore，每轮注入系统提示），
 * 但**只有用户手动在记忆页里写**才能改——对话里的模型完全不知道它的存在，
 * 用户说「记住我喜欢用拉格朗日法」也只能靠自己在设置里再打一遍。
 *
 * 这里补上工具：模型判断这一句里有长期有效的信息（身份/目标/偏好/约束），
 * 就调用 [SAVE] 写一条。写入走 [MemoryMerge] 的纯文本合并（不重跑 LLM），
 * 只做去重与追加——比「整篇重写」便宜两个数量级，也不会把已有内容改坏。
 */
object MemoryTools {

    const val SAVE = "save_memory"

    fun all(): List<LlmTool> = listOf(saveTool())

    private fun saveTool(): LlmTool = LlmTool(
        function = ToolFunction(
            name = SAVE,
            description = "把用户**长期有效**的信息写进记忆库（身份、目标、偏好、习惯、约束）。" +
                "只在用户透露这类稳定信息时调用，例如「我是考研党」「以后都用拉格朗日法」" +
                "「别给我推荐网课」。一次性的具体题目、临时需求、闲聊不要写。" +
                "每条一句话、不超过 40 字；同一件事已在记忆里就不要重复写。" +
                "调用后不需要再复述你写了什么。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "facts",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "一条长期记忆，一句话，不超过 40 字")
                                    }
                                )
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("facts")) })
            }
        )
    )

    /** 取出要写入的事实列表（容忍字符串/数组两种写法，去掉空项）。 */
    fun parseFacts(arguments: String): List<String> = runCatching {
        val root = Json.parseToJsonElement(arguments).jsonObject
        val element = root["facts"] ?: return@runCatching emptyList()
        val raw = when {
            element is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> listOfNotNull(element.jsonPrimitive.contentOrNull)
        }
        raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }.getOrDefault(emptyList())
}