package com.mistakebook.mindmap

import kotlinx.serialization.Serializable

/**
 * 思维导图节点（F4）。只保留 markmap 渲染需要的两个字段，
 * 序列化后直接注进 WebView 页面。
 */
@Serializable
data class MindmapNode(
    val content: String,
    val children: List<MindmapNode> = emptyList()
)

/**
 * LLM 输出的层级 markdown → 节点树。纯函数，脱网可单测。
 *
 * ## 支持的 markdown 子集（模型容易稳定输出的最小集）
 * - `#`~`######` 标题：标题级别即树的层级；
 * - `-` / `*` / `+` 列表：按 2 空格缩进嵌套，挂在最近的标题（或父列表项）下。
 *
 * ## 为什么不用 HTML 解析器式的完整 markdown
 * 输入是自家提示词约束出来的 LLM 产物，不是任意网页文本；
 * 支持的语法越少，模型的输出越稳，解析器的行为也越可预测。
 */
object MindmapParser {

    /** 全部提供的薄弱知识点都必须出现在产物里（防模型偷工减料）。 */
    fun missingKnowledgePoints(markdown: String, required: List<String>): List<String> =
        required.filter { !markdown.contains(it.trim()) }

    /**
     * 解析为树。多个一级标题会包进一个以 [fallbackRootName] 命名的根节点
     * （markmap 需要单根；学科名做根比第一个章节做根更自然）。
     */
    fun parse(markdown: String, fallbackRootName: String): MindmapNode {
        // 可变构建再一次性冻结成不可变树：叶子用 mutableListOf 收集，
        // 全部行处理完后 toFrozen 递归转换
        data class MNode(
            val content: String,
            val children: MutableList<MNode> = mutableListOf()
        )

        fun toFrozen(node: MNode): MindmapNode =
            MindmapNode(content = node.content, children = node.children.map(::toFrozen))

        val mroots = mutableListOf<MNode>()
        val mstack = ArrayDeque<Pair<Int, MNode>>()
        // 最近一个标题的层级；-1 表示还没出现过标题（根级列表挂 0 层）。
        // 列表深度必须相对它计算：无缩进列表跟在 ## 后面应挂在 ## 之下，
        // 绝对缩进算不出这个关系（那是第一个测试用例抓出来的 bug）。
        var lastHeadingDepth = -1

        fun mattach(depth: Int, node: MNode) {
            while (mstack.isNotEmpty() && mstack.last().first >= depth) mstack.removeLast()
            val parent = mstack.lastOrNull()?.second
            if (parent == null) mroots += node else parent.children += node
            mstack.addLast(depth to node)
        }

        markdown.lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@forEach
            val heading = Regex("^(#{1,6})\\s+(.+)$").find(line)
            if (heading != null) {
                val depth = heading.groupValues[1].length - 1 // # → 0
                lastHeadingDepth = depth
                mattach(depth, MNode(clean(heading.groupValues[2])))
                return@forEach
            }
            val item = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
            if (item != null) {
                val indent = line.length - line.trimStart().length
                val depth = lastHeadingDepth + 1 + indent / 2
                mattach(depth, MNode(clean(item.groupValues[1])))
            }
            // 既非标题也非列表的行（前言/说明）直接丢弃——树只吃结构行
        }

        return when {
            mroots.isEmpty() -> MindmapNode(content = fallbackRootName)
            mroots.size == 1 -> toFrozen(mroots[0])
            else -> MindmapNode(
                content = fallbackRootName,
                children = mroots.map(::toFrozen)
            )
        }
    }

    /** 去掉常见的 markdown 强调符号（**x** → x）；模型爱加，导图节点不需要。 */
    private fun clean(text: String): String =
        text.replace(Regex("""\*\*(.+?)\*\*"""), "$1").trim()
}
