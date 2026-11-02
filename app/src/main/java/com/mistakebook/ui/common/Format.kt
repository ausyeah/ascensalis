package com.mistakebook.ui.common

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

    /** epochDay -> yyyy-MM-dd */
    fun epochDay(day: Long?): String =
        if (day == null) "" else LocalDate.ofEpochDay(day).format(dateFormatter)

    /** 相对日期：今天 / 昨天 / N 天前 / yyyy-MM-dd */
    fun relativeDay(day: Long?, today: LocalDate = LocalDate.now()): String {
        if (day == null) return ""
        val target = LocalDate.ofEpochDay(day)
        val diff = today.toEpochDay() - target.toEpochDay()
        return when {
            diff <= 0L -> "今天"
            diff == 1L -> "昨天"
            diff < 7L -> "${diff} 天前"
            diff < 30L -> "${diff / 7} 周前"
            else -> target.format(dateFormatter)
        }
    }

    fun millisToDate(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .format(dateFormatter)

    /**
     * 字节数 -> 人类可读。全仓三处重复实现（设置页存储统计/对话附件芯片/
     * MinerU 上传日志）收敛于此：GB 档补齐、KB 取整、0 字节显示空串
     * （附件芯片拼副标题时空文件不该出现「0 B」噪声）。
     */
    fun fileSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 题干摘要：去掉换行，最多 [max] 字。 */
    fun stemDigest(stem: String, max: Int = 60): String =
        stem.replace('\n', ' ').trim().let {
            if (it.length <= max) it else it.take(max) + "…"
        }
}
