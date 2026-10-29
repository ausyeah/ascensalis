package com.mistakebook.data.prefs

import kotlinx.serialization.Serializable

/**
 * 一个附加的 API Key。
 *
 * ## 为什么需要
 *
 * 用户诉求：「不能给一个 URL 配置多个 key，从而实现一个 provider 供应多种
 * 模型，适配中转站实用」。
 *
 * 中转站（one-api / new-api 这类）的典型形态是：**一个 baseUrl，
 * 多个 key，每个 key 的可用模型和额度都不一样**。
 *
 * ## 为什么不再有「该 Key 负责的模型」
 *
 * 最早的版本让每把 key 手填自己管哪些模型。实测下来这个输入框是多余的：
 * 设置页底部本来就会「一键获取所有 Key 可见的模型」并合并成池，
 * 探测时自然知道每个模型是哪把 key 看到的——路由表（[LlmProfile.modelKeys]）
 * 在拉列表时就自动生成，用户再手填一遍只会两头不一致。
 */
@Serializable
data class LlmKeyEntry(
    val id: String = java.util.UUID.randomUUID().toString(),

    /** 这个 key 本身。 */
    val key: String = ""
) {
    /** 是否可以真正用于请求。 */
    fun isUsable(): Boolean = key.isNotBlank()
}

/**
 * 一套可保存、可随时切换的大模型接入配置（OpenAI 兼容）。
 *
 * apiKey 只写 EncryptedSharedPreferences，不进 DataStore、不进日志。
 */
@Serializable
data class LlmProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val note: String = "",

    /**
     * 这个 provider **勾选后供应的模型列表**（从 /models 拉来后勾选）。
     *
     * 一个中转站常常挂着几十个模型，用户想在 App 里直接切着用。
     * 只有勾进这里的模型（外加主模型）会出现在对话页的模型选项里。
     *
     * 留空表示「只有主模型」，行为与旧版完全一致。
     */
    val models: List<String> = emptyList(),

    /**
     * 附加的 key 池（同一个 baseUrl 下的其他 key）。
     *
     * 存量数据反序列化时没有这个字段，必须给默认值 `emptyList()`，
     * 否则老用户的配置直接崩掉。
     */
    val extraKeys: List<LlmKeyEntry> = emptyList(),

    /**
     * 模型 -> keyId 的自动路由表。
     *
     * 「一键获取所有模型」时逐把 key 探测生成：主 key 看得见的模型全部
     * 路由到主 key，只有附加 key 看得见的才绑到那把 key 上。
     * 用户不填这个表，也不该看到这个表——它是探测结果的落盘。
     *
     * 路由表指向的 key 被删除时（[resolveKey]），自动回退主 key。
     */
    val modelKeys: Map<String, String> = emptyMap(),

    /**
     * 这个模型**能不能直接看图**。
     *
     * ## 为什么需要它
     *
     * 用户接的大模型不一定是多模态的（DeepSeek-chat、Qwen-plus 这类都看不见图）。
     * 而本 App 已经接了 MinerU——它能把图片里的文字、公式逐字抽出来。
     * 于是「给不识图的模型发图」就有了一条可行路径：
     * **先让 MinerU 读图，再把读出来的文字喂给模型。**
     *
     * ## 默认值为什么是 true
     *
     * 存量的接入配置里没有这个字段，反序列化时若不给默认值会直接崩，
     * 用户的 Key、URL、模型名全部丢失。给 `true` 是**保持原行为**：
     * 旧配置照旧把图片当多模态图片发出去，不会因为升级就突然改走 OCR。
     *
     * 用户可以在设置里按模型单独关掉（不识图的模型关掉即可）。
     */
    val supportsVision: Boolean = true
) : KeyedProfile {
    fun displayName(): String = name.ifBlank { model.ifBlank { "未命名配置" } }

    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /**
     * 所有可用的 key：主 key 在前，附加 key 在后。
     *
     * 主 key 排第一是刻意的——它承担「兜底」角色，
     * 路由表没绑定的模型都走它。
     *
     * 主 key 用**固定 id**（而不是随机 UUID）：它是每次调用现算出来的
     * 临时对象，id 若随机，「这个模型来自哪个 key」就没法稳定回溯，
     * 拉列表时的来源标注与自动绑定都对不上号。
     */
    fun allKeys(): List<LlmKeyEntry> =
        buildList {
            if (apiKey.isNotBlank()) {
                add(LlmKeyEntry(id = MAIN_KEY_ID, key = apiKey))
            }
            addAll(extraKeys.filter { it.key.isNotBlank() })
        }

    /**
     * 按模型名找出**所有**能调用它的 key。
     *
     * 口径：路由表绑定的那把 + 主 key（主 key 是兜底，
     * 任何模型都至少可以试着走它）。空模型名无从判断，返回空。
     */
    fun keysServing(model: String): List<LlmKeyEntry> {
        val target = model.trim()
        if (target.isBlank()) return emptyList()
        val bound = bindingFor(target)
        return allKeys().filter { it.isUsable() && (it.id == bound || it.id == MAIN_KEY_ID) }
    }

    companion object {
        /** 主 key 的固定标识（见 [allKeys] 的注释）。 */
        const val MAIN_KEY_ID = "__main__"
    }

    /**
     * 路由表查找：**大小写不敏感**。
     *
     * /models 返回的模型名与用户手填/芯片上显示的名字偶尔大小写不一致
     * （DeepSeek-Chat vs deepseek-chat），精确查会莫名丢掉绑定、
     * 落回主 key——而主 key 恰恰调不动这个模型。
     */
    private fun bindingFor(model: String): String? =
        modelKeys.entries.firstOrNull { it.key.trim().equals(model.trim(), ignoreCase = true) }?.value

    /**
     * 按模型名解析出该用的 key。
     *
     * 规则：
     * 1. 路由表（[modelKeys]）绑定的 key 优先——那是拉列表时探测出的
     *    「这把 key 真能调这个模型」的结论；
     * 2. 绑定的 key 已被删除（配置里找不到）时，退回主 key；
     * 3. 主 key 为空时，退回第一个可用的附加 key。
     *
     * 解析不出来就返回空串——调用方要按「未配置」处理。
     */
    fun resolveKey(model: String): String {
        val target = model.trim()
        bindingFor(target)?.let { id ->
            val entry = if (id == MAIN_KEY_ID) {
                allKeys().firstOrNull { it.id == MAIN_KEY_ID }
            } else {
                extraKeys.firstOrNull { it.id == id }
            }
            if (entry != null && entry.isUsable()) return entry.key
        }
        if (apiKey.isNotBlank()) return apiKey
        return extraKeys.firstOrNull { it.isUsable() }?.key.orEmpty()
    }

    /**
     * 这个配置下**对话页可见**的模型名（主模型 + 勾选的模型）。
     *
     * 没勾选的模型不会出现在这里——用户明确要求过：
     * 「没有勾选的就不需要出现在对话的模型选项里面了」。
     */
    fun availableModels(): List<String> {
        val out = LinkedHashSet<String>()
        if (model.isNotBlank()) out += model.trim()
        models.filter { it.isNotBlank() }.forEach { out += it.trim() }
        return out.toList()
    }

    /**
     * 保存前规范化：只填了根域名（如 https://api.example.com）时自动补 /v1，
     * 已经带路径的（如 https://host/api）保持原样。
     */
    override fun normalized(): LlmProfile {
        val raw = baseUrl.trim().trimEnd('/')
        val hasPath = raw.removePrefix("https://").removePrefix("http://").contains('/')
        return copy(baseUrl = if (hasPath) raw else "$raw/v1")
    }

    fun chatCompletionsUrl(): String = baseUrl.trimEnd('/') + "/chat/completions"

    fun modelsUrl(): String = baseUrl.trimEnd('/') + "/models"
}
