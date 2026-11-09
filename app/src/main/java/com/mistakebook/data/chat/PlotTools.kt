package com.mistakebook.data.chat

import com.mistakebook.math.FunctionPlotter
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
 * 函数图像工具（issue #54）。
 *
 * 有了它，「画个函数图像」从**模型凭感觉画**变成**算出来的图**：
 * 模型只需给出表达式与范围，采样、刻度、断线（渐近线）都由
 * [FunctionPlotter] 精确完成，图**确定性**地插进回答正文
 * （见 `ChatGenerationService` 的 plot_function 分支——不让模型转述几万
 * 字符的 SVG，那样既烧 token 又容易被截断）。
 *
 * 与本工具配套的提示词约束写在 `ChatPrompts.SYSTEM`：
 * 函数图像**必须**走本工具，不许手写 SVG 曲线，也不许用生图模型。
 */
object PlotTools {

    const val PLOT_FUNCTION = "plot_function"

    fun handles(name: String): Boolean = name == PLOT_FUNCTION

    fun all(): List<LlmTool> = listOf(tool())

    fun tool(): LlmTool = LlmTool(
        type = "function",
        function = ToolFunction(
            name = PLOT_FUNCTION,
            description = "把数学函数表达式画成**精确**的函数图像（本项目自己算采样点，" +
                "坐标轴、刻度、单调性、极值、交点位置都是真的，不是画个大概）。" +
                "凡是用户要求「画函数图像 / 看函数形状 / 判断单调性、极值、交点、渐近线」，" +
                "或者你自己需要借助图像讲清楚时，**必须调用本工具**：" +
                "不要手写 SVG 曲线（位置靠猜，会画错），也不要用生图模型（刻度对不上）。" +
                "支持三种写法：① 显函数 y=f(x)，`y=` 可省略：x^2+2x、y=sin(x)、1/x；" +
                "② 隐函数方程 F(x,y)=0：x^2+y^2=1、x^2-y^2=1、x=y^2；" +
                "③ 极坐标 r=f(θ)：r=1+cos(θ)、r=sin(3θ)。" +
                "变量支持 x、y、θ（也认 theta / t），常量 pi/e，运算 + - * / % ^ 与隐式乘法" +
                "（2x、3sin(x)、(x+1)(x-1)），" +
                "函数 sqrt/abs/sin/cos/tan/asin/acos/atan/exp/ln/log(以10为底)/log2/floor/ceil/round/sign，" +
                "三角函数用弧度制。一次最多 4 条曲线；" +
                "**务必按题目给合理的范围**（例如题目讨论 x∈[-3,1] 就传 x_min=-3、x_max=1），" +
                "不传则 x 默认 -10..10；画极坐标时 x_min/x_max 表示 **θ 的范围**" +
                "（默认 0 到 2π，要画多圈就传更大的 θ 范围）。" +
                "隐函数靠网格求等值线，范围给准一点（把曲线所在的那段框进去）更清楚，" +
                "范围太大曲线会变细、太小可能看不到解。" +
                "调用后图会自动出现在你的回答里，你只需要用文字说明图上看到什么。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "expressions",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "曲线表达式，三种写法都行：显函数 x^2+2x、" +
                                                "y=sin(x)、1/x；隐函数方程 x^2+y^2=1、" +
                                                "x=y^2；极坐标 r=1+cos(θ)。最多 4 条。"
                                        )
                                    }
                                )
                                put(
                                    "description",
                                    "要画的函数表达式列表（从左到右依次是曲线顺序）"
                                )
                            }
                        )
                        put(
                            "labels",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject { put("type", "string") }
                                )
                                put("description", "图例名（可选），与 expressions 一一对应，如 y=x^2+2x")
                            }
                        )
                        put(
                            "x_min",
                            buildJsonObject {
                                put("type", "number")
                                put(
                                    "description",
                                    "x 轴左端（可选，默认 -10）；画极坐标时表示 θ 的下界（默认 0）"
                                )
                            }
                        )
                        put(
                            "x_max",
                            buildJsonObject {
                                put("type", "number")
                                put(
                                    "description",
                                    "x 轴右端（可选，默认 10）；画极坐标时表示 θ 的上界（默认 2π）"
                                )
                            }
                        )
                        put(
                            "y_min",
                            buildJsonObject {
                                put("type", "number")
                                put("description", "y 轴下端（可选，默认按函数取值自动定）")
                            }
                        )
                        put(
                            "y_max",
                            buildJsonObject {
                                put("type", "number")
                                put("description", "y 轴上端（可选，默认按函数取值自动定）")
                            }
                        )
                        put(
                            "title",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "图标题（可选），如 f(x)=x^2+2x 的图像")
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("expressions")) })
            }
        )
    )

    /**
     * 解析工具参数。
     *
     * 容忍度按「模型实际会怎么写」来定：表达式可能写成数组，也可能只给一条
     * 字符串；数值可能是 number，也可能是 `"-3"` 这样的字符串——两种都认，
     * 不认就直接报「参数没法用」，让模型重来一次（比崩掉好）。
     */
    fun parse(arguments: String): FunctionPlotter.Request? = runCatching {
        val root = Json.parseToJsonElement(arguments).jsonObject
        val expressions = when (val element = root["expressions"] ?: root["expression"]) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
            null -> emptyList()
            else -> listOfNotNull(element.jsonPrimitive.contentOrNull)
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (expressions.isEmpty()) return@runCatching null

        val labels = (root["labels"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        val curves = expressions.mapIndexed { index, expression ->
            FunctionPlotter.Curve(
                expression = expression,
                label = labels.getOrNull(index)?.trim().orEmpty()
            )
        }
        FunctionPlotter.Request(
            curves = curves,
            xMin = number(root["x_min"]),
            xMax = number(root["x_max"]),
            yMin = number(root["y_min"]),
            yMax = number(root["y_max"]),
            title = (root["title"] as? JsonPrimitive)?.contentOrNull
        )
    }.getOrNull()

    private fun number(element: kotlinx.serialization.json.JsonElement?): Double? =
        (element as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()

    /**
     * 写给模型看的一句摘要：画了哪几条、取用了什么范围。
     *
     * 模型据此对用户说明「画的是哪条曲线、在什么区间」，不必回读 SVG 源码。
     */
    fun summary(curves: List<FunctionPlotter.Curve>, ok: FunctionPlotter.Result.Ok): String {
        val shown = curves.take(FunctionPlotter.MAX_CURVES).joinToString("、") { it.expression }
        val ignored = if (curves.size > FunctionPlotter.MAX_CURVES) {
            "（超过 ${FunctionPlotter.MAX_CURVES} 条的已忽略）"
        } else {
            ""
        }
        return "$shown$ignored；x ∈ [${short(ok.xMin)}, ${short(ok.xMax)}]，" +
            "y ∈ [${short(ok.yMin)}, ${short(ok.yMax)}]"
    }

    private fun short(value: Double): String = if (value == Math.floor(value) &&
        Math.abs(value) < 1e6
    ) {
        value.toLong().toString()
    } else {
        java.lang.String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }

    /** 包成 ```svg 围栏：客户端的 [com.mistakebook.markdown.MarkdownParser] 只认这个标记。 */
    fun fence(svg: String): String = "```svg\n$svg\n```"
}
