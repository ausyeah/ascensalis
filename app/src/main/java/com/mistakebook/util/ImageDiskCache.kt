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

    fun fileFor(key: String): File = File(dir, "$key.png")

    /** 命中返回文件（并 touch 刷新「未使用」计时）；未命中返回 null。 */
    fun get(key: String): File? {
        val file = fileFor(key)
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /** 写入并触发淘汰。返回落盘文件。 */
    fun put(key: String, bytes: ByteArray): File {
        dir.mkdirs()
        val file = fileFor(key)
        file.writeBytes(bytes)
        evictIfNeeded()
        return file
    }

    /** 只查存在性，不 touch（预加载 / 列表缩略图用，避免滚动时疯狂写时间戳）。 */
    fun peek(key: String): File? = fileFor(key).takeIf { it.isFile && it.length() > 0L }

    /** 超上限时按最旧优先淘汰。 */
    fun evictIfNeeded() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { runCatching { it.delete() } }
    }

    /** 当前缓存张数（设置页展示用）。 */
    fun count(): Int = dir.listFiles()?.count { it.isFile } ?: 0

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    companion object {
        /** 上限 200 张：按每张 100~300KB 估，约 20~60MB，可接受。 */
        const val MAX_ENTRIES = 200
    }
}
