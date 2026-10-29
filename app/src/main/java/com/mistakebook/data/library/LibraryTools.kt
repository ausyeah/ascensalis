package com.mistakebook.data.library

import com.mistakebook.net.llm.LlmTool
import com.mistakebook.net.llm.ToolFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 资料库的**对话工具**（v0.0.57 接入 tool loop）。
 *
 * ## 为什么需要它
 *
 * [LibraryTool] 早就写好了 `library_list` / `library_read`，但一直没接进
 * 对话引擎——用户在对话里说「读一下资料库里那份 PDF」，模型既看不到清单、
 * 也没有工具可调，只能回「我没有读取资料库的能力」。老版的降级路径是
 * `@资料库` 选文件把原文塞进当轮正文，只覆盖「用户手动选过」的情况，
 * 且会把原文糊进气泡（v0.0.57 一并修掉）。
 *
 * 现在把「读」这两个工具接进通用 tool loop：模型可以自己列目录、按需读，
 * 和 web_search / describe_image 同一条执行路径。
 *
 * ## 为什么只开放读
 *
 * `LibraryTool` 里还有 write / edit / delete。在没有「确认后执行」的交互前
 * 把写工具交给模型，误写无法撤回；读是无副作用的，风险为零。
 * 等有了二次确认 UI 再放开写。
 */
object LibraryTools {

    const val LIST = "library_list"
    const val READ = "library_read"

    /** 下发给模型的两个只读工具。 */
    fun all(): List<LlmTool> = listOf(listTool(), readTool())

    private fun listTool(): LlmTool = LlmTool(
        function = ToolFunction(
            name = LIST,
            description = "列出用户「资料库」里的文件（文件名 + 大小）。" +
                "当用户提到资料库、或你不确定有哪些资料可用时调用。" +
                "只需要一次就能拿到清单，不要重复调用。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
            }
        )
    )

    private fun readTool(): LlmTool = LlmTool(
        function = ToolFunction(
            name = READ,
            description = "读取资料库中某个文件的正文。docx / pdf / xlsx 会自动抽取文字；" +
                "超过 50KB 会截断并注明。先用 library_list 拿到准确文件名再调用。" +
                "只在用户的问题确实需要该资料时读，不要无差别把所有文件都读一遍。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "name",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "资料库里的文件名（含扩展名，必须与清单一致）")
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("name")) })
            }
        )
    )

    /** 是否是我们接管的资料库工具名。 */
    fun handles(name: String): Boolean = name == LIST || name == READ

    /**
     * 执行一次资料库工具调用，返回给模型看的文本。
     *
     * @param root 资料库目录。
     * @param arguments 模型给的 arguments JSON（可能不合法，内部兜底）。
     */
    fun execute(root: File, name: String, arguments: String): String {
        val args = if (name == READ) {
            val fileName = nameArg(arguments)
                ?: return "资料库工具执行失败：缺少 name 参数。请先用 library_list 查看准确文件名。"
            mapOf("name" to fileName)
        } else {
            emptyMap()
        }
        val result = LibraryTool.dispatch(root, name, args)
        return if (result.ok) result.message else "资料库工具执行失败：${result.message}"
    }

    /** 从 arguments 里取文件名；取不到返回 null，由执行层给出「缺少 name」提示。 */
    private fun nameArg(arguments: String): String? {
        if (arguments.isBlank()) return null
        return runCatching {
            Json.parseToJsonElement(arguments).jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
