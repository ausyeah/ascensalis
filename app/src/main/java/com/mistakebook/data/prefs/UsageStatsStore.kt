package com.mistakebook.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * 一次用量快照。设置页「用量概览」直接渲染它。
 *
 * @param llmCachedTokens 命中提示词缓存的 token 数（DeepSeek 的
 *   `prompt_cache_hit_tokens`）。缓存命中部分单价远低于普通输入，
 *   单独列出来才有意义——和输入混在一起的话，「明明没问多少、
 *   账单却在涨」就解释不清了。
 */
data class UsageSnapshot(
    val llmCalls: Int = 0,
    val llmPromptTokens: Int = 0,
    val llmCompletionTokens: Int = 0,
    val llmCachedTokens: Int = 0,
    val mineruCalls: Int = 0,

    /**
     * MinerU 解析的累计页数。
     *
     * MinerU 免费额度按页算（2000 页/天），「解析几次」说明不了花了多少
     * 额度——一份 50 页的 PDF 和一张截图都只算 1 次。页数才是
     * 「识别」这一侧的具体量（用户反馈「对话和识别都要计算在内」）。
     */
    val mineruPages: Int = 0
)

/**
 * 大模型 / MinerU 用量累计。
 *
 * 为什么用 SharedPreferences 而不是 Room：
 * 用量是**全局单例计数器**，没有按实体查询的需求，
 * 也没有历史明细要留（要留的话就是每次调用插一行，增长无上限）。
 * 几个整数放进 prefs，读一次 O(1)，写一次 O(1)，不值得开表。
 *
 * 重置入口放在用量卡片上——统计口径（比如换 Key 后想从零看）
 * 由用户自己决定。
 */
class UsageStatsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 记一次大模型调用。cachedTokens 为 0 表示服务端没给缓存字段。 */
    fun recordLlm(promptTokens: Int, completionTokens: Int, cachedTokens: Int) {
        prefs.edit {
            putInt(KEY_LLM_CALLS, prefs.getInt(KEY_LLM_CALLS, 0) + 1)
            putInt(KEY_LLM_PROMPT, prefs.getInt(KEY_LLM_PROMPT, 0) + promptTokens.coerceAtLeast(0))
            putInt(
                KEY_LLM_COMPLETION,
                prefs.getInt(KEY_LLM_COMPLETION, 0) + completionTokens.coerceAtLeast(0)
            )
            putInt(KEY_LLM_CACHED, prefs.getInt(KEY_LLM_CACHED, 0) + cachedTokens.coerceAtLeast(0))
        }
    }

    /**
     * 记一次 MinerU 文件解析。
     *
     * @param pages 本次解析的页数（MinerU 计费单位）。服务端没给
     *   `total_pages` 时传 0：次数照记，页数不记。
     */
    fun recordMineru(pages: Int = 0) {
        prefs.edit {
            putInt(KEY_MINERU_CALLS, prefs.getInt(KEY_MINERU_CALLS, 0) + 1)
            if (pages > 0) {
                putInt(KEY_MINERU_PAGES, prefs.getInt(KEY_MINERU_PAGES, 0) + pages)
            }
        }
    }

    fun snapshot(): UsageSnapshot = UsageSnapshot(
        llmCalls = prefs.getInt(KEY_LLM_CALLS, 0),
        llmPromptTokens = prefs.getInt(KEY_LLM_PROMPT, 0),
        llmCompletionTokens = prefs.getInt(KEY_LLM_COMPLETION, 0),
        llmCachedTokens = prefs.getInt(KEY_LLM_CACHED, 0),
        mineruCalls = prefs.getInt(KEY_MINERU_CALLS, 0),
        mineruPages = prefs.getInt(KEY_MINERU_PAGES, 0)
    )

    /** 清零全部计数。换 Key / 换服务商后从零看用量时用。 */
    fun reset() {
        prefs.edit { clear() }
    }

    private companion object {
        const val PREFS_NAME = "usage_stats"
        const val KEY_LLM_CALLS = "llm_calls"
        const val KEY_LLM_PROMPT = "llm_prompt_tokens"
        const val KEY_LLM_COMPLETION = "llm_completion_tokens"
        const val KEY_LLM_CACHED = "llm_cached_tokens"
        const val KEY_MINERU_CALLS = "mineru_calls"
        const val KEY_MINERU_PAGES = "mineru_pages"
    }
}

/** 千分位格式化：12345 -> "12,345"。 */
fun formatCount(n: Int): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val sb = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.toString()
}
