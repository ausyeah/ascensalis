package com.mistakebook.domain

import com.mistakebook.data.local.entities.Question
import com.mistakebook.wordbook.data.VocabRepository
import com.mistakebook.wordbook.data.Word
import com.mistakebook.wordbook.data.WordProgress
import com.mistakebook.wordbook.data.WordbookDao
import com.mistakebook.wordbook.data.WordStudyLog
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.domain.ErrorReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeWordbookDao(private val progressMap: Map<String, WordProgress>) : WordbookDao {
    override fun observeAllProgress(): Flow<List<WordProgress>> = emptyFlow()
    override suspend fun getProgress(word: String): WordProgress? = progressMap[word]
    override fun observeWrongBook(): Flow<List<WordProgress>> = emptyFlow()
    override fun observeEverWrong(): Flow<List<WordProgress>> = emptyFlow()
    override fun observeMastered(): Flow<List<WordProgress>> = emptyFlow()
    override fun observeFavorite(): Flow<List<WordProgress>> = emptyFlow()
    override suspend fun upsertProgress(progress: WordProgress) {}
    override suspend fun upsertAllProgress(list: List<WordProgress>) {}
    override suspend fun insertStudyLog(log: WordStudyLog) {}
    override fun observeStudiedTodayCount(startOfDayMs: Long): Flow<Int> = emptyFlow()
    override fun observeMasteredCount(): Flow<Int> = emptyFlow()
    override fun observeWrongBookCount(): Flow<Int> = emptyFlow()
    override fun observeFavoriteCount(): Flow<Int> = emptyFlow()
    override suspend fun logsSince(sinceMs: Long): List<WordStudyLog> = emptyList()
    override suspend fun clearLogs() {}
    override suspend fun clearLogsSince(startOfDayMs: Long) {}
    override suspend fun clearAllProgress() {}
}

class VocabQuestionLinkerTest {
    
    private val vocabWords = listOf(
        Word("abandon", "v. 放弃"),
        Word("atom", "n. 原子"),
        Word("subatomic", "adj. 亚原子的")
    )
    
    private val vocabRepo = VocabRepository(vocabWords)
    
    private val progressMap = mapOf(
        "abandon" to WordProgress("abandon", level = 2)
    )
    private val wordbookDao = FakeWordbookDao(progressMap)
    
    private val linker = VocabQuestionLinker(vocabRepo, wordbookDao)
    
    @Test
    fun testFindQuestionsByWord_exactWordBoundary() {
        val q1 = Question(
            subjectId = 1,
            imagePath = "",
            mineruMarkdown = "",
            stem = "The scientist had to abandon the subatomic experiment.",
            optionsJson = "[]",
            answer = "A",
            analysis = "",
            title = "Question 1",
            knowledgePointsJson = "[]",
            errorReason = ErrorReason.OTHER,
            difficulty = 3,
            status = MasteryStatus.ACTIVE,
            createdAt = 0L,
            updatedAt = 0L
        )
        
        val q2 = Question(
            subjectId = 1,
            imagePath = "",
            mineruMarkdown = "",
            stem = "An atom is very small.",
            optionsJson = "[]",
            answer = "B",
            analysis = "",
            title = "Question 2",
            knowledgePointsJson = "[]",
            errorReason = ErrorReason.OTHER,
            difficulty = 3,
            status = MasteryStatus.ACTIVE,
            createdAt = 0L,
            updatedAt = 0L
        )
        
        // Match 'atom' -> only q2, not q1 (subatomic)
        val results = linker.findQuestionsByWord("atom", listOf(q1, q2))
        assertEquals(1, results.size)
        assertEquals("Question 2", results[0].title)
        assertTrue(results[0].snippet.contains("atom"))
        
        // Match 'abandon' -> q1
        val results2 = linker.findQuestionsByWord("abandon", listOf(q1, q2))
        assertEquals(1, results2.size)
        assertEquals("Question 1", results2[0].title)
    }

    @Test
    fun testFindQuestionsByWord_caseInsensitive() {
        val q1 = Question(
            subjectId = 1,
            imagePath = "",
            mineruMarkdown = "",
            stem = "Abandon all hope.",
            optionsJson = "[]",
            answer = "",
            analysis = "",
            title = "Hope",
            knowledgePointsJson = "[]",
            errorReason = ErrorReason.OTHER,
            difficulty = 3,
            status = MasteryStatus.ACTIVE,
            createdAt = 0L,
            updatedAt = 0L
        )
        val results = linker.findQuestionsByWord("abandon", listOf(q1))
        assertEquals(1, results.size)
        assertTrue(results[0].snippet.contains("Abandon"))
    }

    @Test
    fun testExtractVocabFromText() = runBlocking {
        val text = "We should abandon the old way of studying the atom."
        val results = linker.extractVocabFromText(text)
        
        assertEquals(2, results.size)
        val words = results.map { it.word.word }.toSet()
        assertTrue(words.contains("abandon"))
        assertTrue(words.contains("atom"))
        
        val abandonRes = results.find { it.word.word == "abandon" }
        assertEquals(2, abandonRes?.progress?.level)
        
        val atomRes = results.find { it.word.word == "atom" }
        assertEquals(null, atomRes?.progress)
    }
    
    @Test
    fun testEmptyInputs() = runBlocking {
        val q1 = Question(
            subjectId = 1,
            imagePath = "",
            mineruMarkdown = "",
            stem = "abandon",
            optionsJson = "[]",
            answer = "",
            analysis = "",
            title = "",
            knowledgePointsJson = "[]",
            errorReason = ErrorReason.OTHER,
            difficulty = 3,
            status = MasteryStatus.ACTIVE,
            createdAt = 0L,
            updatedAt = 0L
        )
        assertTrue(linker.findQuestionsByWord("", listOf(q1)).isEmpty())
        assertTrue(linker.extractVocabFromText("").isEmpty())
    }
}
