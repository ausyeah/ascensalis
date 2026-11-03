package com.mistakebook.ui.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 拍照页竖向布局的契约守护。
 *
 * Compose 的测量与绘制在 JVM 单测里跑不起来（没有 Robolectric，也没有真机），
 * 所以用源码结构钉死这次修复的**根因性质**：顶部操作栏、取景器卡片、底部
 * 操作区必须是同一个 Column 的三个兄弟节点，卡片高度只能来自 weight(1f)。
 *
 * 为什么值得专门钉：v0.0.90 之前卡片是 `Box.align(TopCenter).fillMaxSize()
 * .padding(top = 68.dp)`——68dp 是照着「状态栏 14dp + 顶栏 54dp」手算的，
 * 而状态栏高度由系统说了算（带挖孔的机器实测 42dp）。顶栏底缘落到 96dp 时，
 * 不透明的预览把三颗磨砂圆钮盖掉 28dp，只剩顶部一小截。
 *
 * 这种 bug 读代码看不出问题：常量在主流机型上刚好对，只在特定机型现形。
 * 所以用结构约束关掉它——只要三者退回「各自 align + 手写 top 预留」，
 * 或者底部预留常量复活，这里就红。
 */
class CaptureLayoutContractTest {

    /** 竖向流的开头那一行；抽成常量是为了两处断言锚同一处，不会各写各的。 */
    private val columnOpen = "Column(modifier = Modifier.fillMaxSize()) {"

    /** 取出整个竖向流 Column 的源码（含首尾花括号）。 */
    private fun verticalFlow(src: String): String {
        val idx = src.indexOf(columnOpen)
        assertTrue("拍照页必须有承上启下的竖向 Column（$columnOpen）", idx >= 0)
        return src.substring(idx, blockEnd(src, idx + columnOpen.length - 1))
    }

    @Test
    fun `顶栏取景器底栏必须是同一个 Column 的兄弟节点`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        val column = verticalFlow(src)

        // 三块内容都在这个 Column 里 → Column 的测量规则保证它们首尾相接、
        // 永不错位，与状态栏多高、底部导航多高都无关。
        assertTrue(
            "顶部操作栏必须在这个 Column 里（否则又会变成 align 兄弟互相盖）",
            column.contains("imageVector = Icons.Default.Close")
        )
        assertTrue(
            "取景器卡片必须在这个 Column 里",
            column.contains("ViewfinderCorners(modifier = Modifier.fillMaxSize())")
        )
        assertTrue(
            "底部操作区必须在这个 Column 里（否则卡片要靠预留常量猜它多高）",
            column.contains("navigationBarsPadding()") && column.contains("ShutterButton(")
        )
        assertTrue(
            "没权限的占位也必须占同一个格子，否则底栏会贴着取景器",
            column.contains("CameraPermissionRequest(")
        )
    }

    @Test
    fun `取景器卡片高度只能来自 weight 不能手写 top 预留`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        val column = verticalFlow(src)

        assertTrue(
            "卡片必须用 weight(1f) 拿「顶栏与底栏剩下的高度」",
            column.contains(".weight(1f)")
        )
        assertFalse(
            "竖向流里不允许再出现手写的 top 预留（那就是这次 bug 的种子）",
            column.contains("top =")
        )
        assertFalse(
            "卡片不能再 align 回 Box 的坐标里（align 是重叠定位，不是流式布局）",
            column.contains(".align(Alignment.")
        )
    }

    @Test
    fun `底部预留常量已删除不再需要猜底部多高`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        assertFalse(
            "BottomAreaReserve 必须删掉：留着就会有人重新拿它去算卡片高度",
            src.contains("BottomAreaReserve")
        )
    }

    @Test
    fun `顶部提示文案必须走字符串资源`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        assertTrue("QUESTION 用途提示必须用资源", src.contains("R.string.capture_hint_question"))
        assertTrue("CHAT 用途提示必须用资源", src.contains("R.string.capture_hint_chat"))
        assertFalse(
            "提示文案不允许再硬编码在 Kotlin 里",
            src.contains("\"对准题目，光线充足识别更准\"") ||
                src.contains("\"拍摄题目或解题过程，支持连拍\"")
        )

        val strings = readSource("strings.xml", "src/main/res/values/strings.xml")
        assertTrue(
            "strings.xml 必须有 QUESTION 提示",
            strings.contains("<string name=\"capture_hint_question\">对准题目，光线充足识别更准</string>")
        )
        assertTrue(
            "strings.xml 必须有 CHAT 提示",
            strings.contains("<string name=\"capture_hint_chat\">拍摄题目或解题过程，支持连拍</string>")
        )
    }

    /**
     * 从 openIdx（必须是 '{' 的下标）出发数到配对的那个 '}'，返回它的下标。
     *
     * 跳过字符串字面量与注释：这个文件里既有 `"$doneLabel ($batchSize)"` 这种
     * 带美元插值的，也有大段中文注释，不跳过就会把注释里的括号当代码数。
     */
    private fun blockEnd(src: String, openIdx: Int): Int {
        require(src[openIdx] == '{') { "blockEnd 只能从 '{' 开始" }
        var depth = 0
        var i = openIdx
        while (i < src.length) {
            val c = src[i]
            if (c == '"') {
                i++
                while (i < src.length) {
                    if (src[i] == '\\') i++ else if (src[i] == '"') break
                    i++
                }
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i++
            } else if (c == '{') {
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        error("括号不配对：从 $openIdx 起找不到闭合的 '}'")
    }

    private fun readSource(name: String, vararg paths: String): String {
        // 测试进程的工作目录是 app/ 模块目录（Gradle test task 的 cwd），
        // 但直接 gradlew 从项目根跑时又是根目录——三个候选都试。
        val candidates = paths.toList() +
            paths.map { "app/$it" } +
            paths.map { "../$it" }
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 $name，已尝试：${candidates.map { it }}")
        return file.readText()
    }
}
