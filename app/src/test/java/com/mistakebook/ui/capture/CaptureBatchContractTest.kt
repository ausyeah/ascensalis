package com.mistakebook.ui.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F6 连拍模式的契约守护。
 *
 * Compose 交互在 JVM 单测里跑不起来，用源码结构钉死四条关键性质：
 * - 快门两级：QUESTION 拍完入批次不导航、capturing 必须复位（否则第二张按不动）；
 * - CHAT 用途完全走原单张链路（回归底线）；
 * - 满员 12 张拦截 + 丢弃确认（返回键/关闭按钮都拦）；
 * - 裁切接力：NavHost 出队推进、onBack 计数减 1、提交走 drain 不删文件。
 */
class CaptureBatchContractTest {

    @Test
    fun `快门拍完入批次且停留在相机页`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        assertTrue(
            "QUESTION 用途拍完必须加入批次",
            src.contains("container.captureBatch.add(path, fromGallery = false)")
        )
        assertTrue(
            "QUESTION 用途不得直接 onCropped 导航（留在相机页攒批）",
            src.contains("if (isQuestion) {") &&
                src.contains("container.captureBatch.add(path, fromGallery = false)") &&
                src.contains("} else {") &&
                src.contains("onCropped(path)")
        )
    }

    @Test
    fun `拍完必须复位 capturing 否则第二张按不动快门`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        val onCapturedIdx = src.indexOf("onCaptured = { path ->")
        assertTrue("onCaptured 回调必须存在", onCapturedIdx >= 0)
        val block = src.substring(onCapturedIdx, src.indexOf("onError = {", onCapturedIdx))
        assertTrue(
            "连拍模式拍完必须把 capturing 复位（旧流程直接导航走，页面销毁掩盖了这点）",
            block.contains("capturing = false")
        )
    }

    @Test
    fun `批次满员 12 张时快门 toast 拦截`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        assertTrue(
            "快门必须检查批次满员",
            src.contains("container.captureBatch.isFull()")
        )
        assertTrue(
            "满员必须 toast 提示",
            src.contains("R.string.capture_batch_full")
        )
        val batchSrc = readSource(
            "CaptureBatch.kt",
            "src/main/java/com/mistakebook/di/CaptureBatch.kt"
        )
        assertTrue("上限必须是 12", batchSrc.contains("const val MAX = 12"))
    }

    @Test
    fun `CHAT 用途保持单张原链路`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        // CHAT 用途：快门直接 onCropped（不经过 add），相册单选 launcher 仍在
        val singleIdx = src.indexOf("gallerySingleLauncher")
        assertTrue("CHAT 用途必须有单选相册入口", singleIdx >= 0)
        assertTrue(
            "单选 launcher 保持 cropSourceIsGallery 标记",
            src.contains("container.cropSourceIsGallery = true")
        )
        // 批次计数只在 QUESTION 用途显示
        assertTrue(
            "batchActive 必须限定 QUESTION 用途",
            src.contains("CameraPurpose.QUESTION &&") &&
                src.contains("batchSize > 0")
        )
    }

    @Test
    fun `返回键与关闭按钮都拦截并弹丢弃确认`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        assertTrue(
            "系统返回键必须经 BackHandler 拦截",
            src.contains("BackHandler(enabled = batchActive)")
        )
        val closeIdx = src.indexOf("imageVector = Icons.Default.Close")
        assertTrue("关闭按钮必须存在", closeIdx >= 0)
        // 锚点随组件重构更新：v0.0.54 拍照页重设计后，底部圆形按钮统一走
        // FrostyCircleButton（磨砂圆钮），不再是裸 IconButton。
        // 断言的意图没变——**关闭按钮必须能拦住批次**。
        // 只锚到组件名（不带参数）：参数换行/加参数都不影响定位。
        val closeBlock = src.substring(src.lastIndexOf("FrostyCircleButton(", closeIdx))
        assertTrue(
            "有关闭批次时点关闭必须弹确认而不是直接返回",
            closeBlock.contains("if (batchActive)") && closeBlock.contains("showDiscardDialog = true")
        )
        assertTrue(
            "确认丢弃必须调用 clear()",
            src.contains("container.captureBatch.clear()")
        )
    }

    @Test
    fun `相册多选进入同一批次且立即开裁`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        assertTrue(
            "QUESTION 用途相册必须多选（maxItems 达批次上限）",
            src.contains("PickMultipleVisualMedia(CaptureBatch.MAX)")
        )
        assertTrue(
            "多选结果必须逐张加入批次",
            src.contains("container.captureBatch.add(file.absolutePath, fromGallery = true)")
        )
        assertTrue(
            "选完直接开裁第一张（系统选择器里已选完，不用再按完成）",
            src.contains("onCropped(container.captureBatch.paths.first())")
        )
    }

    @Test
    fun `批次悬浮计数与完成按钮挂接 onStartBatchCrop`() {
        val src = readSource(
            "CaptureScreen.kt",
            "src/main/java/com/mistakebook/ui/capture/CaptureScreen.kt"
        )
        assertTrue(
            "必须有「本批 N 张」计数文案",
            src.contains("R.string.capture_batch_count")
        )
        assertTrue(
            "完成按钮必须回调 onStartBatchCrop",
            src.contains("onClick = onStartBatchCrop")
        )
    }

    @Test
    fun `裁切提交分流批次与单张两条路`() {
        val src = readSource(
            "CropScreen.kt",
            "src/main/java/com/mistakebook/ui/crop/CropScreen.kt"
        )
        val batchIdx = src.indexOf("if (batchMode) {")
        assertTrue("提交分支必须识别 batchMode", batchIdx >= 0)
        val block = src.substring(batchIdx, src.indexOf("val title = if (container.cropSourceIsGallery)", batchIdx))
        assertTrue(
            "批次模式必须把成果记入批次",
            block.contains("container.captureBatch.commitCropped(saved.absolutePath)")
        )
        assertTrue(
            "批次模式有下一张必须走接力回调",
            block.contains("onBatchNext(next)")
        )
        assertTrue(
            "批次模式全部裁完必须走 onBatchCompleted",
            block.contains("onBatchCompleted()")
        )
        assertTrue(
            "批次模式必须清掉本张的裁切会话（防重裁恢复旧笔迹）",
            block.contains("sessionStore.clear(imagePath)")
        )
        // 单张流程必须原样保留（回归）
        assertTrue(
            "单张流程必须原样提交",
            src.contains("container.recognitionSubmitter.submitImages(listOf(saved), title)")
        )
    }

    @Test
    fun `原图丢失时批次模式提供跳过出口`() {
        val src = readSource(
            "CropScreen.kt",
            "src/main/java/com/mistakebook/ui/crop/CropScreen.kt"
        )
        val decodeIdx = src.indexOf("text = decodeError")
        assertTrue(decodeIdx >= 0)
        val block = src.substring(decodeIdx, src.indexOf("TextButton(onClick = onBack)", decodeIdx))
        assertTrue(
            "批次模式解码失败必须显示「跳过这张」",
            block.contains("if (batchMode)") && block.contains("onBatchSkip()")
        )
        assertTrue(
            "跳过时必须 toast 提示",
            block.contains("R.string.capture_batch_skip_missing")
        )
    }

    @Test
    fun `NavHost 接力出队推进且返回计数减一`() {
        val src = readSource(
            "MistakeBookNavHost.kt",
            "src/main/java/com/mistakebook/MistakeBookNavHost.kt"
        )
        assertTrue(
            "接力必须导航下一张 crop",
            src.contains("onBatchNext = { next ->") &&
                src.contains("Routes.crop(next)")
        )
        assertTrue(
            "跳过必须出队后推进",
            src.contains("container.captureBatch.dropCurrent()")
        )
        val backIdx = src.indexOf("onBack = {", src.indexOf("batchMode = batchMode"))
        assertTrue("crop 路由 onBack 必须存在", backIdx >= 0)
        val backBlock = src.substring(backIdx, src.indexOf("}\n            )", backIdx))
        assertTrue(
            "批次模式返回必须出队当前张（计数减 1）并回相机页",
            backBlock.contains("container.captureBatch.dropCurrent()") &&
                backBlock.contains("navigate(Routes.CAPTURE)")
        )
    }

    @Test
    fun `批次提交走 drain 不删文件且只跳第一张进度`() {
        val src = readSource(
            "MistakeBookNavHost.kt",
            "src/main/java/com/mistakebook/MistakeBookNavHost.kt"
        )
        assertTrue(
            "提交必须经 drainCropped 取走（绝不删文件）",
            src.contains("batch.drainCropped()")
        )
        assertFalse(
            "提交路径不得调用 clear()（那会删掉待识别的成果文件）",
            src.substring(
                src.indexOf("fun submitCaptureBatch()"),
                src.indexOf("fun submitCaptureBatch()") + 800
            ).contains(".clear()")
        )
        assertTrue(
            "只跳第一张 task 的进度页",
            src.contains("Routes.progress(ids.first())")
        )
    }

    @Test
    fun `批次状态挂 AppContainer 供旋横屏存活`() {
        val src = readSource(
            "AppContainer.kt",
            "src/main/java/com/mistakebook/di/AppContainer.kt"
        )
        assertTrue(
            "AppContainer 必须持有 captureBatch 实例",
            src.contains("val captureBatch = CaptureBatch()")
        )
        val batchSrc = readSource(
            "CaptureBatch.kt",
            "src/main/java/com/mistakebook/di/CaptureBatch.kt"
        )
        assertTrue(
            "paths 必须是快照状态（旋转重组不丢）",
            batchSrc.contains("var paths: List<String> by mutableStateOf(emptyList())")
        )
        assertTrue(
            "cropped 必须是快照状态",
            batchSrc.contains("var cropped: List<String> by mutableStateOf(emptyList())")
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
