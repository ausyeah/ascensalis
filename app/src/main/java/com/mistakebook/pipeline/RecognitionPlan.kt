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

    /** 用**当前默认/会话大模型**直连看图，输出结构化题目。 */
    object LlmDirect : RecognitionPlan

    /** 用**用户单独配置的识图模型**直连看图。 */
    object VisionDirect : RecognitionPlan

    /**
     * 自动回落链（[RecognitionMode.AUTO]）。
     *
     * 依次尝试 [routes] 里的每一条，**中途失败不中断**；
     * 全部失败才由执行层报错。
     */
    data class Chain(val routes: List<Route>) : RecognitionPlan

    /**
     * 两条路都走不通，[reason] 是把两条路都说清楚的用户可读原因。
     * 不抛异常：这是「配置不满足」而不是「执行出错」，交给调用方
     * 以任务失败的形式落库展示（与其它失败同一套 UI）。
     */
    data class Unavailable(val reason: String) : RecognitionPlan

    /** 链里的一条具体路线。 */
    enum class Route {
        /** MinerU 文档解析（逐字抽文字 + LaTeX）。 */
        MINERU,

        /** 当前默认大模型直接看原图。 */
        CURRENT_LLM,

        /** 用户配置的识图模型直接看原图。 */
        VISION_LLM
    }

    companion object {

        /**
         * 识别路线决策（**纯函数，可 JVM 单测**）。
         *
         * 四个模式的语义（v0.0.49 补齐「我配置的识图模型」与「当前会话模型」）：
         *
         * | mode | 条件 | 路线 |
         * |---|---|---|
         * | MINERU | 任意 | Mineru（没 Key 由执行层按老行为报错） |
         * | CURRENT | 当前模型识图 | LlmDirect |
         * | CURRENT | 当前模型不识图 | Unavailable |
         * | VISION | 配了识图模型 | VisionDirect |
         * | VISION | 没配识图模型 | Unavailable |
         * | AUTO | 见下 | Chain |
         *
         * AUTO 的链条（用户诉求，v0.0.55 反馈收敛）：
         * 「MinerU 加超时，超时就转大模型；大模型识别一次就够了，不转给
         *   另一个大模型再来一次；当前模型不能识图，就让配置好的识图模型
         *   跑全程。」
         *
         * 落到链上：① MINERU（配了才进链，执行层带超时，超时/失败即往下）
         * → ② 大模型**只出一条**：当前模型能识图 → CURRENT_LLM，否则 →
         * VISION_LLM（配了识图模型才进链）。不再把「当前模型」和「识图模型」
         * 串成两条连续重试。
         * 链上 MinerU 产出的文字，后面仍由大模型整理。
         */
        fun decide(
            mode: RecognitionMode,
            mineruConfigured: Boolean,
            supportsVision: Boolean,
            /** 用户单独配置的识图模型是否可用。 */
            visionConfigured: Boolean = false,
            /** 仅用于把「当前模型叫什么」写进错误原因。 */
            modelName: String = ""
        ): RecognitionPlan = when (mode) {
            RecognitionMode.MINERU -> Mineru

            RecognitionMode.CURRENT -> if (supportsVision) {
                LlmDirect
            } else {
                Unavailable(
                    "设置里选择了「当前模型识别」题目，但当前模型" +
                        (if (modelName.isNotBlank()) "（$modelName）" else "") +
                        "不支持识图。请到设置里切换到识图模型，" +
                        "或把识别方式改成「识图模型」/「自动」。"
                )
            }

            RecognitionMode.VISION -> if (visionConfigured) {
                VisionDirect
            } else {
                Unavailable(
                    "设置里选择了「识图模型」识别题目，但还没有配置可用的识图模型。" +
                        "请到设置 → 大模型 → 识图模型里填写地址、Key 与模型名，" +
                        "或把识别方式改成「自动」。"
                )
            }

            RecognitionMode.AUTO -> {
                val routes = buildList {
                    if (mineruConfigured) add(Route.MINERU)
                    // 大模型只出一条：当前能识图用当前，否则用识图模型；不二者连试
                    // （用户诉求：大模型识别一次就够了，不转给另一个大模型再来一次）
                    when {
                        supportsVision -> add(Route.CURRENT_LLM)
                        visionConfigured -> add(Route.VISION_LLM)
                    }
                }
                if (routes.isEmpty()) {
                    Unavailable(
                        "自动识别需要 MinerU、识图模型、当前大模型识图能力至少其一：" +
                            "MinerU 尚未配置 Key，当前模型" +
                            (if (modelName.isNotBlank()) "（$modelName）" else "") +
                            "也不支持识图，还没有配置识图模型。" +
                            "请到设置里配置 MinerU Key 或识图模型。"
                    )
                } else {
                    Chain(routes)
                }
            }
        }
    }
}
