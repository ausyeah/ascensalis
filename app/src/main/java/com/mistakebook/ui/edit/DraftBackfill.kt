package com.mistakebook.ui.edit

import com.mistakebook.pipeline.DraftBundle
import com.mistakebook.pipeline.DraftBundleCodec
import com.mistakebook.pipeline.DraftQuestion
import com.mistakebook.pipeline.RefinedOptionDto

/**
 * [EditableDraft] → [DraftQuestion] 的反向转换（T4 自动暂存）。
 *
 * 编辑页本来只存在 DraftQuestion → EditableDraft 的正向转换（[EditViewModel.toEditable]），
 * 暂存要把用户改过的内容写回 refinedJson，就必须有反向。
 * 字段对应关系与正向严格对称，暂存→重进编辑页 decode 出来才不会走样：
 * - subject：与 [EditableDraft.toDomainDraft] 同规则——手填学科优先于识别结果；
 * - figurePaths 不进 DraftQuestion（它没有这个字段），附图引用本来就活在
 *   markdown 里，写回时 bundle 的 markdown 原样保留，不在这里丢；
 * - note / notebookId / subjectId 是编辑页/保存链路的会话级字段，
 *   DraftQuestion 里没有对应物，暂存不覆盖它们。
 */
fun EditableDraft.toDraftQuestion(): DraftQuestion = DraftQuestion(
    subject = manualSubject.trim().ifBlank { subjectName },
    title = title,
    stem = stem,
    options = options.map { option ->
        RefinedOptionDto(label = option.label, text = option.text)
    },
    answer = answer,
    analysis = analysis,
    knowledgePoints = knowledgePoints,
    errorReason = errorReason.label,
    difficulty = difficulty
)

/**
 * 把 index 处的那一份草稿替换为 [draft]，bundle 其余题**原样保留**（T4）。
 *
 * 一次识别的 DraftBundle 可能装多道题，编辑页经 initialIndex 只编辑其中一道。
 * 暂存一题时如果整包重编码（拿编辑页的 drafts 列表全量重建），同批其他题
 * 会被覆盖成编辑页的当前状态——共享字段（markdown 等）没问题，但其他题
 * 的编辑（用户在题间切换留下的改动）就在不知情中丢了。
 *
 * 返回 null 表示不可写回：refinedJson 解析失败，或 index 越界
 * （bundle 题数与编辑页 drafts 数不一致，如 markdown 兜底的空 bundle）。
 */
internal fun replaceDraftQuestionInBundle(
    refinedJson: String,
    index: Int,
    draft: EditableDraft
): String? {
    val bundle: DraftBundle = DraftBundleCodec.decode(refinedJson) ?: return null
    if (index !in bundle.questions.indices) return null
    val questions = bundle.questions.toMutableList()
    questions[index] = draft.toDraftQuestion()
    return DraftBundleCodec.encode(bundle.copy(questions = questions))
}
