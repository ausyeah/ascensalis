package com.mistakebook.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把相册选中的图片导入 app 目录。
 *
 * 关键改动：不再做「裸字节拷贝」。
 * 裸拷贝会把 EXIF 方向标记原封不动带进新文件，而 BitmapFactory 不认 EXIF，
 * 导致「明明调正了却又歪回去」。这里统一走 [ImageNormalizer]：
 * 方向烘进像素 + 统一转 JPEG。
 *
 * ## 为什么不在这里做对比度增强（v0.0.55 反馈）
 *
 * 增强的权威位置是**裁剪页**：那里有 0~3 档实时预览，用户裁完框选、转正之后
 * 能直接看到增强效果再定档。若在导入时就按设置的档位增强，裁剪页再增强就是
 * 叠加强化——[ImageNormalizer.enhanceForOcr] 不幂等（Otsu 阈值会按已增强的
 * 直方图再算一次），而且「关」档也没有原像素可回。
 *
 * 所以这里只做 `strength = 0` 的归一化：方向烘进像素 + 转 JPEG。
 */
class ImageImporter(
    private val context: Context,
    private val files: AppFiles
) {

    suspend fun importUris(uris: List<Uri>): List<File> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri -> runCatching { importOne(uri) }.getOrNull() }
    }

    private fun importOne(uri: Uri): File? {
        val target = files.newImportFile("jpg")
        // 先落到临时文件：归一化要能按路径读两次（EXIF 一次、解码一次）
        val staging = files.newImportFile("src")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            staging.length() > 0
        }.getOrDefault(false)
        if (!copied) {
            staging.delete()
            return null
        }

        // 只归一化方向、不增强：增强档位由裁剪页决定（见类注释）
        val ok = ImageNormalizer.normalize(staging, target, 0)
        staging.delete()
        if (!ok) {
            // 归一化失败（极端损坏的图片）不该让整批导入作废，退回原始字节
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return if (target.length() > 0) target else null
        }
        return target
    }
}
