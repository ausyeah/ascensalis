package com.mistakebook.data.library

import com.mistakebook.data.local.LibraryChunkDao
import com.mistakebook.data.local.entities.LibraryChunkEntity
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.math.VectorMath
import com.mistakebook.net.ApiResult
import com.mistakebook.net.llm.EmbeddingClient
import com.mistakebook.net.llm.EmbeddingConfig
import com.mistakebook.net.llm.EmbeddingEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 资料库向量索引器（v0.0.113）。
 *
 * ## 流程（照 zvec 的小规模姿势）
 * 每份资料 → 提正文 → [LibraryChunker.chunk] → 与库里已有块按
 * (chunkIndex, contentHash) 对齐 → **只有新增/修改的块**发嵌入请求 →
 * 写回。hash 没变的块直接复用，同一份资料反复重建不产生重复流量。
 *
 * ## 为什么按文档做检查点
 * 嵌入要联网，一次索引可能发几十个请求。逐块写库 = 逐块 checkpoint：
 * 中途断网/杀进程，已完成的部分留在库里，下次重跑只补剩下的——
 * 这就是 zvec「WAL + 检查点」在单机小场景下的等价简化。
 *
 * ## 单写者
 * 与 zvec 相同的约束：**不支持并发写**。所有重建走一把互斥锁，
 * 重复点击「建立索引」只会排队或直接看到进行中的进度。
 *
 * @param libraryRoot 资料库目录（files/library）。
 * @param pdfOcrFallback 扫描型 PDF 的转写兜底（v0.0.131）；返回 null/空白
 *   = 该资料依然没有可检索内容（不算失败）。默认无操作，AppContainer 装配。
 */
class LibraryIndexer(
    private val libraryRoot: java.io.File,
    private val chunkDao: LibraryChunkDao,
    private val embeddingClient: EmbeddingClient,
    private val settingsStore: SettingsStore,
    private val pdfOcrFallback: suspend (pdf: java.io.File, onStage: suspend (String) -> Unit) -> String? =
        { _, _ -> null }
) {

    /** 索引进度（Idle = 从未跑过；Failed 带原因；Ready 带块数）。 */
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val docs: Int = 0,
        val chunks: Int = 0,
        val lastError: String? = null,
        /** 阶段文案（OCR 兜底这类长流程用）；有值时优先于「x / y」展示。 */
        val stage: String? = null
    )

    private val mutex = Mutex()
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * 从库里的块回填状态行（v0.0.119）。
     *
     * 进度是内存态：进程重启后回到 Idle，界面就会写「尚未建立索引」，
     * 可库里明明有块、搜索也照常能用。进页面时读一次库把数字补回来——
     * 只有本来就没状态、且不在跑的时候才回填，绝不覆盖运行中的进度。
     */
    suspend fun hydrate() {
        if (!shouldHydrate(_progress.value)) return
        val counts = runCatching { chunkDao.countsByDoc() }.getOrDefault(emptyList())
        if (counts.isEmpty()) return
        _progress.value = Progress(docs = counts.size, chunks = counts.sumOf { it.chunks })
    }

    /**
     * 全量重建索引（增量：未变化的块不重新嵌入）。
     *
     * @param config 向量化配置（地址 / Key / 模型名）；地址与 Key 留空时回退到
     *   [activeProfile]，即「借对话配置」的老路径
     * @param activeProfile 当前生效的大模型配置，只在 [config] 缺项时兜底
     */
    suspend fun reindex(config: EmbeddingConfig, activeProfile: LlmProfile?): Progress {
        _progress.value = Progress(running = true)
        val result = mutex.withLock {
            runCatching { reindexLocked(config, activeProfile) }.getOrElse { error ->
                Progress(
                    chunks = chunkDao.count(),
                    lastError = error.message ?: error.javaClass.simpleName
                )
            }
        }
        _progress.value = result
        return result
    }

    private suspend fun reindexLocked(config: EmbeddingConfig, profile: LlmProfile?): Progress {
        if (config.model.isBlank()) {
            return Progress(chunks = chunkDao.count(), lastError = "还没有填写向量化模型")
        }
        val endpoint = EmbeddingEndpoint.resolve(config, profile)
            ?: return Progress(chunks = chunkDao.count(), lastError = "还没有配置接口地址或 Key")

        val files = LibraryStore.list(libraryRoot)
        if (files.isEmpty()) {
            chunkDao.clear()
            return Progress(docs = 0, chunks = 0)
        }

        var done = 0
        // 总块数要先全部切出来才知道；文本提取也在这一步做（IO）。
        data class DocChunks(val name: String, val chunks: List<String>)
        val docs = ArrayList<DocChunks>()
        files.forEach { file ->
            val detailed = runCatching { LibraryStore.readDetailed(file) }.getOrNull()
            val text = detailed?.text.orEmpty()
            if (detailed == null) return@forEach
            if (!detailed.scannedPdf) {
                docs.add(DocChunks(file.name, LibraryChunker.chunk(file.name, text)))
                return@forEach
            }
            // 扫描/无 ToUnicode PDF（v0.0.131）：零依赖抽取器读不出可读文本，
            // 走 OCR 兜底——渲染页图交给当前识别渠道逐页转写。转写失败
            // （无渠道配置/网络断）不算索引失败，只是这份资料没有可检索内容，
            // 与此前「直接跳过」的语义一致，只是多了一次抢救机会。
            _progress.value = Progress(running = true, stage = "正在识别 ${file.name} …")
            val ocrText = runCatching {
                pdfOcrFallback(file) { stage ->
                    _progress.value = Progress(running = true, stage = stage)
                }
            }.getOrNull().orEmpty()
            if (ocrText.isNotBlank()) {
                docs.add(DocChunks(file.name, LibraryChunker.chunk(file.name, ocrText)))
            }
        }
        val total = docs.sumOf { it.chunks.size }

        // 模型名变了等于换了一套向量空间，旧块必须重算——这里按模型名对齐
        val modelTag = endpoint.model
        docs.forEach { doc ->
            val existing = chunkDao.byDoc(doc.name)
                .associateBy { it.chunkIndex to it.contentHash }
            val keep = mutableListOf<LibraryChunkEntity>()
            val pending = mutableListOf<Pair<Int, String>>() // (chunkIndex, text)

            doc.chunks.forEachIndexed { index, text ->
                val hash = LibraryChunker.contentHash(text)
                val hit = existing[index to hash]
                if (hit != null && hit.model == modelTag) {
                    keep.add(hit)
                } else {
                    pending.add(index + 1 to text)
                }
            }

            // 没有变化也要清理：文档被截断（块变少）时，旧的多余块要删
            chunkDao.deleteDoc(doc.name)

            // 先落「未变化」的块（checkpoint），失败的批次不影响它们
            if (keep.isNotEmpty()) chunkDao.insert(keep)

            pending.chunked(embeddingClient.batchSize).forEach { batch ->
                val embedded = when (
                    val result = embeddingClient.embed(endpoint, batch.map { it.second })
                ) {
                    is ApiResult.Success -> result.data
                    is ApiResult.Failure -> throw IllegalStateException(
                        result.error.serverMessage.ifBlank { result.error.kind.name }
                    )
                }
                if (embedded.size != batch.size) {
                    throw IllegalStateException("嵌入返回条数与请求不一致")
                }
                val now = System.currentTimeMillis()
                val rows = batch.mapIndexed { i, (_, text) ->
                    LibraryChunkEntity(
                        docName = doc.name,
                        chunkIndex = batch[i].first,
                        contentHash = LibraryChunker.contentHash(text),
                        text = text,
                        dim = embedded[i].size,
                        model = modelTag,
                        vector = VectorMath.pack(embedded[i]),
                        updatedAt = now
                    )
                }
                chunkDao.insert(rows)
                done += rows.size
                _progress.value = Progress(running = true, done = done, total = total, docs = docs.size)
            }
            // 该文档无新块时也要推进进度（done 按「已对齐块数」计）
            if (pending.isEmpty()) {
                done += keep.size
                _progress.value = Progress(running = true, done = done, total = total, docs = docs.size)
            }
        }

        // 清掉已删除文档的残留块
        val liveNames = docs.map { it.name }.toSet()
        chunkDao.allOnce()
            .filter { it.docName !in liveNames }
            .map { it.docName }
            .distinct()
            .forEach { chunkDao.deleteDoc(it) }

        return Progress(done = total, total = total, docs = docs.size, chunks = chunkDao.count())
    }

    /**
     * 语义检索：查询文本嵌入后对全库块做余弦，再与关键词命中做 RRF 融合。
     *
     * 嵌入模型未配置 / 查询嵌入失败时返回空列表（上层回退纯关键词检索）。
     */
    suspend fun search(
        query: String,
        config: EmbeddingConfig,
        profile: LlmProfile?,
        topK: Int = 8
    ): List<LibrarySearchFusion.Hit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rows = chunkDao.allOnce()
        if (rows.isEmpty()) return emptyList()

        // 关键词路：整串包含（大小写不敏感）即命中，按命中长度占比排
        val keywordRanked = rows
            .mapNotNull { row ->
                val at = row.text.indexOf(trimmed, ignoreCase = true)
                if (at < 0) null else LibrarySearchFusion.Hit(
                    docName = row.docName,
                    chunkIndex = row.chunkIndex,
                    text = row.text,
                    score = (trimmed.length.toDouble() / row.text.length)
                )
            }
            .sortedByDescending { it.score }

        // 向量路：查询嵌入失败就只留关键词路（功能降级，不报错打断用户）
        val endpoint = EmbeddingEndpoint.resolve(config, profile)
        val vectorRanked = if (endpoint != null) {
            val queryVector = when (
                val result = embeddingClient.embed(endpoint, listOf(trimmed))
            ) {
                is ApiResult.Success -> result.data.firstOrNull()
                is ApiResult.Failure -> null
            }
            if (queryVector != null && queryVector.isNotEmpty()) {
                rows.mapNotNull { row ->
                    val stored = VectorMath.unpack(row.vector)
                    if (stored.isEmpty() || stored.size != queryVector.size) return@mapNotNull null
                    LibrarySearchFusion.Hit(
                        docName = row.docName,
                        chunkIndex = row.chunkIndex,
                        text = row.text,
                        score = VectorMath.cosine(queryVector, stored).toDouble()
                    )
                }.sortedByDescending { it.score }
                    .take(topK * 2)
            } else emptyList()
        } else emptyList()

        return LibrarySearchFusion.fuse(vectorRanked, keywordRanked, topK)
    }

    companion object {
        /**
         * 把逐页 OCR 结果拼成整册正文（v0.0.131）。
         *
         * 列表按**原 PDF 页序**排列，失败的页是 null——页码用「位置 + 1」
         * 标注，第 3 页失败不会让第 4 页被错标成第 3 页；空白/失败页整页
         * 剔除，正文引用「第 N 页」时必须与 PDF 实际页码对得上。
         */
        internal fun assembleOcrPages(pages: List<String?>): String =
            pages.mapIndexed { index, text ->
                val body = text?.trim().orEmpty()
                if (body.isEmpty()) null else "【第 ${index + 1} 页】\n$body"
            }.filterNotNull().joinToString("\n\n")
    }
}

/**
 * 状态行该不该从库里回填：只有「没在跑且显示 0 块」的 Idle 才回填。
 *
 * 纯函数便于单测：回填本身要读 Room，而这条判断才是会被改坏的地方
 * ——写成无条件回填，运行中的进度条会被一次页面进入打回 0。
 */
internal fun shouldHydrate(current: LibraryIndexer.Progress): Boolean =
    !current.running && current.chunks == 0
