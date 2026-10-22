package com.mistakebook.wordbook.algo

/**
 * 单词书的业务规则常量与纯函数。
 *
 * 单独抽出来而不是散在 [com.mistakebook.wordbook.ui.WordbookViewModel] 里：
 * 毕业条件这类规则要在 UI 文案（「连对 x/2」）和打分逻辑
 * （`reps >= 几`）两处保持一致，写死在两个地方必有一处忘记改。
 * 放成纯函数后还能被 JVM 单测直接覆盖——UI 状态依赖 AppContainer，
 * 单测碰不到，但规则本身必须可信。
 */
object WordbookRules {

    /**
     * 错词本毕业所需连对次数。
     *
     * 取 2 而不是 1：答对 1 次就毕业太松——刚看完解析带着印象选对，
     * 并没有真记住，过两天又忘，错词本失去「防遗忘」的意义。
     * 3 次又太严：一个词要连续对 3 次（约 3 轮复习）才能出库，
     * 后期错词本越积越多，用户看着挫败。2 次是「真会了」与
     * 「不留包袱」的折中。
     */
    const val GRADUATION_REPS = 2

    /** 连对 [reps] 次后是否可以从错词本毕业（保留曾错标记）。 */
    fun shouldGraduateFromWrongBook(reps: Int): Boolean = reps >= GRADUATION_REPS
}
