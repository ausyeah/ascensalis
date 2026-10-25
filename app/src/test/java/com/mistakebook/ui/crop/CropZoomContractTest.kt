package com.mistakebook.ui.crop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T14 裁切页双指缩放/平移的契约守护。
 *
 * 五条容易被后续改动悄悄破坏的性质，用源码契约钉死（Compose 手势与
 * graphicsLayer 无法在 JVM 单测里实例化，只能守住源码结构）：
 * - **手势分流**：awaitEachGesture 手工分流，≥2 指进变换模式并作废半笔（不 commit），
 *   单指分支只在从未进过变换模式时结算；
 * - **同步变换**：Image 与 Canvas 必须在同一个 graphicsLayer 里；
 * - **坐标链**：screenToBase/baseToScreen 外层叠 zoom 逆/正变换，且互逆；
 * - **pan 钳制**：按图片实际显示尺寸算，不照抄预览弹窗的 1500f/2000f 硬编码；
 * - **复位**：双击与工具栏「重置」都必须把 scale/pan 归位。
 */
class CropZoomContractTest {

    @Test
    fun `手势分流用 awaitEachGesture 且两指进入变换模式`() {
        val src = readSource("CropOverlay.kt", "src/main/java/com/mistakebook/ui/crop/CropOverlay.kt")
        assertTrue("必须改用 awaitEachGesture 手工分流", src.contains("awaitEachGesture"))
        assertTrue("必须有变换模式标志", src.contains("transformMode"))
        assertTrue(
            "pointer 数 >= 2 必须触发变换模式",
            src.contains("pressed.size >= 2")
        )
    }

    @Test
    fun `进入变换模式时作废半笔且不 commit`() {
        val src = readSource("CropOverlay.kt", "src/main/java/com/mistakebook/ui/crop/CropOverlay.kt")
        // 两指落下时手指位置跳变，半笔一定是废线：清点 + 空回调，但不能 commit
        assertTrue(
            "涂鸦模式下两指落下必须清空当前笔",
            src.contains("onActiveStrokeChange(emptyList())")
        )
        assertTrue(
            "非涂鸦模式下两指落下必须取消手柄拖拽",
            src.contains("onHandleDragCancel()")
        )
        // commit 只允许出现在 !transformMode 的结算分支
        val commitIdx = src.indexOf("onStrokeCommit()")
        val guardIdx = src.indexOf("if (!transformMode)")
        assertTrue("onStrokeCommit 必须存在", commitIdx >= 0)
        assertTrue("必须存在 !transformMode 结算守卫", guardIdx >= 0)
        assertTrue(
            "onStrokeCommit 只能在从未进过变换模式的手势流里调用（守卫须在它之前）",
            guardIdx < commitIdx
        )
    }

    @Test
    fun `Image 与遮罩 Canvas 包在同一 graphicsLayer 内同步缩放`() {
        val src = readSource("CropOverlay.kt", "src/main/java/com/mistakebook/ui/crop/CropOverlay.kt")
        val layerIdx = src.indexOf(".graphicsLayer(")
        val imageIdx = src.indexOf("Image(")
        val canvasIdx = src.indexOf("Canvas(")
        assertTrue("必须存在 graphicsLayer", layerIdx >= 0)
        assertTrue("graphicsLayer 必须在 Image 之前（图片在变换层内）", layerIdx < imageIdx)
        assertTrue("Canvas 必须在 graphicsLayer 之后（遮罩与图片同层）", layerIdx < canvasIdx)
        assertTrue("缩放必须作用于 graphicsLayer", src.contains("scaleX = zoomScale"))
        assertTrue("平移必须作用于 graphicsLayer", src.contains("translationX = zoomPan.x"))
    }

    @Test
    fun `笔宽按 zoomScale 换算保持屏幕视觉宽度恒定`() {
        val src = readSource("CropOverlay.kt", "src/main/java/com/mistakebook/ui/crop/CropOverlay.kt")
        assertTrue(
            "绘制笔宽必须除以 zoomScale（graphicsLayer 会再放大回去）",
            src.contains("brushWidthPx / zoomScale")
        )
        assertTrue(
            "drawLine 必须使用换算后的笔宽",
            src.contains("strokeWidth = visualBrushWidthPx")
        )
    }

    @Test
    fun `screenToBase 与 baseToScreen 外层叠 zoom 且互逆`() {
        val src = readSource("CropScreen.kt", "src/main/java/com/mistakebook/ui/crop/CropScreen.kt")
        // 逆变换：屏幕 → 减 pan、除 scale → 视图坐标 → 原有旋转换算
        assertTrue(
            "screenToBase 必须先做 zoom 逆变换（减 pan、除 scale）",
            src.contains("(x - zoomPanClamped.x) / zoomScale") &&
                src.contains("(y - zoomPanClamped.y) / zoomScale")
        )
        // 正变换：视图 → 乘 scale、加 pan → 屏幕
        assertTrue(
            "baseToScreen 末尾必须做 zoom 正变换（乘 scale、加 pan）",
            src.contains("toScreenX(d.x) * zoomScale + zoomPanClamped.x") &&
                src.contains("toScreenY(d.y) * zoomScale + zoomPanClamped.y")
        )
        // 旋转换算本体不能被改动（zoom 是外层包裹，保持正交）
        assertTrue(
            "原有旋转换算必须原样保留在 zoom 内层",
            src.contains("MASK_ROTATION_PERIOD - rotationQuarter")
        )
    }

    @Test
    fun `zoomScale 钳制在 1f 到 5f`() {
        val src = readSource("CropScreen.kt", "src/main/java/com/mistakebook/ui/crop/CropScreen.kt")
        assertTrue(
            "缩放倍数必须钳制在 1f..5f",
            src.contains("coerceIn(1f, 5f)")
        )
    }

    @Test
    fun `pan 按图片实际显示尺寸钳制而非预览硬编码`() {
        val src = readSource("CropScreen.kt", "src/main/java/com/mistakebook/ui/crop/CropScreen.kt")
        // 主页面钳制基准必须是 imageSize（FIT 后实际显示尺寸）
        assertTrue(
            "pan 横向钳制必须按图片显示宽度算",
            src.contains("(zoomScale - 1f) * imageSize.width / 2f")
        )
        assertTrue(
            "pan 纵向钳制必须按图片显示高度算",
            src.contains("(zoomScale - 1f) * imageSize.height / 2f")
        )
        // 预览弹窗自己那对 1500f/2000f 是它自己的尺寸，主页面不得把公式抄过来
        //（注释里提到这两个数字是在说明「为什么不能用」，不算照抄）
        val mainPage = src.substring(0, src.indexOf("CropOverlay("))
        assertFalse(
            "主页面不得照抄预览弹窗的 * 1500f/2000f 硬编码公式",
            mainPage.contains("* 1500f") || mainPage.contains("* 2000f")
        )
    }

    @Test
    fun `手柄热区与拖动都按放大后的屏幕位置换算`() {
        val src = readSource("CropScreen.kt", "src/main/java/com/mistakebook/ui/crop/CropScreen.kt")
        assertTrue(
            "pickHandle 的屏幕映射必须叠 zoom 正变换（热区跟着图片走）",
            src.contains("toScreenX(f) * zoomScale + zoomPanClamped.x")
        )
        assertTrue(
            "手柄拖动位移必须除以 zoomScale（放大时移动更细腻）",
            src.contains("dragAmount.x / zoomScale")
        )
    }

    @Test
    fun `双击与工具栏重置都必须复位 zoom`() {
        val overlay = readSource("CropOverlay.kt", "src/main/java/com/mistakebook/ui/crop/CropOverlay.kt")
        assertTrue(
            "双击必须经 detectTapGestures 回调 onZoomReset",
            overlay.contains("detectTapGestures(onDoubleTap") && overlay.contains("onZoomReset()")
        )
        val screen = readSource("CropScreen.kt", "src/main/java/com/mistakebook/ui/crop/CropScreen.kt")
        val resetIdx = screen.indexOf("onReset = {")
        assertTrue("工具栏 onReset 必须存在", resetIdx >= 0)
        val resetBlock = screen.substring(resetIdx, screen.indexOf("},", resetIdx))
        assertTrue(
            "「重置」必须同时复位 zoomScale",
            resetBlock.contains("zoomScale = 1f")
        )
        assertTrue(
            "「重置」必须同时复位 zoomPan",
            resetBlock.contains("zoomPan = Offset.Zero")
        )
    }

    @Test
    fun `zoom 状态提升到调用方且旋转时不重置`() {
        val src = readSource("CropScreen.kt", "src/main/java/com/mistakebook/ui/crop/CropScreen.kt")
        assertTrue(
            "zoomScale 必须是调用方持有的状态（remember，无 imagePath/rotationQuarter key）",
            src.contains("var zoomScale by remember { mutableStateOf(1f) }")
        )
        assertTrue(
            "zoomPan 必须是调用方持有的状态",
            src.contains("var zoomPan by remember { mutableStateOf(Offset.Zero) }")
        )
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
