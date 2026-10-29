package com.mistakebook.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内的「后台生成」状态总线。
 *
 * ## 为什么需要它
 *
 * 流式生成被搬进 [ChatGenerationService]（前台服务）之后，界面与生成过程
 * 就分处两个生命周期：界面可能已被销毁（用户退出），而生成还在跑。
 * 二者要交换的东西只有一样——**这次生成的实时进度**，所以用进程内单例
 * 的 StateFlow 传递最省事：
 *
 * - Service 往里写增量内容与终态；
 * - ViewModel 订阅它，把增量内容盖到那条助手消息上（打字机效果），
 *   并在终态时更新「可重试 / 被截断 / 输入 token 数」等状态。
 *
 * 为什么不用 Binder 回调：Binder 的连接随界面一起死，界面重建后
 * 就再也收不到增量了；而单例 StateFlow 在进程内一直活着——
 * **用户退出再回来，只要进程还在，订阅立刻拿到最新快照**。
 *
 * 最终内容始终以数据库为准（Service 负责落库），总线只管「过程中的预览」。
 */
object ChatGenerationBus {

    /**
     * 一次生成的快照。
     *
     * @param assistantId 正在生成的那条助手消息 id
     * @param content 已收到的全部内容（含思考标记）
     * @param finished 是否已结束（成功 / 失败 / 取消）
     * @param promptTokens 输入 token 数（终态才有意义）
     * @param truncated 是否因 max_tokens 被截断
     * @param failed 是否失败（可重试）
     * @param errorText 失败原因
     */
    data class Snapshot(
        val assistantId: Long,
        val content: String = "",
        val finished: Boolean = false,
        val promptTokens: Int = 0,
        val truncated: Boolean = false,
        val failed: Boolean = false,
        val errorText: String? = null,

        /**
         * 首个 token 之前的**阶段提示**（如「正在联网搜索…」）。
         * 非空时界面在生成中气泡里显示这行字；正文开始流动后置空。
         * 用户反馈过「点了没反应」系列——联网搜索要几秒到十几秒，
         * 没有这行字就是又一例「不知道在干嘛」。
         */
        val stage: String? = null,

        /**
         * 这个快照属于**哪个会话**。
         *
         * ## 为什么必须有它
         *
         * 总线是**进程内单例**。服务在后台给会话 A 生成时，会话 B 的界面
         * 也订阅着同一个 Flow。不带会话 id 的话，B 会把 A 的快照当成自己的：
         * 表现是「在 B 里点发送，界面却显示 A 正在生成」，甚至 B 的界面
         * 被 A 的终态快照复位。带上 id 之后才算真正隔离。
         *
         * 0 表示「不知道属于谁」，此时界面按老逻辑处理（不参与隔离）。
         */
        val sessionId: Long = 0L
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    fun publish(value: Snapshot) {
        _snapshot.value = value
    }
}
