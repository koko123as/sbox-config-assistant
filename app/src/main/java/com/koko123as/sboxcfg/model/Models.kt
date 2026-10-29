package com.koko123as.sboxcfg.model

import java.util.UUID

/**
 * 节点：解析时直接产出完整 sing-box outbound dict（存为 rawJson），
 * 后续只做 tag 订正，不做二次转换（Karing 的 raw 模式）。
 */
data class NodeEntry(
    val tag: String,
    val protocol: String, // vless / vmess / shadowsocks / trojan / hysteria / hysteria2 / tuic / wireguard / socks / http / ssh / anytls
    val server: String,
    val port: Int,
    val rawJson: String, // 完整 outbound JSON
    val detourTag: String? = null // ⑦ 链式代理：前置代理的 tag（sing-box outbound.detour）
)

/** 一条规则组的匹配条件（条件清单） */
data class RuleConditions(
    val domainSuffix: List<String> = emptyList(),
    val domain: List<String> = emptyList(),
    val domainKeyword: List<String> = emptyList(),
    val domainRegex: List<String> = emptyList(),
    val ipCidr: List<String> = emptyList(),
    val ports: List<String> = emptyList(), // 允许 "80" 或 "8000-8100"
    val protocols: List<String> = emptyList(), // tcp / udp
    val packageNames: List<String> = emptyList(),
    val geosite: List<String> = emptyList(), // 存 code，如 "google" / "geolocation-!cn"
    val geoip: List<String> = emptyList(),
    val ruleSetUrls: List<String> = emptyList() // https .srs/.json
) {
    fun isEmpty(): Boolean = domainSuffix.isEmpty() && domain.isEmpty() &&
        domainKeyword.isEmpty() && domainRegex.isEmpty() && ipCidr.isEmpty() &&
        ports.isEmpty() && protocols.isEmpty() && packageNames.isEmpty() &&
        geosite.isEmpty() && geoip.isEmpty() && ruleSetUrls.isEmpty()

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (domainSuffix.isNotEmpty()) parts.add("域名后缀×${domainSuffix.size}")
        if (domain.isNotEmpty()) parts.add("完整域名×${domain.size}")
        if (domainKeyword.isNotEmpty()) parts.add("关键字×${domainKeyword.size}")
        if (domainRegex.isNotEmpty()) parts.add("正则×${domainRegex.size}")
        if (ipCidr.isNotEmpty()) parts.add("IP段×${ipCidr.size}")
        if (ports.isNotEmpty()) parts.add("端口×${ports.size}")
        if (protocols.isNotEmpty()) parts.add("协议:${protocols.joinToString("/")}")
        if (packageNames.isNotEmpty()) parts.add("应用×${packageNames.size}")
        if (geosite.isNotEmpty()) parts.add("geosite×${geosite.size}")
        if (geoip.isNotEmpty()) parts.add("geoip×${geoip.size}")
        if (ruleSetUrls.isNotEmpty()) parts.add("规则集×${ruleSetUrls.size}")
        return if (parts.isEmpty()) "（无条件）" else parts.joinToString("、")
    }
}

/** 分流规则组：生成时包成一条 sing-box logical 规则 */
data class RuleGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val mode: String = "or", // and / or
    val outboundTag: String = "默认",
    val conditions: RuleConditions = RuleConditions()
)

/**
 * ⑥ DNS 服务器（对标 Karing SettingConfigItemDNS 的四组模型，诚实删减：
 * sing-box 官方 schema 无全局 TTL 字段 → 不做 TTL；client_subnet 走 singbox 口味时
 * Karing 主动去掉 → v2 不做 EDNS 开关，留 v3）。
 */
data class DnsServer(
    val tag: String,
    val type: String = "https", // https / tls / tcp / udp / local
    val address: String = "",
    val detour: String = "" // 空=不走代理；如 "默认"
)

/** DNS 配置：四组服务器＋解析模式三态。默认与 base_template.json 一致。 */
data class DnsConfig(
    val resolver: List<DnsServer> = listOf(DnsServer("local", "local")),
    val outbound: List<DnsServer> = listOf(DnsServer("dns_local", "local")),
    val direct: List<DnsServer> = listOf(DnsServer("ali", "https", "223.5.5.5")),
    val proxy: List<DnsServer> = listOf(DnsServer("google", "https", "8.8.8.8", "默认")),
    val resolveMode: String = "proxy", // fakeip / proxy / direct
    val testDomain: String = "gstatic.com" // 展示/预留，v2 不做测速
) {
    companion object {
        fun default() = DnsConfig()
    }

    /** 全部服务器（去重 tag） */
    fun allServers(): List<DnsServer> =
        (resolver + outbound + direct + proxy).distinctBy { it.tag }

    /** DNS 联动规则的 server：Karing getDnsOutboundFromOutbound 映射 */
    fun linkedServer(outboundTag: String): String = when {
        resolveMode == "fakeip" -> "dns_fakeip"
        outboundTag == "直连" -> direct.firstOrNull()?.tag ?: "ali"
        else -> proxy.firstOrNull()?.tag ?: "google"
    }

    fun finalTag(): String = proxy.firstOrNull()?.tag ?: "google"
}

/**
 * ② 内置规则组开关：v3.0 起规则与模板解耦，ruleJson 存完整 route rule JSON；
 * 老数据该字段为空，启动时按 index 从原模板回填。
 * label 首次运行时按 matcher＋outbound 生成中文摘要。
 */
data class BuiltInRule(
    val index: Int,
    val label: String,
    val enabled: Boolean = true,
    val ruleJson: String = ""
)

/**
 * v3.0 规则模版：内置两个（minimal=极简分流，original=原版完整），
 * 用户可把当前 builtInRules 存为自定义模版。
 */
data class RuleTemplate(
    val id: String,
    val name: String,
    val finalOutbound: String,
    val rules: List<BuiltInRule>,
    val isBuiltIn: Boolean = false
)

data class AppConfigData(
    val nodes: List<NodeEntry> = emptyList(),
    val ruleGroups: List<RuleGroup> = emptyList(),
    val dns: DnsConfig = DnsConfig.default(),
    val builtInRules: List<BuiltInRule> = emptyList(), // 空=未初始化，用全部启用
    val customTemplates: List<RuleTemplate> = emptyList(), // v3.0 用户自定义模版
    val activeTemplateId: String = "minimal" // v3.0 当前激活的模版
)
