package com.mistakebook.data.library

import java.io.File

/**
 * 资料库 AI 文件工具（v0.0.51 T6）。
 *
 * 与 WebSearch 同层的对话工具接口。当前版本的对话引擎（ChatGenerationService）
 * 还没有通用 tool calling 循环，本类的公开 API 按**未来接入的形状**写好：
 * 每个操作 = 参数校验 → [LibraryStore] 执行 → 返回给模型看的结构化文本。
 * 框架合入后把 [dispatch] 挂进工具路由即可，无需再改语义。
 *
 * 降级期（本版）实际生效的只有「读」：`@资料库` 选文件把内容注入当轮
 * 上下文（ChatViewModel）。写操作不开放给模型——没有工具循环时无从
 * 确认，误写无法撤回。
 */
object LibraryTool {

    /** 一次工具调用的结果：给模型看的文本（已含成功/失败与必要的元信息）。 */
    data class Result(val ok: Boolean, val message: String)

    /** 工具名清单（供未来工具路由注册与提示词生成用）。 */
    val TOOL_NAMES = listOf(
        "library_list", "library_read", "library_write", "library_edit", "library_delete"
    )

    /**
     * 统一分发入口。未来 tool calling 框架把模型给的
     * `{name, arguments}` 直接递进来即可。
     *
     * @param root 资料库目录（LibraryStore.ensureDir 的产物）。
     */
    fun dispatch(root: File, name: String, args: Map<String, String>): Result = try {
        when (name) {
            "library_list" -> list(root)
            "library_read" -> read(root, args["name"].orEmpty())
            "library_write" -> write(root, args["name"].orEmpty(), args["content"].orEmpty())
            "library_edit" -> edit(
                root,
                args["name"].orEmpty(),
                args["content"].orEmpty(),
                args["old"].orEmpty()
            )
            "library_delete" -> delete(root, args["name"].orEmpty())
            else -> Result(false, "未知工具: $name")
        }
    } catch (t: Throwable) {
        Result(false, "工具执行失败: ${t.message ?: t.javaClass.simpleName}")
    }

    private fun list(root: File): Result {
        val files = LibraryStore.list(root)
        if (files.isEmpty()) return Result(true, "资料库为空。")
        val body = files.joinToString("\n") { f ->
            "- ${f.name}（${f.length()} 字节）"
        }
        return Result(true, "资料库共 ${files.size} 个文件：\n$body")
    }

    private fun read(root: File, name: String): Result {
        val file = LibraryStore.resolveLibraryPath(root, name)
            ?: return Result(false, "非法文件名或类型: $name")
        val (text, truncated) = LibraryStore.read(file)
            ?: return Result(false, "文件不存在: $name")
        val note = if (truncated) {
            "\n（内容超过 50KB 已截断）"
        } else ""
        return Result(true, "《$name》内容如下：\n$text$note")
    }

    private fun write(root: File, name: String, content: String): Result {
        if (content.isBlank()) return Result(false, "写入内容为空。")
        val file = LibraryStore.resolveLibraryPath(root, name)
            ?: return Result(false, "非法文件名或类型: $name")
        if (LibraryStore.isBinaryDoc(file)) {
            return Result(false, ".docx / .pdf 是二进制文档，不支持模型写入，只能读取。")
        }
        val bytes = LibraryStore.write(file, content)
        return Result(true, "已写入 $name（$bytes 字节）。")
    }

    /**
     * 编辑：`old` 非空时做**片段替换**（把第一处 old 换成 content）；
     * 否则**追加**。替换目标找不到时失败，绝不静默。
     */
    private fun edit(root: File, name: String, content: String, old: String): Result {
        val file = LibraryStore.resolveLibraryPath(root, name)
            ?: return Result(false, "非法文件名或类型: $name")
        if (!file.isFile) return Result(false, "文件不存在: $name")
        if (LibraryStore.isBinaryDoc(file)) {
            return Result(false, ".docx / .pdf 是二进制文档，不支持编辑或追加，只能读取。")
        }
        if (old.isNotBlank()) {
            val text = file.readText(Charsets.UTF_8)
            if (!text.contains(old)) return Result(false, "未找到要替换的片段。")
            val replaced = text.replaceFirst(old, content)
            val bytes = LibraryStore.write(file, replaced)
            return Result(true, "已替换片段并保存 $name（现 $bytes 字节）。")
        }
        val total = LibraryStore.append(file, content)
        return Result(true, "已追加到 $name（现 $total 字节）。")
    }

    private fun delete(root: File, name: String): Result {
        val file = LibraryStore.resolveLibraryPath(root, name)
            ?: return Result(false, "非法文件名或类型: $name")
        return if (LibraryStore.delete(file)) {
            Result(true, "已删除 $name。")
        } else {
            Result(false, "删除失败（文件不存在）: $name")
        }
    }
}
