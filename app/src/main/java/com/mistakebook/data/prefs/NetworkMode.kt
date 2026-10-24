package com.mistakebook.data.prefs

/**
 * App 的网络出口模式。
 *
 * 用户诉求：「mineru 走代理就会说没网，把它改成直连吧，在设置里面加一个
 * 网络代理怎么走的设置吧，一个是系统代理，一个是直连一个是自动」。
 */
enum class NetworkMode {
    /** 跟随系统代理（Wi-Fi 代理 / 系统 VPN 代填的 HTTP 代理）。 */
    SYSTEM,

    /** 全部直连，忽略系统代理。 */
    DIRECT,

    /**
     * 自动：国内服务（MinerU）直连，其余跟随系统代理。
     *
     * 默认值。MinerU 的域名在国内，走代理反而常常连不上；
     * 而大模型中转站多数时候反而需要代理。按域名分流两头都顾上。
     */
    AUTO;

    companion object {
        fun fromKey(key: String?): NetworkMode = when (key?.lowercase()) {
            "system" -> SYSTEM
            "direct" -> DIRECT
            // 缺省（新装 / 旧版本升级）走自动：MinerU 直连是修
            // 「代理下一打开识别就报没网」的默认解
            else -> AUTO
        }
    }
}
