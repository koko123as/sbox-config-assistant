package com.koko123as.sboxcfg.store

import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.model.BuiltInRule
import com.koko123as.sboxcfg.model.RuleTemplate
import com.koko123as.sboxcfg.model.DnsConfig
import com.koko123as.sboxcfg.model.DnsServer
import com.koko123as.sboxcfg.model.NodeEntry
import com.koko123as.sboxcfg.model.RuleConditions
import com.koko123as.sboxcfg.model.RuleGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 配置落盘：filesDir/app_config.json。每次变更后立即保存（无保存按钮，
 * 退出页面即落盘）；编辑器里改的是副本，校验失败不污染线上配置。
 * v2：加 detourTag / dns / builtInRules；删 perApp（v1 数据兼容：读到旧字段直接忽略）。
 */
class ConfigStore(private val dir: File) {

    private val file: File get() = File(dir, "app_config.json")

    fun load(): AppConfigData {
        return try {
            val f = file
            if (!f.exists()) return AppConfigData()
            fromJson(JSONObject(f.readText()))
        } catch (_: Exception) {
            AppConfigData()
        }
    }

    fun save(data: AppConfigData) {
        try {
            file.writeText(toJson(data).toString())
        } catch (_: Exception) {
        }
    }

    // ---------- JSON 互转 ----------

    private fun strList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }

    private fun toJson(data: AppConfigData): JSONObject {
        val root = JSONObject()
        val nodes = JSONArray()
        for (n in data.nodes) {
            val o = JSONObject()
                .put("tag", n.tag)
                .put("protocol", n.protocol)
                .put("server", n.server)
                .put("port", n.port)
                .put("raw", n.rawJson)
            if (n.detourTag != null) o.put("detour", n.detourTag)
            nodes.put(o)
        }
        root.put("nodes", nodes)
        val groups = JSONArray()
        for (g in data.ruleGroups) {
            val c = g.conditions
            groups.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("enabled", g.enabled)
                    .put("mode", g.mode)
                    .put("outbound", g.outboundTag)
                    .put(
                        "conditions", JSONObject()
                            .put("domainSuffix", JSONArray(c.domainSuffix))
                            .put("domain", JSONArray(c.domain))
                            .put("domainKeyword", JSONArray(c.domainKeyword))
                            .put("domainRegex", JSONArray(c.domainRegex))
                            .put("ipCidr", JSONArray(c.ipCidr))
                            .put("ports", JSONArray(c.ports))
                            .put("protocols", JSONArray(c.protocols))
                            .put("packageNames", JSONArray(c.packageNames))
                            .put("geosite", JSONArray(c.geosite))
                            .put("geoip", JSONArray(c.geoip))
                            .put("ruleSetUrls", JSONArray(c.ruleSetUrls))
                    )
            )
        }
        root.put("ruleGroups", groups)
        // DNS
        fun dnsList(list: List<DnsServer>): JSONArray {
            val a = JSONArray()
            for (s in list) {
                a.put(
                    JSONObject()
                        .put("tag", s.tag)
                        .put("type", s.type)
                        .put("address", s.address)
                        .put("detour", s.detour)
                )
            }
            return a
        }
        root.put(
            "dns", JSONObject()
                .put("resolver", dnsList(data.dns.resolver))
                .put("outbound", dnsList(data.dns.outbound))
                .put("direct", dnsList(data.dns.direct))
                .put("proxy", dnsList(data.dns.proxy))
                .put("resolveMode", data.dns.resolveMode)
                .put("testDomain", data.dns.testDomain)
        )
        // 内置规则开关（v3.0：加 ruleJson；模版系统）
        val bir = JSONArray()
        for (b in data.builtInRules) {
            bir.put(
                JSONObject()
                    .put("index", b.index)
                    .put("label", b.label)
                    .put("enabled", b.enabled)
                    .put("ruleJson", b.ruleJson)
            )
        }
        root.put("builtInRules", bir)
        root.put("activeTemplateId", data.activeTemplateId)
        // v3.0 自定义模版
        val cts = JSONArray()
        for (t in data.customTemplates) {
            val trules = JSONArray()
            for (b in t.rules) {
                trules.put(
                    JSONObject()
                        .put("index", b.index)
                        .put("label", b.label)
                        .put("enabled", b.enabled)
                        .put("ruleJson", b.ruleJson)
                )
            }
            cts.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("finalOutbound", t.finalOutbound)
                    .put("isBuiltIn", t.isBuiltIn)
                    .put("rules", trules)
            )
        }
        root.put("customTemplates", cts)
        return root
    }

    private fun dnsServerList(a: JSONArray?): List<DnsServer> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            DnsServer(
                tag = o.optString("tag"),
                type = o.optString("type").ifEmpty { "https" },
                address = o.optString("address"),
                detour = o.optString("detour")
            )
        }.filter { it.tag.isNotEmpty() }
    }

    private fun fromJson(root: JSONObject): AppConfigData {
        val nodes = mutableListOf<NodeEntry>()
        val na = root.optJSONArray("nodes")
        if (na != null) {
            for (i in 0 until na.length()) {
                val o = na.getJSONObject(i)
                nodes.add(
                    NodeEntry(
                        tag = o.optString("tag"),
                        protocol = o.optString("protocol"),
                        server = o.optString("server"),
                        port = o.optInt("port"),
                        rawJson = o.optString("raw"),
                        detourTag = o.optString("detour").ifEmpty { null }
                    )
                )
            }
        }
        val groups = mutableListOf<RuleGroup>()
        val ga = root.optJSONArray("ruleGroups")
        if (ga != null) {
            for (i in 0 until ga.length()) {
                val o = ga.getJSONObject(i)
                val c = o.optJSONObject("conditions") ?: JSONObject()
                groups.add(
                    RuleGroup(
                        id = o.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
                        name = o.optString("name"),
                        enabled = o.optBoolean("enabled", true),
                        mode = o.optString("mode").ifEmpty { "or" },
                        outboundTag = o.optString("outbound").ifEmpty { "默认" },
                        conditions = RuleConditions(
                            domainSuffix = strList(c.optJSONArray("domainSuffix")),
                            domain = strList(c.optJSONArray("domain")),
                            domainKeyword = strList(c.optJSONArray("domainKeyword")),
                            domainRegex = strList(c.optJSONArray("domainRegex")),
                            ipCidr = strList(c.optJSONArray("ipCidr")),
                            ports = strList(c.optJSONArray("ports")),
                            protocols = strList(c.optJSONArray("protocols")),
                            packageNames = strList(c.optJSONArray("packageNames")),
                            geosite = strList(c.optJSONArray("geosite")),
                            geoip = strList(c.optJSONArray("geoip")),
                            ruleSetUrls = strList(c.optJSONArray("ruleSetUrls"))
                        )
                    )
                )
            }
        }
        val dj = root.optJSONObject("dns")
        val dns = if (dj == null) {
            DnsConfig.default()
        } else {
            DnsConfig(
                resolver = dnsServerList(dj.optJSONArray("resolver")).ifEmpty { DnsConfig.default().resolver },
                outbound = dnsServerList(dj.optJSONArray("outbound")).ifEmpty { DnsConfig.default().outbound },
                direct = dnsServerList(dj.optJSONArray("direct")).ifEmpty { DnsConfig.default().direct },
                proxy = dnsServerList(dj.optJSONArray("proxy")).ifEmpty { DnsConfig.default().proxy },
                resolveMode = dj.optString("resolveMode").ifEmpty { "proxy" },
                testDomain = dj.optString("testDomain").ifEmpty { "gstatic.com" }
            )
        }
        val bir = mutableListOf<BuiltInRule>()
        val ba = root.optJSONArray("builtInRules")
        if (ba != null) {
            for (i in 0 until ba.length()) {
                val o = ba.getJSONObject(i)
                bir.add(
                    BuiltInRule(
                        index = o.optInt("index", -1),
                        label = o.optString("label"),
                        enabled = o.optBoolean("enabled", true),
                        ruleJson = o.optString("ruleJson")
                    )
                )
            }
        }
        // v3.0：activeTemplateId / customTemplates（老文件缺字段时给默认值）
        val activeTemplateId = root.optString("activeTemplateId").ifEmpty { "minimal" }
        val cts = mutableListOf<RuleTemplate>()
        val ca = root.optJSONArray("customTemplates")
        if (ca != null) {
            for (i in 0 until ca.length()) {
                val o = ca.getJSONObject(i)
                val trules = mutableListOf<BuiltInRule>()
                val ra = o.optJSONArray("rules")
                if (ra != null) {
                    for (j in 0 until ra.length()) {
                        val ro = ra.getJSONObject(j)
                        trules.add(
                            BuiltInRule(
                                index = ro.optInt("index", -1),
                                label = ro.optString("label"),
                                enabled = ro.optBoolean("enabled", true),
                                ruleJson = ro.optString("ruleJson")
                            )
                        )
                    }
                }
                cts.add(
                    RuleTemplate(
                        id = o.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
                        name = o.optString("name").ifEmpty { "自定义模版" },
                        finalOutbound = o.optString("finalOutbound").ifEmpty { "手动" },
                        rules = trules,
                        isBuiltIn = o.optBoolean("isBuiltIn", false)
                    )
                )
            }
        }
        // v1 兼容：perApp 字段直接忽略（功能已删除）
        return AppConfigData(nodes, groups, dns, bir.filter { it.index >= 0 }, cts, activeTemplateId)
    }
}
