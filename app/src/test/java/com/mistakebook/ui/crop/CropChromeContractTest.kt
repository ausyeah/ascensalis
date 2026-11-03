package com.mistakebook.ui.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 裁剪页界面重做的契约守护（v0.0.95）。
 *
 * 用户的原话是「编辑页面有点土、UI 没有质感太粗糙」。这类反馈没有崩溃可查、
 * 没有单测能测，退化的方式又极其安静：某个组件被改回 M3 默认样式、
 * 某个圆角被写死成 `RoundedCornerShape(12.dp)`，看上去都能编译、都能跑。
 *
 * 所以这里钉的是**这次重做的四条性质**：
 * 1. 主顶栏必须换成自绘的 [CropTopBar]，并带上批次进度；
 * 2. 工具栏不再出现 M3 的 SegmentedButton（那就是「土」的来源）；
 * 3. 所有圆角必须取自 MaterialTheme.shapes——否则设置页的圆角旋钮在裁剪页失效；
 * 4. 界面文案仍然全部走 strings.xml。
 */
class CropChromeContractTest {

    @Test
    fun `顶栏必须显示批次进度`() {
        val src = readSource(
            "CropScreen.kt",
            "src/main/java/com/mistakebook/ui/crop/CropScreen.kt"
        )
        assertTrue(
            "主顶栏必须走自绘的 CropTopBar",
            src.contains("CropTopBar(")
        )
        assertTrue(
            "进度必须由批次快照推导（队首即当前，见 batchPosition 注释）",
            src.contains("val batchCropped = container.captureBatch.cropped.size") &&
                src.contains("val batchTotal = container.captureBatch.size")
        )
        assertTrue(
            "顶栏必须把进度传进去",
            src.contains("progress = batchProgressLabel(batchCropped, batchTotal)")
        )
    }

    @Test
    fun `裁剪页主顶栏不再用 M3 TopAppBar`() {
        val src = readSource(
            "CropScreen.kt",
            "src/main/java/com/mistakebook/ui/crop/CropScreen.kt"
        )
        // 预览弹层里还留着一个（它需要标准关闭按钮），所以是「只剩一个」而不是「一个都没有」
        assertEquals(
            "主 Scaffold 不许再用 M3 TopAppBar，只允许预览弹层保留一处",
            1,
            Regex("TopAppBar\\(").findAll(src).count()
        )
    }

    @Test
    fun `工具栏不再使用 M3 分段按钮`() {
        val toolbar = readSource(
            "CropToolbar.kt",
            "src/main/java/com/mistakebook/ui/crop/CropToolbar.kt"
        )
        assertFalse(
            "SegmentedButton 自带 M3 默认形状与色板，和本应用的描边语言是两套东西",
            toolbar.contains("SingleChoiceSegmentedButtonRow") ||
                toolbar.contains("SegmentedButton(")
        )
    }

    @Test
    fun `工具栏圆角必须跟主题走`() {
        val toolbar = readSource(
            "CropToolbar.kt",
            "src/main/java/com/mistakebook/ui/crop/CropToolbar.kt"
        )
        // 写死 dp 圆角 = 设置页的圆角旋钮在裁剪页失效，是这次重做最容易退化的地方
        assertTrue(toolbar.contains("MaterialTheme.shapes.extraLarge"))
        assertTrue(toolbar.contains("MaterialTheme.shapes.large"))
        assertTrue(toolbar.contains("MaterialTheme.shapes.medium"))
        assertTrue(toolbar.contains("MaterialTheme.shapes.small"))
    }

    @Test
    fun `增强四档必须常驻且不再有展开开关`() {
        val toolbar = readSource(
            "CropToolbar.kt",
            "src/main/java/com/mistakebook/ui/crop/CropToolbar.kt"
        )
        // v0.0.57 用户明确要求「不用展开」；v0.0.95 顺手删掉了这条从未被读取的参数
        assertFalse("enhancePanelOpen 是死参数，不许复活", toolbar.contains("enhancePanelOpen"))
        assertTrue(toolbar.contains("R.string.capture_ocr_off"))
        assertTrue(toolbar.contains("R.string.capture_ocr_standard"))
        assertTrue(toolbar.contains("R.string.capture_ocr_strong"))
    }

    @Test
    fun `界面文案全部走 stringsxml`() {
        val toolbar = readSource(
            "CropToolbar.kt",
            "src/main/java/com/mistakebook/ui/crop/CropToolbar.kt"
        )
        val screen = readSource(
            "CropScreen.kt",
            "src/main/java/com/mistakebook/ui/crop/CropScreen.kt"
        )
        listOf("框选", "涂鸦", "重置", "旋转 90°", "确认", "撤销", "清空", "预览")
            .forEach { text ->
                assertFalse(
                    "「$text」不许硬编码在 Kotlin 里（仓库规则：界面文案全部进 strings.xml）",
                    toolbar.contains("\"$text\"") || screen.contains("\"$text\"")
                )
            }

        val strings = readSource("strings.xml", "src/main/res/values/strings.xml")
        assertTrue(
            "进度文案必须在资源里",
            strings.contains("<string name=\"crop_progress\">第 %1\$d / %2\$d 张</string>")
        )
    }

    private fun readSource(name: String, vararg paths: String): String {
        val candidates = paths.toList() +
            paths.map { "app/$it" } +
            paths.map { "../$it" }
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 $name，已尝试：${candidates.map { it }}")
        return file.readText()
    }
}