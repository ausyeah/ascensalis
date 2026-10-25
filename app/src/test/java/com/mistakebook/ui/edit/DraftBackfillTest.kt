package com.mistakebook.ui.edit

import com.mistakebook.domain.ErrorReason
import com.mistakebook.pipeline.DraftBundleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动暂存的「多题合并写回」回转测试（T4）。
 *
 * 一次识别的 DraftBundle 可能装多道题，编辑页只编辑其中一道。
 * 暂存一题时若整包重编码，同批其他题的草稿会被覆盖——这里把
 * `decode(replace(decode(x), i, draft)) == decode(x)`（除第 i 题外
 * 全部不变）钉死，见 [replaceDraftQuestionInBundle] 的注释。
 */
class DraftBackfillTest {

    private fun draftOf(stem: String): EditableDraft = EditableDraft(
        imagePath = "/tmp/photo.jpg",
        title = "标题-$stem",
        subjectName = "数学",
        stem = stem,
        answer = "答案-$stem",
        analysis = "解析-$stem",
        knowledgePoints = listOf("点-$stem"),
        errorReason = ErrorReason.CALCULATION,
        difficulty = 4
    )

    private fun bundleJson(): String = DraftBundleCodec.encode(
        com.mistakebook.pipeline.DraftBundle(
            imagePath = "/tmp/photo.jpg",
            markdown = "# 原文",
            questions = listOf(
                draftOf("第一题").toDraftQuestion(),
                draftOf("第二题").toDraftQuestion(),
                draftOf("第三题").toDraftQuestion()
            )
        )
    )

    @Test
    fun `替换中位的草稿 其余两题原样保留`() {
        val original = DraftBundleCodec.decode(bundleJson())!!
        val edited = draftOf("第二题改").copy(manualSubject = "手填学科")

        val updated = replaceDraftQuestionInBundle(bundleJson(), index = 1, draft = edited)
        assertNotNull(updated)
        val result = DraftBundleCodec.decode(updated!!)!!

        // 第 1 题换成编辑后的
        assertEquals(edited.toDraftQuestion(), result.questions[1])
        // 第 0、2 题原样保留
        assertEquals(original.questions[0], result.questions[0])
        assertEquals(original.questions[2], result.questions[2])
        // bundle 其他字段不动
        assertEquals(original.markdown, result.markdown)
        assertEquals(original.imagePath, result.imagePath)
    }

    @Test
    fun `暂存后重进编辑页 decode 出来不走样`() {
        val edited = draftOf("第一题改")
        val updated = replaceDraftQuestionInBundle(bundleJson(), index = 0, draft = edited)!!

        // 反向转换对称：replace(decode(x), i, toEditable(decode(result)[i])) 再 decode 的
        // 题干/答案与编辑值一致（这里的 toEditable 侧逻辑由 EditViewModel 持有，
        // 只验证字段级对称性：toDraftQuestion 的关键文本字段能原样回来）
        val question = DraftBundleCodec.decode(updated)!!.questions[0]
        assertEquals("第一题改", question.stem)
        assertEquals("答案-第一题改", question.answer)
        assertEquals("数学", question.subject)
        assertEquals("计算失误", question.errorReason)
        assertEquals(4, question.difficulty)
    }

    @Test
    fun `index 越界拒绝写回`() {
        // bundle 只有 3 题：越界（含 markdown 兜底的空 bundle 场景）返回 null
        assertNull(replaceDraftQuestionInBundle(bundleJson(), index = 3, draft = draftOf("x")))
        assertNull(replaceDraftQuestionInBundle(bundleJson(), index = -1, draft = draftOf("x")))
    }

    @Test
    fun `refinedJson 解析失败拒绝写回`() {
        assertNull(replaceDraftQuestionInBundle("不是 JSON", index = 0, draft = draftOf("x")))
        assertNull(replaceDraftQuestionInBundle("", index = 0, draft = draftOf("x")))
    }
}
