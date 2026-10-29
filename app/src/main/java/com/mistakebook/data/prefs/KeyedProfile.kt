package com.mistakebook.data.prefs

/**
 * 多套「Provider 配置」（LLM 接入 / 生图渠道 / 识图模型）的公共形状。
 *
 * 三套配置的存储逻辑完全同构（列表存取 / upsert / 删除 / 激活切换），
 * 差异只在序列化类型与存储键。此前每套 5 个方法逐字复制三份（约 180 行），
 * 这类复制的必然结局是「改一处漏两处」。v0.0.63 起由
 * [SettingsStore] 的泛型方法统一处理，本接口只约束最小组：
 * 唯一标识与存盘前的规范化。
 */
interface KeyedProfile {

    /** 唯一标识（UUID）。upsert 以它判「新增还是覆盖」。 */
    val id: String

    /**
     * 规范化（trim / 补默认路径等），存盘前统一调用。
     * 实现类用协变返回具体类型，调用方无需强转。
     */
    fun normalized(): KeyedProfile
}
