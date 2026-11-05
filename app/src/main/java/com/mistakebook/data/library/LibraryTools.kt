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

    /** 语义 + 关键词混合检索（v0.0.120）。 */
    const val SEARCH = "library_search"

    /** 下发给模型的三个只读工具。 */
    fun all(): List<LlmTool> = listOf(listTool(), readTool(), searchTool())

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

    /**
     * 检索工具（v0.0.120）。
     *
     * 为什么要有它：只有 list + read 时，模型回答「资料库里关于 X 的内容」
     * 必须先列目录、再整份读——一次问答就能把几万字塞进上下文，既贵又容易
     * 撞上下文上限。检索只回最相关的几段，才是资料库该有的用法。
     */
    private fun searchTool(): LlmTool = LlmTool(
        function = ToolFunction(
            name = SEARCH,
            description = "在资料库里做混合检索（语义相似 + 关键词命中），返回最相关的几段正文。" +
                "只要用户的问题可能落在资料库的内容里，就先调用本工具定位段落，" +
                "不要用 library_list + library_read 把整份文件读进上下文。" +
                "资料库没有建立索引时会返回提示，无需重试。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "检索词或一句话问题。写成完整的语义描述比堆关键词更准。")
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            }
        )
    )

    /** 取出检索参数。 */
    fun parseSearchQuery(arguments: String): String? = runCatching {
        Json.parseToJsonElement(arguments).jsonObject["query"]
            ?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 是否是我们接管的资料库工具名。 */
    fun handles(name: String): Boolean = name == LIST || name == READ || name == SEARCH

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

    /**
     * 检索结果 → 给模型看的文本。
     *
     * 三条规则都有原因：
     * - 只保留前 [MAX_SEARCH_CHARS] 个字符：检索回 6 段、每段几百字，不设上限
     *   就等于把整份资料又塞回去了，白做了检索；
     * - 每段都带文件名与段号：模型转述时能说清「来自哪份资料的哪一段」；
     * - 空结果给可执行提示而不是「无结果」：多数时候是没建索引，模型据此
     *   可以提示用户去资料库点「建立索引」，而不是硬说资料里没有。
     */
    fun formatHits(
        query: String,
        hits: List<LibrarySearchFusion.Hit>,
        maxChars: Int = MAX_SEARCH_CHARS
    ): String {
        if (hits.isEmpty()) {
            return "资料库没有检索到与「$query」相关的内容。" +
                "可能还没建立索引（资料库页点「建立索引」），也可能资料里确实没有这个话题。"
        }
        val builder = StringBuilder()
        builder.append("资料库检索「").append(query).append("」命中 ").append(hits.size)
            .append(" 段（按相关度排序）：\n")
        hits.forEachIndexed { index, hit ->
            val block = "\n[" + (index + 1) + "] " + hit.docName + " · 第 " + hit.chunkIndex +
                " 段 · 相关度 " + String.format("%.2f", hit.score) + "\n" +
                hit.text.trim() + "\n"
            if (builder.length + block.length > maxChars) {
                builder.append("\n（其余段落已省略，需要更具体的内容请缩小检索范围）\n")
                return builder.toString()
            }
            builder.append(block)
        }
        return builder.toString()
    }

    /** 单次检索结果回传给模型的字符上限。 */
    const val MAX_SEARCH_CHARS = 3000
}
