package com.mistakebook.util

import java.io.File
import java.security.MessageDigest

/**
 * 生图结果的磁盘 LRU 缓存。
 *
 * ## 为什么按内容 hash 命名
 *
 * key = md5(prompt + model)：同一个词、同一个义项、同一个渠道，
 * 生成的图就是确定的，第二次直接读盘，不再调 API、不再花钱。
 * prompt 里带一点点差异（大小写、标点）就是另一个 key——
 * 这是刻意行为：prompt 不同，图本来就该不同。
 *
 * ## LRU 口径
 *
 * 「最久未用」按 lastModified 近似：命中（[get]）时 touch 文件，
 * 写入（[put]）时刷新。超上限（默认 200 张）按最旧优先删。
 * 不引入 LinkedHashMap 式的内存索引——进程重启后访问历史只存在于
 * 文件时间戳里，内存索引会有「冷启动集体失忆」问题。
 *
 * 线程安全：调用方（ViewModel / 渲染层）都在 IO 或主线程单点调用，
 * 文件系统自身提供原子性，这里不加锁。
 */
class ImageDiskCache(
    private val dir: File,
    private val maxEntries: Int = MAX_ENTRIES
) {

    /** 缓存 key：prompt + 渠道模型的联合 hash。 */
    fun keyFor(prompt: String, model: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest((prompt + "\u0000" + model).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 缓存文件路径。
     *
     * [pinned] 为真的文件带 `p_` 前缀：**不参与 LRU 淘汰**。
     *
     * ## 为什么要有 pin
     *
     * 用户反馈「单词生成的图片会丢失」。根因是这里按 LRU 上限 200 张淘汰，
     * 而单词场景图是**用户主动生成的内容**——背到第 201 个词时，
     * 第一个词的图就被删了，下次翻回去只剩空白。
     * 缓存该淘汰的是「临时预览」，不是用户花钱生成的图。
     *
     * 用文件名前缀而不是单独的索引文件：索引文件会有「图还在、索引没了」
     * 的不一致状态，而前缀和文件同生共死。
     */
    fun fileFor(key: String, pinned: Boolean = false): File =
        File(dir, "${if (pinned) PIN_PREFIX else ""}$key.png")

    /** 同一 key 的两种候选（pin 优先）：读的时候两种都要认。 */
    private fun candidates(key: String): List<File> =
        listOf(fileFor(key, pinned = true), fileFor(key, pinned = false))

    /** 命中返回文件（并 touch 刷新「未使用」计时）；未命中返回 null。 */
    fun get(key: String): File? {
        val file = candidates(key).firstOrNull { it.isFile && it.length() > 0L } ?: return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /** 写入并触发淘汰。返回落盘文件。 */
    fun put(key: String, bytes: ByteArray, pinned: Boolean = false): File {
        dir.mkdirs()
        val file = fileFor(key, pinned)
        file.writeBytes(bytes)
        evictIfNeeded()
        return file
    }

    /** 只查存在性，不 touch（预加载 / 列表缩略图用，避免滚动时疯狂写时间戳）。 */
    fun peek(key: String): File? = candidates(key).firstOrNull { it.isFile && it.length() > 0L }

    /**
     * 删掉这个 key 的缓存文件（pin 与非 pin 两种候选都删）。
     *
     * ## 为什么必须有它
     *
     * 详情页的辅助图每次进页都会按 key 从缓存**回填**（DetailViewModel.init），
     * 只清界面状态的话，下次进来图又原样出现——用户看到的「删除」
     * 形同虚设。真正的删除必须落在这层：文件没了，回填自然找不到。
     */
    fun remove(key: String) {
        candidates(key).forEach { file -> runCatching { file.delete() } }
    }

    /** 超上限时按最旧优先淘汰——**只淘汰未 pin 的**。 */
    fun evictIfNeeded() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(PIN_PREFIX) }
            ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { runCatching { it.delete() } }
    }

    /** 把已存在的一张图固定住（不再被淘汰）。 */
    fun pin(key: String) {
        val current = candidates(key).firstOrNull { it.isFile && it.length() > 0L } ?: return
        val target = fileFor(key, pinned = true)
        if (current == target) return
        runCatching { current.renameTo(target) }
    }

    /** 当前缓存张数（设置页展示用）。 */
    fun count(): Int = dir.listFiles()?.count { it.isFile } ?: 0

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    companion object {
        /** 上限 200 张：按每张 100~300KB 估，约 20~60MB，可接受。 */
        const val MAX_ENTRIES = 200

        /** pin 标记：文件名前缀。见 [fileFor]。 */
        const val PIN_PREFIX = "p_"
    }
}
