package com.mistakebook.net.mineru

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** MinerU v4 统一响应信封。 */
@Serializable
data class MineruEnvelope<T>(
    val code: Int = 0,
    val msg: String = "",
    val data: T? = null
) {
    fun isOk(): Boolean = code == 0
}

@Serializable
data class FileUrlsRequest(
    val files: List<FileUrlsItem>,
    val model_version: String,
    val language: String,
    val enable_formula: Boolean = true,
    val enable_table: Boolean = true
)

@Serializable
data class FileUrlsItem(
    val name: String,
    val is_ocr: Boolean
)

@Serializable
data class FileUrlsData(
    val batch_id: String,
    val file_urls: List<String> = emptyList()
)

@Serializable
data class ExtractResultsData(
    val extract_result: List<ExtractResultItem> = emptyList()
)

@Serializable
data class ExtractResultItem(
    // 实测字段名是 file_name（PRD 写的 name 与官方响应不一致，这里按官方响应来）
    @SerialName("file_name") val fileName: String = "",
    val name: String = "",
    val state: String = "",
    @SerialName("full_zip_url") val fullZipUrl: String? = null,
    @SerialName("err_msg") val errMsg: String? = null,
    @SerialName("extract_progress") val extractProgress: JsonElement? = null
) {
    /**
     * 解析进度百分比。
     *
     * ## `extract_progress` 是**对象**，不是 0-100 标量
     *
     * 官方响应形如：
     * ```json
     * "extract_progress": { "extracted_pages": 1, "total_pages": 2, "start_time": "..." }
     * ```
     *
     * 早先按标量处理：`extractProgress?.toString()` 得到 `"{extracted_pages=1, ...}"`，
     * `toDoubleOrNull()` 恒为 null → **整个轮询循环里一次都没算出来过百分比**。
     * 配上界面文案被覆盖成常量（见 `CaptureTaskDao.moveToParsing`），
     * 用户看到的就是「一直转圈 + 同一句话」，与「卡死」无法区分。
     *
     * PRD 里写的「0-100」是错的，实现照抄了。已在 PRD 修正。
     */
    fun progressPercent(): Int? {
        val obj = extractProgress as? JsonObject ?: return null
        val extracted = obj.intOrNull("extracted_pages") ?: return null
        val total = obj.intOrNull("total_pages") ?: return null
        if (total <= 0) return null
        return (extracted.toLong() * 100 / total).toInt().coerceIn(0, 99)
    }

    /**
     * 本次解析的**总页数**（MinerU 的计费单位）。
     *
     * 免费额度按页算（2000 页/天），所以「解析了几次」说明不了花了多少
     * 额度——一份 50 页的 PDF 和一张截图都只算 1 次。用量概览要把
     * 页数单独列出来（用户反馈「对话和识别都要计算在内」）。
     *
     * 服务端在 `extract_progress.total_pages` 里给；拿不到时返回 0，
     * 由调用方决定记 0 还是不记。
     */
    fun totalPages(): Int {
        val obj = extractProgress as? JsonObject ?: return 0
        return obj.intOrNull("total_pages") ?: 0
    }

    /**
     * 安全取整数。
     *
     * 不能用 `jsonPrimitive`：它在元素不是 primitive 时**抛异常**，
     * 而服务端字段类型不稳定（有时给数字、有时给字符串、有时不给）。
     * 一旦抛，整个轮询循环就崩了——而这只是取个进度显示。
     */
    private fun JsonObject.intOrNull(key: String): Int? {
        val element = this[key] ?: return null
        if (element !is JsonPrimitive) return null
        return runCatching { element.intOrNull }.getOrNull()
    }
}
