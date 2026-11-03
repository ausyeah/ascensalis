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
    override val id: String = java.util.UUID.randomUUID().toString(),
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
     * 模型 -> keyId 的自动路由表（**只存首选那把**）。
     *
     * 「一键获取所有模型」时逐把 key 探测生成：主 key 看得见的模型全部
     * 路由到主 key，只有附加 key 看得见的才绑到那把 key 上。
     * 用户不填这个表，也不该看到这个表——它是探测结果的落盘。
     *
     * 它就是 [modelKeyChain] 每个模型的链头（探测时同一份数据同时落成两个字段）。
     * 字段类型不能改成 List——见 [modelKeyChain] 里关于存量 JSON 的说明。
     *
     * 路由表指向的 key 被删除时（[resolveKey]），自动回退主 key。
     */
    val modelKeys: Map<String, String> = emptyMap(),

    /**
     * 模型 -> **能调用它的全部 keyId**，按尝试顺序（首选在前）。
     *
     * ## 为什么要有它（[modelKeys] 每个模型只记得一把 key）
     *
     * 中转站一个 URL 下挂着多把 key 时，同一个模型常常好几把都调得动
     * （用户诉求：「多个 key 都勾选了同一个模型」）。而 [modelKeys] 是
     * `model -> keyId`，**一个模型只存一把**——于是「这把 key 额度用完
     * 或被吊销」就只能是整次请求失败，明明旁边还有一把能调的 key 闲着。
     *
     * 这张表把探测时看得见该模型的**每一把 key** 按顺序记下来，
     * 请求侧据此做失败转移：鉴权失败（401/403）或额度受限（429/503）时
     * 自动换下一把 key 重发同一个请求，用户不用回设置页改配置。
     *
     * ## 为什么是新增字段，而不是把 [modelKeys] 的值改成 List
     *
     * [SettingsStore] 读配置是 `runCatching { decode }.getOrDefault(emptyList())`
     * ——**坏数据按空列表处理**。把值的类型从 String 改成 List<String>，
     * 老用户存的那份 JSON 会整体解析失败，整套接入配置（URL / Key / 模型）
     * 静默变成空。所以只能加字段，老配置没有它时行为与只有一把 key 时一致。
     *
     * ## 为什么首选也写在链里
     *
     * 链是**唯一来源**：[resolveKey] 取链头，[keyCandidates] 取整条链。
     * 两张表各记一份首选，迟早对不上。探测时由同一份数据同时生成两者
     * （见 SettingsViewModel.fetchModels），不变量是
     * `modelKeys[m] == modelKeyChain[m].first()`。
     *
     * 留空表示「没探测过 / 老配置」，此时按 [modelKeys] + 主 key 兜底。
     */
    val modelKeyChain: Map<String, List<String>> = emptyMap(),

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
     * 这个模型下**所有能用的 key**，按尝试顺序（首选在前）。
     *
     * 取代早先的 `keysServing`：那个名字只答「有哪几把」，答不上「先试哪把」，
     * 而失败转移要的正是顺序。候选来自探测（[modelKeyChain]）——
     * 探测时看得见这个模型的每一把 key 都在里面，顺序即优先级；
     * 没探测过（老配置）就退回「路由表绑定的那把 + 主 key」。
     *
     * 查表**大小写不敏感**：/models 回来的模型名与用户手填/芯片上显示的名字
     * 偶尔大小写不一致（DeepSeek-Chat vs deepseek-chat）。
     *
     * 空模型名无从判断，返回空列表。
     */
    fun keyCandidates(model: String): List<LlmKeyEntry> {
        val target = model.trim()
        if (target.isBlank()) return emptyList()
        chainFor(target)?.let { ids ->
            val chained = ids.mapNotNull { entryFor(it) }
                .filter { it.isUsable() }
                // 同一把 key 被用户粘了两次是常事，去重免得白试一遍
                .distinctBy { it.key }
            if (chained.isNotEmpty()) return chained
        }
        val bound = bindingFor(target)
        val preferred = buildList {
            if (bound != null) entryFor(bound)?.let { add(it) }
            addAll(allKeys().filter { it.id == MAIN_KEY_ID })
        }.filter { it.isUsable() }
        // 绑定的 key 已删、主 key 也空：退回第一把能用的附加 key，
        // 与 resolveKey 的老口径一致（见 LlmMultiKeyTest 的对应用例）
        return preferred.ifEmpty { allKeys().filter { it.isUsable() } }
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

    /** 候选链查找，与 [bindingFor] 同样大小写不敏感。 */
    private fun chainFor(model: String): List<String>? =
        modelKeyChain.entries.firstOrNull {
            it.key.trim().equals(model.trim(), ignoreCase = true)
        }?.value?.takeIf { it.isNotEmpty() }

    /** keyId -> key 条目。主 key 用固定 id 现算（见 [allKeys]），附加 key 查配置。 */
    private fun entryFor(id: String): LlmKeyEntry? = when (id) {
        MAIN_KEY_ID -> allKeys().firstOrNull { it.id == MAIN_KEY_ID }
        else -> extraKeys.firstOrNull { it.id == id }
    }

    /**
     * 按模型名解析出**首选** key。
     *
     * 就是 [keyCandidates] 的链头——链是唯一来源，不再另写一套挑选规则，
     * 免得「首选」和「失败转移时第二把」对不上。
     *
     * 解析不出来就返回空串——调用方要按「未配置」处理。
     */
    fun resolveKey(model: String): String =
        keyCandidates(model).firstOrNull()?.key.orEmpty()

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
