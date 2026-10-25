package com.mistakebook.pipeline

import com.mistakebook.data.prefs.RecognitionMode

/**
 * 一次识别的执行路线。
 *
 * 由 [RecognitionPlan.decide] 依据设置与当前配置纯函数式地产出，
 * [RecognitionEngine] 只负责执行，不自己拍脑袋——分支集中一处才可单测。
 */
sealed interface RecognitionPlan {

    /** 只用 MinerU：识别失败（含没配 Key）按失败处理，不回落。 */
    object Mineru : RecognitionPlan

    /** 先 MinerU，**失败（含没配 Key / 抽不出字）时自动落大模型直连**。 */
    object MineruThenLlm : RecognitionPlan

    /** 跳过 MinerU：可识图模型直接看原图、输出结构化题目。 */
    object LlmDirect : RecognitionPlan

    /**
     * 两条路都走不通，[reason] 是把两条路都说清楚的用户可读原因。
     * 不抛异常：这是「配置不满足」而不是「执行出错」，交给调用方
     * 以任务失败的形式落库展示（与其它失败同一套 UI）。
     */
    data class Unavailable(val reason: String) : RecognitionPlan

    companion object {

        /**
         * 识别路线决策（**纯函数，可 JVM 单测**）。
         *
         * 矩阵（mode × mineruConfigured × supportsVision）：
         *
         * | mode | mineru | vision | 路线 |
         * |---|---|---|---|
         * | MINERU | 任意 | 任意 | Mineru（没 Key 由执行层按老行为报错） |
         * | LLM | 任意 | true | LlmDirect |
         * | LLM | 任意 | false | Unavailable（请换识图模型） |
         * | AUTO | true | 任意 | MineruThenLlm |
         * | AUTO | false | true | LlmDirect |
         * | AUTO | false | false | Unavailable（两条路都说明） |
         */
        fun decide(
            mode: RecognitionMode,
            mineruConfigured: Boolean,
            supportsVision: Boolean,
            /** 仅用于把「当前模型叫什么」写进错误原因。 */
            modelName: String = ""
        ): RecognitionPlan = when (mode) {
            RecognitionMode.MINERU -> Mineru

            RecognitionMode.LLM -> if (supportsVision) {
                LlmDirect
            } else {
                Unavailable(
                    "设置里选择了「大模型识别」题目，但当前模型" +
                        (if (modelName.isNotBlank()) "（$modelName）" else "") +
                        "不支持识图。请到设置里切换到识图模型，" +
                        "或把识别方式改回「自动」/「仅 MinerU」。"
                )
            }

            RecognitionMode.AUTO -> when {
                mineruConfigured -> MineruThenLlm
                supportsVision -> LlmDirect
                else -> Unavailable(
                    "自动识别需要 MinerU 或识图模型至少其一：" +
                        "MinerU 尚未配置 Key，当前模型" +
                        (if (modelName.isNotBlank()) "（$modelName）" else "") +
                        "也不支持识图。请到设置里配置 MinerU Key，" +
                        "或切换到识图模型。"
                )
            }
        }
    }
}
