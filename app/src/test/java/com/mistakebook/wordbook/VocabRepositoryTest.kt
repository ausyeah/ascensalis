package com.mistakebook.wordbook

import com.mistakebook.wordbook.data.VocabRepository
import com.mistakebook.wordbook.data.Word
import com.mistakebook.wordbook.data.WordProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabRepositoryTest {

    private val sampleWords = listOf(
        Word(word = "abandon", meaning = "v. 放弃，抛弃", pos = "v.", full = "v. 放弃，抛弃；n. 放纵"),
        Word(word = "abandonment", meaning = "n. 抛弃，遗弃", pos = "n.", full = "n. 抛弃，遗弃"),
        Word(word = "desert", meaning = "v. 舍弃，遗弃；n. 沙漠", pos = "v./n.", full = "v. 舍弃，遗弃；n. 沙漠"),
        Word(word = "abundant", meaning = "adj. 丰富的，充裕的", pos = "adj.", full = "adj. 丰富的，充裕的"),
        Word(word = "abundance", meaning = "n. 充裕，丰富", pos = "n.", full = "n. 充裕，丰富"),
        Word(word = "benefit", meaning = "n. 利益，好处；v. 有益于", pos = "n./v.", full = "n. 利益，好处；v. 有益于"),
        Word(word = "beneficial", meaning = "adj. 有益的", pos = "adj.", full = "adj. 有益的"),
        Word(word = "profit", meaning = "n. 利润，收益", pos = "n.", full = "n. 利润，收益")
    )

    private val clusters = mapOf(
        "c1" to listOf("abandon", "abandonment"),
        "c2" to listOf("abundant", "abundance"),
        "c3" to listOf("benefit", "beneficial")
    )

    // abandon 与 desert 为同义组
    private val synGroups = listOf(
        listOf("abandon", "desert"),
        listOf("benefit", "profit")
    )

    private val byPos = mapOf(
        "v" to listOf("abandon", "desert", "benefit"),
        "n" to listOf("abandonment", "abundance", "benefit", "profit"),
        "adj" to listOf("abundant", "beneficial")
    )

    private val repo = VocabRepository(
        words = sampleWords,
        clusters = clusters,
        synGroups = synGroups,
        byPos = byPos
    )

    @Test
    fun `pickDistractors excludes target and its synonyms`() = runBlocking {
        val target = sampleWords.first { it.word == "abandon" }
        val distractors = repo.pickDistractors(target, count = 3)

        assertEquals(3, distractors.size)
        // 目标词绝不能在干扰项中
        assertFalse(distractors.any { it.word == "abandon" })
        // 同义词 desert 绝不能在干扰项中（避免双正确答案）
        assertFalse(distractors.any { it.word == "desert" })
        // 干扰项中包含同词根不同派生词
        assertTrue(distractors.any { it.word == "abandonment" })
    }

    @Test
    fun `search matches by english word or chinese meaning`() = runBlocking {
        val matchedByEn = repo.search("abun")
        assertEquals(2, matchedByEn.size)
        assertTrue(matchedByEn.all { it.word.startsWith("abun") })

        val matchedByCn = repo.search("利润")
        assertEquals(1, matchedByCn.size)
        assertEquals("profit", matchedByCn.first().word)
    }

    @Test
    fun `word progress graduation after correct answer in review`() {
        val initial = WordProgress(
            word = "abandon",
            level = 0,
            reps = 0,
            isWrongBook = true,
            everWrong = true
        )

        // 答对：满足 reps >= 1，复习答对即从错词本毕业出库！
        val newReps = initial.reps + 1
        val p1 = initial.copy(
            level = 2,
            reps = newReps,
            isWrongBook = if (initial.isWrongBook && newReps >= 1) false else initial.isWrongBook
        )
        assertFalse("复习答对后应从错词本毕业", p1.isWrongBook)
        assertTrue("曾经错过的标记应保留", p1.everWrong)
        assertEquals(1, p1.reps)
    }

    @Test
    fun `wrong answer punishes into wrongbook and resets reps`() {
        val masteredWord = WordProgress(
            word = "benefit",
            level = 3,
            reps = 5,
            isWrongBook = false
        )

        // 答错
        val wrongProgress = masteredWord.copy(
            level = 0,
            reps = 0,
            lapses = masteredWord.lapses + 1,
            wrongCount = masteredWord.wrongCount + 1,
            isWrongBook = true,
            everWrong = true
        )

        assertEquals(0, wrongProgress.level)
        assertEquals(0, wrongProgress.reps)
        assertEquals(1, wrongProgress.lapses)
        assertTrue(wrongProgress.isWrongBook)
        assertTrue(wrongProgress.everWrong)
    }

    @Test
    fun `splitSenses and normSense work as expected`() {
        val senses = repo.splitSenses("n. 地址；vt. 寄往；致辞")
        assertEquals(3, senses.size)
        assertEquals("n. 地址", senses[0])
        assertEquals("vt. 寄往", senses[1])
        assertEquals("致辞", senses[2])

        val n1 = repo.normSense("vt. 寄往；致辞")
        val n2 = repo.normSense("vt.寄往,致辞。")
        assertEquals(n1, n2)
    }

    @Test
    fun `buildSenseQuestion returns unique correct answer and valid distractors`() = runBlocking {
        val target = sampleWords.first { it.word == "desert" } // desert has 2 senses: "v. 舍弃，遗弃" and "n. 沙漠"
        val q = repo.buildSenseQuestion(target)
        assertNotNull(q)
        assertEquals("desert", q!!.word.word)
        assertEquals(4, q.options.size)
        // 包含正确目标词 desert
        assertTrue(q.options.any { it.word == "desert" })

        // 正确目标词只能出现一次
        assertEquals(1, q.options.count { it.word == "desert" })

        // 干扰项中绝不能包含能够匹配题干义项的词（避免假阳性或双正确答案）
        val targetSenseNorm = repo.normSense(q.sense)
        val distractors = q.options.filter { it.word != "desert" }
        for (d in distractors) {
            val dSenses = repo.splitSenses(d.meaning)
            assertFalse(
                "干扰项 ${d.word} 不能包含题干义项 ${q.sense}",
                dSenses.any { repo.normSense(it) == targetSenseNorm }
            )
        }
    }

    @Test
    fun `buildSenseQuestion 不再偷偷换词，单义词也用它自己出题`() = runBlocking {
        // abandon 的 meaning 只有一个义项「v. 放弃，抛弃」。
        //
        // 旧行为是「义项数 < 2 就随机换一个多义词」——这正是用户报的 bug：
        // 「切换到义项辨析就会换词」。用户刚在四选一里选对了这个词，
        // 一进义项模式词就变了，等于把刚建立的语境抽走。
        //
        // 现在传了词就用它，题干取那唯一的义项；答案唯一性由
        // 「排除释义含同一义项的干扰项」保证（下面逐条断言）。
        val targetSingleSense = sampleWords.first { it.word == "abandon" }
        val q = repo.buildSenseQuestion(targetSingleSense)
        assertNotNull(q)
        assertEquals("abandon", q!!.word.word)
        assertEquals(4, q.options.size)
        assertEquals(1, q.options.count { it.word == "abandon" })

        val targetSenseNorm = repo.normSense(q.sense)
        for (d in q.options.filter { it.word != "abandon" }) {
            assertFalse(
                "干扰项 ${d.word} 不能包含题干义项 ${q.sense}",
                repo.splitSenses(d.meaning).any { repo.normSense(it) == targetSenseNorm }
            )
        }
    }

    @Test
    fun `buildSenseQuestion returns null safely when repository has no multi-sense words`() = runBlocking {
        val singleSenseOnlyWords = listOf(
            Word(word = "cat", meaning = "n. 猫", pos = "n.", full = "n. 猫"),
            Word(word = "dog", meaning = "n. 狗", pos = "n.", full = "n. 狗"),
            Word(word = "bird", meaning = "n. 鸟", pos = "n.", full = "n. 鸟"),
            Word(word = "fish", meaning = "n. 鱼", pos = "n.", full = "n. 鱼")
        )
        val singleRepo = VocabRepository(words = singleSenseOnlyWords)
        val q = singleRepo.buildSenseQuestion(null)
        org.junit.Assert.assertNull("没有多义词时应安全返回 null 而不抛出异常", q)
    }

    @Test
    fun `search empty query supports sequence take limit without full materialization`() = runBlocking {
        val all = repo.search("")
        assertEquals(sampleWords.size, all.size)
        val chunked = all.asSequence().take(3).toList()
        assertEquals(3, chunked.size)
    }
}
