package com.mistakebook.ui.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 连拍成功反馈的**接线**契约（v0.0.95）。
 *
 * 几何数值在 [CaptureBatchVisualsTest] 里测，但数值再对，只要有一环没接上，
 * 用户就什么都看不到，而所有单测照样全绿。这种退化只能钉源码：
 *
 * 1. 三个视觉组件必须真的出现在拍照页；
 * 2. 拍成必须**同时**触发白边与飞行（只做一个用户仍会怀疑没拍上）；
 * 3. 飞行起落点必须来自真实测量，不许写死坐标（换机型必错）；
 * 4. 右下角槽位必须定尺寸，否则第二张起飞时落点会先跳；
 * 5. 正在飞的那张不许同时出现在堆叠里（否则一帧里能看到两个自己）；
 * 6. 文案全部走 strings.xml。
 */
class CaptureFxContractTest {

    private val screenPath = "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
    private val visualsPath = "src/main/java/com/mistakebook/ui/capture/CaptureBatchVisuals.kt"

    @Test
    fun `三个视觉组件都必须挂到拍照页`() {
        val screen = readSource("CaptureScreen.kt", screenPath)

        assertTrue(
            "右下角必须是缩略图堆叠（否则「拍成」没有任何视觉凭证）",
            screen.contains("CaptureThumbnailStack(")
        )
        assertTrue(
            "顶栏必须有张数胶囊（v0.0.134：从取景器右上角移到顶栏居中，扁平胶囊）",
            screen.contains("CaptureCountPill(")
        )
        assertFalse(
            "取景器内不许再叠张数徽章——右上角会遮挡取景内容",
            screen.contains("CaptureCountBadge(")
        )
        assertTrue(
            "必须有「整张飞进右下角」的覆盖层",
            screen.contains("CaptureFlightOverlay(")
        )
    }

    @Test
    fun `拍成必须同时触发白边与飞行`() {
        val screen = readSource("CaptureScreen.kt", screenPath)
        val captured = screen.substring(
            screen.indexOf("onCaptured = { path ->"),
            screen.indexOf("onError = {", screen.indexOf("onCaptured = { path ->"))
        )

        // 白边 = 「照片拿到了」；飞行 = 「它去哪了」。只做其中一个，
        // 用户都会怀疑这一张到底拍上没有。
        assertTrue(
            "拍成必须让白边高亮闪一下",
            captured.contains("captureFlash.snapTo(1f)")
        )
        assertTrue(
            "拍成必须把这张送进飞行队列",
            captured.contains("flightRequest = path")
        )
        assertTrue(
            "入批失败（批次满）时不许放动画——那会让用户以为拍成功了",
            captured.contains("if (added) {")
        )
    }

    @Test
    fun `飞行起落点必须来自真实测量而不是写死坐标`() {
        val screen = readSource("CaptureScreen.kt", screenPath)
        val visuals = readSource("CaptureBatchVisuals.kt", visualsPath)

        // 取景器与右下角槽位都按窗口坐标实测；写死 dp 换个机型就飞歪
        assertTrue(
            "取景器矩形必须实测",
            screen.contains("viewfinderRect = it.boundsInWindow()")
        )
        assertTrue(
            "右下角落点必须实测",
            screen.contains("stackRect = it.boundsInWindow()")
        )
        assertTrue(
            "根容器原点必须实测（三处层级不同，只有窗口坐标是共同坐标系）",
            screen.contains("rootOrigin = it.boundsInWindow().topLeft")
        )
        assertTrue(
            "覆盖层必须把窗口坐标换算回自己的局部坐标",
            visuals.contains("rect.left - rootOrigin.x")
        )
    }

    @Test
    fun `右下角槽位尺寸必须固定`() {
        val screen = readSource("CaptureScreen.kt", screenPath)

        // 空态和堆叠尺寸不同的话，第二张起飞时落点矩形会先变一次，
        // 动画表现为「先跳到别处再飞」。
        assertTrue(
            "槽位必须写死 84×56，空态与堆叠共用同一个落点",
            screen.contains("size(width = 84.dp, height = 56.dp)")
        )
    }

    @Test
    fun `正在飞的那张不许同时出现在堆叠里`() {
        val screen = readSource("CaptureScreen.kt", screenPath)

        assertTrue(
            "堆叠必须过滤掉飞行中的路径，否则会看到一张在原地、一张在飞",
            screen.contains("batchPaths.filter { it != flyingPath }")
        )
        assertTrue(
            "落地后必须清掉飞行态，这张才会出现在堆叠里",
            screen.contains("flyingPath = null")
        )
    }

    @Test
    fun `堆叠上限是三张且由常量控制`() {
        val visuals = readSource("CaptureBatchVisuals.kt", visualsPath)

        assertTrue("上限必须是 3", visuals.contains("internal const val STACK_VISIBLE_MAX = 3"))
        assertTrue(
            "裁到上限必须走 visibleStackCount，不许在调用处再写一个 coerceAtMost",
            visuals.contains("visibleStackCount(thumbs.size)")
        )
    }

    @Test
    fun `缩略图解码必须单独捕获 OOM`() {
        val visuals = readSource("CaptureBatchVisuals.kt", visualsPath)

        // runCatching 会把 OutOfMemoryError 一起吞掉，让内存问题伪装成
        // 「文件坏了」——这是本仓库已经踩过一次的坑。
        assertTrue(
            "解码必须显式捕获 OOM",
            visuals.contains("catch (oom: OutOfMemoryError)")
        )
        assertTrue(
            "解码失败只该少一张预览，不能崩",
            visuals.contains("internal fun decodeThumbnail(")
        )
    }

    @Test
    fun `张数文案走字符串资源`() {
        val strings = readSource("strings.xml", "src/main/res/values/strings.xml")
        val screen = readSource("CaptureScreen.kt", screenPath)
        val visuals = readSource("CaptureBatchVisuals.kt", visualsPath)

        assertTrue(
            "strings.xml 必须有堆叠的朗读文案",
            strings.contains("name=\"capture_stack_open\"")
        )
        assertTrue(
            "堆叠必须用捕获批次数量的既有文案",
            screen.contains("R.string.capture_stack_open") &&
                screen.contains("R.string.capture_batch_count")
        )
        // 新组件里不许出现中文硬编码（仓库规则：界面文案全部进 strings.xml）
        listOf("查看本批", "本批", "完成").forEach { text ->
            assertFalse(
                "「$text」不许硬编码在视觉组件里",
                visuals.contains("\"$text\"")
            )
        }
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
