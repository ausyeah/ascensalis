package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mistakebook.R

/**
 * 历史对话页的「能力入口」与「置顶」（v0.0.96）。
 *
 * 用户要求：进历史页，左上角是应用签名，下面是几个功能入口（拍题、翻译、
 * 批改、绘图、讲解、生成图片）**及相关情况**，再下面才是历史对话，
 * 长按可置顶 / 重命名 / 删除。
 *
 * 「相关情况」是最容易被省掉的部分：把六个入口做成一排漂亮按钮并不难，
 * 难的是让用户知道**为什么点了没反应**——没配模型就去点「英语翻译」，
 * 看到的是一条发送失败。所以这里钉住的是可用性的判定规则。
 */
class ChatToolsTest {

    @Test
    fun `六个入口一个不多一个不少`() {
        // v0.0.121（issue #36）：批改从输入栏开关升级成对话历史独立入口
        // v0.0.144（issue #54）：加「函数画图」——内置固定画布的计算器，
        // 与「绘图生图」并列在同一个抽屉菜单里。
        assertEquals(6, ChatTool.entries.size)
        listOf(
            R.string.chat_tool_photo,
            R.string.chat_tool_translate,
            R.string.chat_tool_essay,
            R.string.chat_tool_grade,
            R.string.chat_tool_diagram,
            R.string.chat_tool_plot
        ).forEach { res ->
            assertTrue(
                "入口文案必须在清单里（res=$res）",
                ChatTool.entries.any { it.labelRes == res }
            )
        }
        assertFalse(
            "错因诊断已按用户要求删掉，不许复活",
            ChatTool.entries.any { it.name == "DIAGNOSE" }
        )
    }

    @Test
    fun `每个预填入口都有隐藏指令且不为空`() {
        // 手动入口（函数画图）例外：它不预填提示词，是打开自己的画布页面，
        // 所以没有指令是**对的**，不是「功能没接上」。
        ChatTool.entries.filter { !it.manual }.forEach { tool ->
            assertTrue(
                "${tool.name} 的隐藏指令不能为空——空指令等于功能没接上",
                tool.instruction.isNotBlank()
            )
        }
        assertTrue(
            "函数画图必须是手动入口（本机算图，不预填提示词）",
            ChatTool.PLOT.manual
        )
    }

    @Test
    fun `指令里不许出现输入框回显的措辞`() {
        // 指令以 SYSTEM 消息注入，用户永远看不见；若有人把它当界面文案
        // 写上「请把下面的内容…」以外的 UI 措辞，也轮不到这里管——
        // 这里钉的是更实际的回归：指令被误改成以用户口吻开头，
        // 发出去就成了用户「说过」的话。
        // 手动入口（函数画图）没有指令——它不是预填提示词，是打开画布页面
        ChatTool.entries.filter { !it.manual }.forEach { tool ->
            assertFalse(
                "${tool.name} 的指令必须以模式声明开头（【…模式】），不能像用户说的话",
                !tool.instruction.trimStart().startsWith("【")
            )
        }
    }

    @Test
    fun `要拍照的入口有四个`() {
        // 拍题讲解 / 英语翻译 / 作文批改 / 题目批改都从一张照片开始
        // （v0.0.121 题目批加入，「先拍题目，再拍答案」与作文批改同款）；
        // 只有绘图生图是纯文字/纯生成，不该拉起相机
        assertEquals(
            setOf(ChatTool.PHOTO, ChatTool.TRANSLATE, ChatTool.ESSAY, ChatTool.GRADE),
            ChatTool.entries.filter { it.needsCamera }.toSet()
        )
    }

    /**
     * v0.0.121（issue #36）：批改开关从输入栏拆掉——题目会话与普通对话
     * 用同一个输入栏；批改入口在对话历史里，拍照回来**那一轮**按批改发。
     */
    @Test
    fun `批改入口会拍照且走识别，输入栏不再有批改开关`() {
        assertTrue(
            "题目批改必须拉起相机（先拍题目，再拍答案）",
            ChatTool.GRADE.needsCamera
        )
        assertTrue(
            "题目批改的发送路径要对照片跑 OCR，识别没配必须挡在入口",
            ChatTool.GRADE.needsOcr
        )
        val bar = readSource("src/main/java/com/mistakebook/ui/chat/ChatInputBar.kt")
        assertFalse(
            "输入栏不许再有批改开关（题目会话与普通对话保持一致）",
            bar.contains("gradingVisible") || bar.contains("chat_grading_toggle")
        )
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue(
            "题目批改入口的自动发送那一轮必须走批改路径",
            screen.contains("viewModel.send(forceGrading = mine.tool == ChatTool.GRADE)")
        )
        val strings = readStrings()
        assertTrue("strings.xml 少了 chat_tool_grade", strings.contains("name=\"chat_tool_grade\""))
        assertTrue("strings.xml 少了 capture_hint_grade", strings.contains("name=\"capture_hint_grade\""))
        assertFalse(
            "输入栏开关的两条文案已随开关删除，不许残留",
            strings.contains("chat_grading_toggle") || strings.contains("chat_input_hint_grading")
        )
    }

    @Test
    fun `没配模型时预填入口都提示需配模型`() {
        // 函数画图是本机算图，不需要模型：没配 Key 也照样能画（这正是
        // 手动入口存在的意义——模型限流或没配 Key 时图照样出得来）。
        ChatTool.entries.filter { !it.manual }.forEach { tool ->
            assertEquals(ChatToolBlock.NEEDS_LLM, tool.blockReason(llmConfigured = false, ocrConfigured = true))
        }
        assertEquals(
            ChatToolBlock.READY,
            ChatTool.PLOT.blockReason(llmConfigured = false, ocrConfigured = false)
        )
    }

    @Test
    fun `配了模型没配识别时只有拍题受影响`() {
        // 翻译 / 批改 / 绘图生图都不依赖 OCR，识别没配不该连坐；
        // 拍题讲解要读照片，识别没配就是真的用不了
        ChatTool.entries.filter { !it.needsOcr }.forEach { tool ->
            assertEquals(ChatToolBlock.READY, tool.blockReason(llmConfigured = true, ocrConfigured = false))
        }
        ChatTool.entries.filter { it.needsOcr }.forEach { tool ->
            assertEquals(ChatToolBlock.NEEDS_OCR, tool.blockReason(llmConfigured = true, ocrConfigured = false))
        }
    }

    @Test
    fun `都配好了全部可用`() {
        ChatTool.entries.forEach { tool ->
            assertEquals(ChatToolBlock.READY, tool.blockReason(llmConfigured = true, ocrConfigured = true))
        }
    }

    @Test
    fun `识别缺了不至于把模型缺了盖掉`() {
        // 两个都缺时，先说模型——那是所有入口共同的前置，
        // 用户配好模型之后提示会自动变成「需配识别」，一步步走不会迷路
        assertEquals(ChatToolBlock.NEEDS_LLM, ChatTool.PHOTO.blockReason(llmConfigured = false, ocrConfigured = false))
    }

    @Test
    fun `置顶与提示词文案都在资源里`() {
        val strings = readStrings()
        listOf(
            "app_signature",
            "chat_history_title",
            "swipe_pin_action",
            "swipe_unpin_action",
            "chat_pinned_badge",
            "chat_temporary_new"
        ).forEach { name ->
            assertTrue("strings.xml 少了 $name", strings.contains("name=\"$name\""))
        }
        // 签名就是应用名，别处出现中英混排会认不出来
        assertTrue(strings.contains("<string name=\"app_signature\">Ascensalis</string>"))
    }

    @Test
    fun `抽屉头部是签名而不是对话记录`() {
        val sheet = readSource("src/main/java/com/mistakebook/ui/chat/ChatHistorySheet.kt")
        assertFalse(
            "抽屉不许再挂「对话记录」标题——签名就是入口",
            sheet.contains("R.string.chat_list_title")
        )
        assertTrue("抽屉头部必须是应用签名", sheet.contains("stringResource(R.string.app_signature)"))
    }

    @Test
    fun `功能入口是一行行而不是九宫格`() {
        val tools = readSource("src/main/java/com/mistakebook/ui/chat/ChatTools.kt")
        assertTrue("抽屉里必须是行式入口", tools.contains("internal fun ChatToolRows("))
        assertFalse("九宫格版本已废弃，不许残留", tools.contains("ChatToolGrid"))
    }

    @Test
    fun `历史行左滑露出三个动作`() {
        val sheet = readSource("src/main/java/com/mistakebook/ui/chat/ChatHistorySheet.kt")
        // v0.0.98 用户拍板：去掉长按菜单，置顶进左滑
        assertFalse("长按菜单必须删掉", sheet.contains("onLongClick"))
        assertTrue("左滑露出宽度必须是三块", sheet.contains("SwipeActionRevealWidth = SwipeActionBlockWidth * 3"))
        val blocks = sheet.substring(sheet.indexOf("SwipeActionBlock("), sheet.indexOf("// 上层：卡片内容"))
        assertTrue("置顶块在", blocks.contains("R.string.swipe_unpin_action") && blocks.contains("R.string.swipe_pin_action"))
        assertTrue("重命名块在", blocks.contains("R.string.chat_rename"))
        // v0.0.104：主列表不再直接删除，最后一块改成归档（删除只在「已归档」页里）
        assertTrue("归档块在", blocks.contains("R.string.archive_action"))
        // v0.0.132（用户设计图）：叠画废弃，三块是**独立完整图形并排拼贴**，
        // 声明顺序恢复「左→右」：置顶（左）、重命名（中）、归档（右）。
        assertTrue(
            "拼贴顺序必须从左到右：置顶、重命名、归档",
            blocks.indexOf("R.string.swipe_pin_action") <
                blocks.indexOf("R.string.chat_rename") &&
                blocks.indexOf("R.string.chat_rename") <
                blocks.indexOf("R.string.archive_action")
        )
    }

    @Test
    fun `历史以抽屉形式挂在对话页上`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue(
            "对话页必须内嵌 ModalNavigationDrawer（右滑边缘也能开）",
            screen.contains("ModalNavigationDrawer(") && screen.contains("ModalDrawerSheet {")
        )
        assertTrue(
            "左上角必须能开抽屉",
            screen.contains("scope.launch { drawerState.open() }")
        )
        assertTrue(
            "抽屉开着时返回键必须先关抽屉，而不是退出会话",
            screen.contains("BackHandler(enabled = drawerState.isOpen)")
        )
        assertFalse(
            "独立的历史路由已拆掉——再导航过去就是一条死链",
            readSource("src/main/java/com/mistakebook/MistakeBookNavHost.kt").contains("Routes.CHAT_LIST")
        )
    }

    @Test
    fun `抽屉底部有新对话胶囊`() {
        val sheet = readSource("src/main/java/com/mistakebook/ui/chat/ChatHistorySheet.kt")
        assertTrue(
            "新对话必须是抽屉里最顺手的动作（底部胶囊），不是原来的悬浮按钮",
            sheet.contains("stringResource(R.string.chat_list_new)") && !sheet.contains("FloatingActionButton")
        )
    }

    private fun readStrings(): String = readSource("src/main/res/values/strings.xml")

    private fun readSource(path: String): String {
        val candidates = listOf(
            java.io.File(path),
            java.io.File("../$path"),
            java.io.File(path.removePrefix("app/")),
            java.io.File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}