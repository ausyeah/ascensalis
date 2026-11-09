package com.mistakebook.data.chat

import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.Option
import com.mistakebook.domain.QuestionDraft
import com.mistakebook.net.llm.LlmTool
import com.mistakebook.net.llm.ToolFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 「对话 → 错题本」工具（issue #55）。
 *
 * 用户原话：「可以把当前对话中的提问和解答过程，整理成错题本的规格，并将其
 * 制作成一套可由 AI 调用的工作流工具。这样就能取消『加入错题』的备注，直接
 * 让 AI 调用该工具，将其添加到我主页的错题本内。」
 *
 * 原来只有一条路：用户点「存入题目备注」，把回答塞进**当前这道题**的备注里
 * ——前提是这次对话已经挂着一道题，而且笔记不是错题。现在模型可以自己判断
 * 「这一问一答本身值得收进错题本」，直接把题**建出来**（无原图，与手动录入
 * 同一约定：`imagePath = ""`）。
 *
 * 入库复用 [com.mistakebook.data.repos.QuestionRepository.save]，学科按名字
 * 建/找、复习档期、标签同步都由它负责——工具层只负责把参数整理成
 * [QuestionDraft]，不复制任何入库逻辑。
 */
object SaveQuestionTools {

    const val SAVE_QUESTION = "save_question"

    fun handles(name: String): Boolean = name == SAVE_QUESTION

    fun all(): List<LlmTool> = listOf(tool())

    fun tool(): LlmTool = LlmTool(
        type = "function",
        function = ToolFunction(
            name = SAVE_QUESTION,
            description = "把这次对话里讲清楚的一道题，整理成错题本的一条记录存进用户的错题本" +
                "（题干、答案、解析、学科、知识点、错因、难度都能带上）。" +
                "用户说「把这题加入错题本 / 存成错题 / 记到错题本里 / 这道题我要复习」时调用。" +
                "调用前请先把题干与答案整理干净（公式用 LaTeX 写法，如 \$x^2+2x\$），" +
                "不要照抄整段对话；不确定的字段留空即可，不要编造。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "stem",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "题干（必填）：完整、可独立作答的题目描述。" +
                                        "公式写成 LaTeX，如「求 \$f(x)=x^2+2x\$ 在 \$[-3,1]\$ 上的最小值」。"
                                )
                            }
                        )
                        put(
                            "answer",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "答案（简洁给出最终结果，可带必要单位）")
                            }
                        )
                        put(
                            "analysis",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "解析：解法步骤与关键理由，公式用 LaTeX")
                            }
                        )
                        put(
                            "options",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject { put("type", "string") }
                                )
                                put(
                                    "description",
                                    "选项（仅选择题给）。可以写「A. 1」这种带标号的，" +
                                        "也可以只写内容——标号会自动整理。"
                                )
                            }
                        )
                        put(
                            "subject",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "学科名，如「数学」「物理」「通信原理」；不确定就留空")
                            }
                        )
                        put(
                            "knowledge_points",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject { put("type", "string") }
                                )
                                put("description", "知识点，如 [\"二次函数\",\"最值\"]，最多 6 个")
                            }
                        )
                        put(
                            "error_reason",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "错因，只能取：概念不清 / 计算失误 / 审题错误 / 思路不会 / 粗心遗漏 / 其他"
                                )
                            }
                        )
                        put(
                            "difficulty",
                            buildJsonObject {
                                put("type", "integer")
                                put("description", "难度 1~5（5 最难）；不确定写 3")
                            }
                        )
                        put(
                            "title",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "列表里显示的短标题（可选），如「切比雪夫不等式估计上界」")
                            }
                        )
                        put(
                            "note",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "备注（可选）：易错点、提醒自己的话")
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("stem")) })
            }
        )
    )

    /**
     * 参数 → 入库草稿。
     *
     * 题干为空就返回 null（[QuestionDraft.isValid] 的同一道门槛：没题干入库
     * 出来是一张空卡片，宁可让模型补一次）。
     */
    fun parse(arguments: String): QuestionDraft? = runCatching {
        val root = Json.parseToJsonElement(arguments).jsonObject
        val stem = (root["stem"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (stem.isEmpty()) return@runCatching null

        // 手动录入的同一约定：没有原图就传空串（QuestionRepository 里有守卫）。
        QuestionDraft(
            imagePath = "",
            mineruMarkdown = "",
            subjectName = (root["subject"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            stem = stem,
            options = parseOptions(root["options"]),
            answer = (root["answer"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            analysis = (root["analysis"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            title = (root["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            knowledgePoints = stringList(root["knowledge_points"], limit = 6),
            errorReason = errorReason((root["error_reason"] as? JsonPrimitive)?.contentOrNull),
            difficulty = ((root["difficulty"] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.toIntOrNull() ?: 3).coerceIn(1, 5),
            note = (root["note"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        )
    }.getOrNull()

    /**
     * 选项归一：带标号的（`A. 1`、`B、2`、`C) 3`）拆成标号 + 内容，
     * 没标号的按下标补 A/B/C…——错题本详情页是按「标号 + 内容」两列显示的，
     * 把 `A. 1` 整个塞进内容列会显示成「A  A. 1」。
     */
    fun parseOptions(element: kotlinx.serialization.json.JsonElement?): List<Option> {
        val raw = when (element) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (raw.isEmpty()) return emptyList()
        val labels = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        return raw.mapIndexed { index, text ->
            val match = LABELED.matchEntire(text)
            if (match != null) {
                Option(label = match.groupValues[1].uppercase(), text = match.groupValues[2].trim())
            } else {
                Option(label = labels.getOrElse(index) { "${index + 1}" }, text = text)
            }
        }
    }

    /**
     * `A. 内容` / `A、内容` / `A) 内容` / `(A) 内容` 四种常见写法。
     *
     * 标号后面**必须是分隔符或空白**才算标号——否则「Calculate x」会被读成
     * 「C」+「alculate x」，把选项内容咬掉一个字。认不出就按下标补标号。
     */
    private val LABELED = Regex("^[(\\[]?([A-Ha-h])[)\\]]?(?=[\\s.、,:：)])[\\s.、,:：)]+(.+)$")

    private fun stringList(element: kotlinx.serialization.json.JsonElement?, limit: Int): List<String> {
        val raw = when (element) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            else -> emptyList()
        }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(limit)
    }

    /**
     * 错因：先按中文标签认（模型更可能按标签写），再按枚举名认（`CONCEPT`），
     * 都不认就归「其他」——不能因为一个标签写歪就把整道题的错因丢掉。
     */
    fun errorReason(text: String?): ErrorReason {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return ErrorReason.OTHER
        ErrorReason.entries.firstOrNull { it.label == value }?.let { return it }
        return ErrorReason.fromNameOrDefault(value.uppercase())
    }
}
