package com.mistakebook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15 Otsu 阈值守卫的回归测试。
 *
 * ## 背景
 * 用户报告小概率「增强后黑白颠倒」。steepen 映射对固定阈值单调，单次处理
 * 数学上不可能反转；怀疑根因是 otsuThreshold 在极端直方图下阈值被拉偏。
 * 按防御性修复处理：分位钳制 + 5% 占比守卫回退，不追求复现。
 *
 * 本文件的场景就是任务书指定的那个：**95% 像素 luma 在 250–255、
 * 5% 在 0–30**（一小块强反光/深阴影/黑边，占比小但极端）。
 *
 * 日志回调注入空实现：Paparazzi 带来的 layoutlib jar 里 android.util.Log
 * 是真身（native），JVM 单测里打真 Log 会 UnsatisfiedLinkError。
 */
class ImageNormalizerOtsuGuardTest {

    /**
     * 构造像素数组：[brightRatio] 的像素 luma 在 250–255，其余在 0–30。
     * alpha 置 255（otsuThreshold 跳过 alpha < 16 的像素），RGB 同值 = 近灰。
     */
    private fun extremePixels(total: Int, brightRatio: Double): IntArray =
        IntArray(total) { i ->
            val luma = if (i < (total * brightRatio).toInt()) 250 + (i % 6) else (i % 31)
            (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
        }

    /** 跑守卫并把回退日志收下来（生产默认走 Log.w，这里注入捕获器）。 */
    private fun thresholdWithLog(pixels: IntArray): Pair<Int, String?> {
        var logged: String? = null
        val t = ImageNormalizer.otsuThreshold(pixels) { logged = it }
        return t to logged
    }

    /** 任务书指定场景：95% 亮（250–255）/ 5% 暗（0–30）。 */
    @Test
    fun `95%亮5%暗时阈值回退到中位中点`() {
        val (t, log) = thresholdWithLog(extremePixels(1000, 0.95))
        // 该场景原始 Otsu = 30（分界贴在暗簇上缘，暗侧只剩 4.8% < 5%），
        // 占比守卫必须回退到 (加权中位数 252 + 128) / 2 = 190。
        // 若守卫失效，阈值会是 30——纸面与暗字的边界整体塌到暗簇里。
        assertEquals("暗侧 4.8% 必须触发占比守卫回退", 190, t)
        assertTrue("回退必须留日志，实际=$log", log != null)
        assertTrue("日志应含阈值与两侧占比: $log", log!!.contains("Otsu 阈值异常已回退"))
        assertTrue("日志应含亮侧占比 95%: $log", log.contains("亮侧占比=95%"))
        assertTrue("日志应含暗侧占比 4%: $log", log.contains("暗侧占比=4%"))
        assertTrue("日志应含回退目标值 190: $log", log.contains("-> 190"))
    }

    @Test
    fun `99%亮1%暗时同样触发回退`() {
        val (t, log) = thresholdWithLog(extremePixels(1000, 0.99))
        // 暗侧 1% < 5% 必回退；此时 P2 已落进亮簇（250），分位钳制先把
        // 原始 Otsu(30) 钳到 250，守卫再回退到 190。
        assertEquals("暗侧 1% 必须触发占比守卫回退", 190, t)
        assertTrue("回退必须留日志: $log", log != null && log.contains("暗侧占比=1%"))
    }

    @Test
    fun `回退后增强输出与输入同向不会整体偏暗`() {
        // 「黑白颠倒」的感知 = 整页被推向全暗。用守卫后的阈值走 steepen
        // （enhanceForOcr 的近灰通道正是这条映射），输出均值必须与输入同向。
        val pixels = extremePixels(1000, 0.95)
        val t = ImageNormalizer.otsuThreshold(pixels) {}
        val inMean = pixels.sumOf { (it shr 16) and 0xFF } / pixels.size.toDouble()
        val outMean =
            pixels.sumOf { ImageNormalizer.steepen((it shr 16) and 0xFF, t, 2) } /
                pixels.size.toDouble()
        assertTrue(
            "输入均值=$inMean 输出均值=$outMean，输出不应整体偏暗到离谱",
            outMean > inMean * 0.7
        )
        // 亮簇（纸面）必须被推向接近纯白
        val brightMean = pixels
            .filter { ((it shr 16) and 0xFF) >= 250 }
            .map { ImageNormalizer.steepen((it shr 16) and 0xFF, t, 2) }
            .average()
        assertTrue("亮簇（纸面）均值应接近 255，实际=$brightMean", brightMean >= 240)
    }

    @Test
    fun `正常双簇分布不受守卫影响也不打日志`() {
        // 回归底线：正常照片（两簇均衡）行为与加守卫前一致——
        // 60% 像素在 60–69（字迹）、40% 在 200–209（纸面），Otsu = 69，
        // 暗侧 60% 远大于 5%，守卫不触发：阈值就是 Otsu 原值，且不打日志。
        val pixels = IntArray(1000) { i ->
            val luma = if (i < 600) 60 + (i % 10) else 200 + (i % 10)
            (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
        }
        val (t, log) = thresholdWithLog(pixels)
        assertEquals("正常双簇的阈值应仍是 Otsu 原值 69", 69, t)
        assertEquals("守卫未触发时不应打日志", null, log)
    }
}
