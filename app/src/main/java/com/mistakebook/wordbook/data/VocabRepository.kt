package com.mistakebook.wordbook.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStreamReader
import kotlin.random.Random

/**
 * 单词词库仓储（管理内置 4356 词及词根干扰项索引）。
 */
class VocabRepository(private val context: Context? = null) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val mutex = Mutex()
    private var wordsList: List<Word> = emptyList()
    private var wordsMap: Map<String, Word> = emptyMap()

    // 索引缓存
    private var clusterOf: Map<String, List<String>> = emptyMap()
    private var synIdOf: Map<String, Int> = emptyMap()
    private var byPos: Map<String, List<String>> = emptyMap()

    /**
     * 加载标记必须 @Volatile：[ensureLoaded] 的热路径在锁外直接读它，
     * 而真正的加载发生在 IO 线程。没有 volatile 的话，调用方线程可能一直
     * 读到过期的 false，每次都去切线程抢锁。
     */
    @Volatile
    private var isLoaded = false

    /**
     * 单元测试专用构造器：支持脱离 Android Context 独立验证同根干扰项与同义词过滤算法。
     */
    constructor(
        words: List<Word>,
        clusters: Map<String, List<String>> = emptyMap(),
        synGroups: List<List<String>> = emptyList(),
        byPos: Map<String, List<String>> = emptyMap()
    ) : this(null) {
        this.wordsList = words
        this.wordsMap = words.associateBy { it.word }
        val clusterMap = mutableMapOf<String, MutableList<String>>()
        clusters.values.forEach { group ->
            group.forEach { w ->
                clusterMap.getOrPut(w) { mutableListOf() }.addAll(group)
            }
        }
        this.clusterOf = clusterMap
        val synMap = mutableMapOf<String, Int>()
        synGroups.forEachIndexed { groupIndex, group ->
            group.forEach { w -> synMap[w] = groupIndex }
        }
        this.synIdOf = synMap
        this.byPos = byPos
        this.isLoaded = true
    }

    /**
     * 确保词库与索引已载入内存。
     */
    suspend fun ensureLoaded() {
        // 热路径 fast-path：抽一个词要调好几次（getAllWords / getWord /
        // splitSenses / 干扰项），每次都 withContext(IO) + mutex 的话，
        // 一轮答题就是几千次线程切换。已加载时**零 dispatch**直接返回。
        if (isLoaded) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (isLoaded) return@withLock
                loadVocabInternal()
                loadIndexInternal()
                isLoaded = true
            }
        }
    }

    private fun loadVocabInternal() {
        val ctx = context ?: return
        runCatching {
            ctx.assets.open("vocab.json").use { inputStream ->
                InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                    val content = reader.readText()
                    val parsed = json.decodeFromString<VocabFile>(content)
                    wordsList = parsed.words
                    wordsMap = wordsList.associateBy { it.word }
                }
            }
        }.onFailure {
            android.util.Log.e("VocabRepository", "加载 vocab.json 失败", it)
        }
    }

    private fun loadIndexInternal() {
        val ctx = context ?: return
        runCatching {
            ctx.assets.open("vocab-index.json").use { inputStream ->
                InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                    val content = reader.readText()
                    val idx = json.decodeFromString<VocabIndexFile>(content)

                    val clusterMap = mutableMapOf<String, MutableList<String>>()
                    idx.clusters.values.forEach { group ->
                        group.forEach { w ->
                            clusterMap.getOrPut(w) { mutableListOf() }.addAll(group)
                        }
                    }
                    clusterOf = clusterMap

                    val synMap = mutableMapOf<String, Int>()
                    idx.synGroups.forEachIndexed { groupIndex, group ->
                        group.forEach { w -> synMap[w] = groupIndex }
                    }
                    synIdOf = synMap
                    byPos = idx.byPos
                }
            }
        }.onFailure {
            android.util.Log.w("VocabRepository", "加载 vocab-index.json 索引失败，降级为全库随机", it)
        }
    }

    suspend fun getAllWords(): List<Word> {
        ensureLoaded()
        return wordsList
    }

    suspend fun getWord(word: String): Word? {
        ensureLoaded()
        return wordsMap[word]
    }

    suspend fun search(query: String): List<Word> {
        ensureLoaded()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return wordsList
        return wordsList.filter { w ->
            w.word.lowercase().contains(q) || w.meaning.lowercase().contains(q)
        }
    }

    /**
     * 智能四选一干扰项抽取算法（继承原单词书四层分簇逻辑）：
     *
     * L1: 同词根簇 + 同词性（形近辨析价值最高）
     * L2: 同词根簇（跨词性）
     * L3: 同词性全库
     * L4: 全库随机兜底
     *
     * 严格排除同义组词和相同释义文本，避免出现双正确答案。
     */
    suspend fun pickDistractors(target: Word, count: Int = 3): List<Word> {
        ensureLoaded()
        if (wordsList.size <= 1) return emptyList()

        val picked = mutableListOf<Word>()
        val usedWords = mutableSetOf(target.word)
        val usedMeanings = mutableSetOf(target.meaning)
        val targetSynId = synIdOf[target.word]

        val targetPosSet = splitPos(target.pos)

        fun tryAdd(candidates: List<String>) {
            if (picked.size >= count) return
            val shuffled = candidates.shuffled(Random)
            for (wName in shuffled) {
                if (picked.size >= count) break
                val w = wordsMap[wName] ?: continue
                if (w.word in usedWords || w.meaning in usedMeanings) continue
                // 同义组排除
                val sId = synIdOf[w.word]
                if (targetSynId != null && sId != null && targetSynId == sId) continue

                picked.add(w)
                usedWords.add(w.word)
                usedMeanings.add(w.meaning)
            }
        }

        // L1 & L2: 同簇
        val clusterWords = clusterOf[target.word] ?: emptyList()
        if (clusterWords.isNotEmpty()) {
            val samePosSiblings = clusterWords.filter { sib ->
                val sibWord = wordsMap[sib]
                sibWord != null && overlaps(splitPos(sibWord.pos), targetPosSet)
            }
            tryAdd(samePosSiblings)
            tryAdd(clusterWords)
        }

        // L3: 同词性桶
        for (pos in targetPosSet) {
            val posWords = byPos[pos] ?: emptyList()
            tryAdd(posWords)
            if (picked.size >= count) break
        }

        // L4: 全库兜底
        if (picked.size < count) {
            val allWordNames = wordsList.map { it.word }
            tryAdd(allWordNames)
        }

        return picked.take(count)
    }

    /**
     * 拆分义项：词库的 meaning 形如「n. 地址；vt. 寄往」，用「；」分出多个义项。
     */
    fun splitSenses(meaning: String): List<String> {
        return meaning.split("；", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * 义项归一化，用于判断两个义项是否等价（避免出现两个正确答案）。
     */
    fun normSense(s: String): String {
        return s.replace(Regex("[（）()\\s、，,；;。.．·:：/／]"), "").lowercase()
    }

    /**
     * 多义词池：有 2 个及以上义项的词。
     */
    suspend fun getMultiSenseWords(): List<Word> {
        ensureLoaded()
        return wordsList.filter { splitSenses(it.meaning).size >= 2 }
    }

    /**
     * 义项辨析出题算法（克制熟词僻义假阳性）：
     *
     * 题干呈现单词的具体某个特定义项（如「vt. 寄往；致辞」），
     * 选项为 4 个英文单词。
     *
     * 干扰项选取规则：
     * 1. 优先同簇（形近词中挑义项最接近者，辨析度最高）
     * 2. 次选同词性全库
     * 3. 严格排除释义中也包含该义项的词（确保答案绝对唯一）
     * 4. 严格排除同义组词
     */
    suspend fun buildSenseQuestion(targetWord: Word? = null): SenseQuestion? {
        ensureLoaded()
        val multiWords = getMultiSenseWords()
        if (multiWords.isEmpty()) return null

        val word = if (targetWord != null && splitSenses(targetWord.meaning).size >= 2) {
            targetWord
        } else {
            multiWords.random(Random)
        }

        val senses = splitSenses(word.meaning)
        if (senses.isEmpty()) return null
        val sense = senses.random(Random)
        val targetNorm = normSense(sense)
        val sid = synIdOf[word.word]
        val myPos = splitPos(word.pos)

        val picked = mutableListOf<Word>()
        val usedWords = mutableSetOf(word.word)

        fun tryAdd(candidates: List<String>) {
            if (picked.size >= 3) return
            for (wName in candidates.shuffled(Random)) {
                if (picked.size >= 3) break
                val w = wordsMap[wName] ?: continue
                if (w.word in usedWords || w.meaning.isBlank()) continue
                val wsid = synIdOf[w.word]
                if (sid != null && wsid != null && sid == wsid) continue
                // 排除释义中包含相同义项的词，防止双正确答案
                if (splitSenses(w.meaning).any { normSense(it) == targetNorm }) continue

                picked.add(w)
                usedWords.add(w.word)
            }
        }

        // L1 & L2: 同簇
        val clusterWords = clusterOf[word.word] ?: emptyList()
        if (clusterWords.isNotEmpty()) {
            val samePosSiblings = clusterWords.filter { sib ->
                val sibWord = wordsMap[sib]
                sibWord != null && overlaps(splitPos(sibWord.pos), myPos)
            }
            tryAdd(samePosSiblings)
            tryAdd(clusterWords)
        }

        // L3: 同词性
        for (p in myPos) {
            val posWords = byPos[p] ?: emptyList()
            tryAdd(posWords)
            if (picked.size >= 3) break
        }

        // L4: 全库兜底
        if (picked.size < 3) {
            tryAdd(wordsList.map { it.word })
        }

        if (picked.size < 3) return null

        val allOpts = (listOf(word) + picked).shuffled(Random)
        return SenseQuestion(
            word = word,
            sense = sense,
            options = allOpts
        )
    }

    private fun splitPos(pos: String): List<String> {
        return pos.split("/", "／", " ")
            .map { it.trim().removeSuffix(".") }
            .filter { it.isNotBlank() && it.all { ch -> ch.isLetter() } }
            .ifEmpty { listOf("x") }
    }

    private fun overlaps(a: List<String>, b: List<String>): Boolean {
        return a.any { it in b }
    }
}

/**
 * 义项辨析题目数据结构。
 */
data class SenseQuestion(
    val word: Word,
    val sense: String,
    val options: List<Word>
)
