package com.mistakebook.di

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 连拍批次（F6）：一次拍摄会话里攒下的多张待裁切原图。
 *
 * ## 为什么放 AppContainer
 * 批次要跨「相机页 → 裁切页 → 裁切页 → …」多个导航目标存活，
 * 竖横屏旋转也不能丢。Composable 的 remember 救不了进程级导航，
 * ViewModel 又各自绑在路由上——所以沿用 [AppContainer.recropping]
 * 那套容器标志惯例，由 AppContainer 持有一个实例。
 *
 * ## 状态语义
 * - [paths]：还没裁切的原图队列，队首就是裁切页正在处理的那张；
 * - [cropped]：已裁好的成果（裁切页确认一张出队一张、成果进这里）；
 * - 「第几张」不单独存索引——队首即当前，接力导航天然按队列推进。
 *
 * 所有读写都发生在主线程（相机回调经 onUi 切回、导航与点击也在主线程），
 * 不需要额外同步。进程死亡不持久化，与 [AppContainer.recropping] 一致。
 */
class CaptureBatch {

    /** 未裁切的原图路径队列，队首为当前正在裁切的张。 */
    var paths: List<String> by mutableStateOf(emptyList())
        private set

    /** 已裁好的成果文件路径，按裁切顺序排列。 */
    var cropped: List<String> by mutableStateOf(emptyList())
        private set

    /** 本批来源是否为相册（决定进度页组标题「相册导入 / 拍照识别」）。 */
    var sourceIsGallery: Boolean by mutableStateOf(false)
        private set

    /** 批次总张数（未裁 + 已裁）。相机页「本批 N 张」显示的就是它。 */
    val size: Int get() = paths.size + cropped.size

    /** 还有没有没裁的。 */
    val hasPending: Boolean get() = paths.isNotEmpty()

    /** 批次是否已达上限（含已裁成果，拍满 12 张就的该去裁切了）。 */
    fun isFull(): Boolean = size >= MAX

    /**
     * 加入一张新拍/新选的原图。
     *
     * @return false 表示批次已满、没有加入（调用方应 toast 提示），true 表示已入队。
     */
    fun add(path: String, fromGallery: Boolean): Boolean {
        if (isFull()) return false
        paths = paths + path
        // 相机与相册混拍时以先来者为准：第一张定性，后续不改
        if (size == 1) sourceIsGallery = fromGallery
        return true
    }

    /**
     * 裁切页确认了一张：队首出队、成果入账。
     * 出队的原图顺手删掉——裁切成果已生成，原图不再被任何链路读取
     * （sessionStore 也已清），留着只会让缓存里堆一批没人引用的残影。
     *
     * 提交整批前**绝不要**对成果文件调 [clear]——识别任务还要读它们。
     */
    fun commitCropped(savedPath: String) {
        if (paths.isNotEmpty()) {
            val croppedRaw = paths.first()
            paths = paths.drop(1)
            runCatching { File(croppedRaw).delete() }
        }
        cropped = cropped + savedPath
    }

    /**
     * 放弃当前张（裁切页返回、或原图丢失跳过）：队首出队、不计成果。
     * 顺手删掉这份没裁完的原图，避免「计数 3 实际图 2」的幽灵残留。
     */
    fun dropCurrent(): String? {
        if (paths.isEmpty()) return null
        val dropped = paths.first()
        paths = paths.drop(1)
        runCatching { File(dropped).delete() }
        return dropped
    }

    /**
     * 丢弃整批：清空状态并删掉所有相关文件（未裁原图 + 已裁成果）。
     * 只在「用户确认丢弃」时调用——已提交的任务绝不能再走这里。
     */
    fun clear() {
        val files = (paths + cropped).map(::File)
        paths = emptyList()
        cropped = emptyList()
        sourceIsGallery = false
        files.forEach { runCatching { it.delete() } }
    }

    /**
     * 取走整批成果并清空队列，供提交识别。
     * 与 [clear] 的区别：**不删文件**——提交后 MinerU 还要读它们。
     */
    fun drainCropped(): List<File> {
        val files = cropped.map(::File)
        paths = emptyList()
        cropped = emptyList()
        sourceIsGallery = false
        return files
    }

    // drainRaw 已删（v0.0.75）：对话拍照恢复走裁切接力，「原图直发」路径废弃。

    companion object {
        /**
         * 单批上限 12 张：MinerU 串行解析 12 张已接近耐心极限，
         * 更大批次应该走 PDF 导入（那边有分页预览与断点）。
         */
        const val MAX = 12
    }
}
