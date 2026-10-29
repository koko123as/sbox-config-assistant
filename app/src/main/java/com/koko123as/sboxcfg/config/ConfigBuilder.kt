package com.koko123as.sboxcfg.config

import android.content.Context
import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.model.BuiltInRule
import com.koko123as.sboxcfg.model.DnsConfig
import com.koko123as.sboxcfg.model.DnsServer
import com.koko123as.sboxcfg.model.RuleConditions
import com.koko123as.sboxcfg.parse.CountryUtil
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置组装：模板 + 节点 outbounds + 自定义规则组(logical) + DNS 联动。
 * 对标远程 /custom-config 的 worker 生成逻辑。
 * v2：detour 链式代理（含环路检测）、DNS 参数化、内置规则开关；删除按应用 TUN。
 */
object ConfigBuilder {

    const val SFA_PKG = "io.nekohasekai.sfa"
    const val SELF_PKG = "com.koko123as.sboxcfg"

    data class BuildResult(val config: JSONObject, val warnings: List<String>)

    /** 所有可选作出口的 tag（节点 + 国家组 + 固定分组），供规则编辑器选择 */
    fun availableOutboundTags(data: AppConfigData): List<String> {
        val tags = mutableListOf<String>()
        tags.addAll(data.nodes.map { it.tag })
        tags.addAll(activeCountries(data))
        tags.addAll(listOf("默认", "AI", "AI应用", "账号", "Google", "Final", "手动", "自动", "直连", "GLOBAL", "延迟辅助"))
        return tags.distinct()
    }

    private fun activeCountries(data: AppConfigData): List<String> =
        CountryUtil.NAMES.filter { c -> data.nodes.any { CountryUtil.detect(it.tag) == c } }

    /**
     * ② 内置规则中文摘要：模板 route.rules 用下标做稳定身份（模板无 name 字段）。
     * 首次运行时生成 label，如 "geosite-google → Google"。
     */
    fun describeBuiltInRules(context: Context): List<BuiltInRule> =
        describeBuiltInRules(TemplateLoader.load(context))

    fun describeBuiltInRules(template: JSONObject): List<BuiltInRule> {
        val rules = template.getJSONObject("route").getJSONArray("rules")
        return (0 until rules.length()).map { i ->
            BuiltInRule(i, summarizeRule(rules.getJSONObject(i)), true)
        }
    }

    fun summarizeRule(r: JSONObject): String {
        val m = mutableListOf<String>()
        fun arr(k: String, label: String) {
            val a = r.optJSONArray(k)
            if (a != null && a.length() > 0) m.add("$label×${a.length()}")
        }
        arr("domain_suffix", "域名后缀")
        arr("domain", "完整域名")
        arr("domain_keyword", "关键字")
        arr("domain_regex", "正则")
        arr("ip_cidr", "IP段")
        val rs = r.optString("rule_set", "")
        if (rs.isNotEmpty()) m.add(rs)
        arr("package_name", "应用")
        if (r.optBoolean("ip_is_private", false)) m.add("私网IP")
        r.optString("action", "").ifEmpty { null }?.let { m.add("动作:$it") }
        r.optString("clash_mode", "").ifEmpty { null }?.let { m.add("模式:$it") }
        val matcher = if (m.isEmpty()) "其他" else m.joinToString("、")
        val out = r.optString("outbound", "")
        return if (out.isNotEmpty()) "$matcher → $out" else matcher
    }

    fun build(data: AppConfigData, context: Context): BuildResult =
        build(data, TemplateLoader.load(context)) // 深拷贝（每次重新解析）

    /** JVM 可测重载：template 必须是深拷贝后的 JSONObject（调用方每次重新解析） */
    fun build(data: AppConfigData, template: JSONObject): BuildResult {
        val warnings = mutableListOf<String>()
        val cfg = template

        // ---- 0. 内置规则（v3.0 模版系统）：用 data.builtInRules 的 ruleJson 组装，与模板解耦 ----
        // 不再读模板 route.rules，也不再需要 bir.size == rules.length() 的脆弱守卫。
        val activeTpl = TemplatePresets.findTemplate(data, template)
        val finalOutbound = activeTpl.finalOutbound.ifEmpty { "手动" }
        run {
            val route = cfg.getJSONObject("route")
            val tplRules = route.optJSONArray("rules") // 模板原始规则，仅老数据回退用
            val kept = JSONArray()
            var offCount = 0
            for (b in data.builtInRules) {
                if (!b.enabled) { offCount++; continue }
                var rj = b.ruleJson
                if (rj.isBlank() && tplRules != null && b.index >= 0 && b.index < tplRules.length()) {
                    rj = tplRules.getJSONObject(b.index).toString() // 老数据回退：按 index 取模板
                }
                if (rj.isBlank()) { warnings.add("内置规则「${b.label}」无有效内容，已跳过"); continue }
                try { kept.put(JSONObject(rj)) }
                catch (_: Exception) { warnings.add("内置规则「${b.label}」JSON 无效，已跳过") }
            }
            route.put("rules", kept)
            route.put("final", finalOutbound)
            if (offCount > 0) warnings.add("已关闭 $offCount 条内置规则")
        }

        // ---- 1. 节点 outbounds（tag 去重订正） ----
        val used = mutableSetOf<String>()
        val nodeObjs = data.nodes.map { n ->
            var tag = n.tag
            if (!used.add(tag)) {
                var i = 2
                while (!used.add("$tag-$i")) i++
                tag = "$tag-$i"
                warnings.add("节点 tag 重复，已改名为「$tag」")
            }
            val o = JSONObject(n.rawJson)
            o.put("tag", tag)
            Triple(n.tag, tag, o) // 原tag, 最终tag, outbound
        }
        val finalTagOf = nodeObjs.associate { it.first to it.second }
        val nodeTags = nodeObjs.map { it.second }
        if (nodeTags.isEmpty()) warnings.add("还没有节点：请先在「节点」页粘贴链接解析")

        // ---- 1b. ⑦ detour 链式代理：校验＋DFS 环路检测 ----
        run {
            // detour 边：最终tag → 目标最终tag
            val edges = mutableMapOf<String, String>()
            for ((origTag, finalTag, o) in nodeObjs) {
                val want = data.nodes.find { it.tag == origTag }?.detourTag ?: continue
                val target = finalTagOf[want]
                when {
                    target == null -> warnings.add("节点「$finalTag」的前置代理「$want」不存在，已忽略")
                    target == finalTag -> warnings.add("节点「$finalTag」的前置代理不能是自己，已忽略")
                    else -> edges[finalTag] = target
                }
            }
            // DFS 找环：环上所有节点的 detour 都丢弃
            val inCycle = mutableSetOf<String>()
            val color = mutableMapOf<String, Int>() // 0=未访问 1=栈中 2=完成
            fun dfs(u: String, stack: MutableList<String>) {
                color[u] = 1
                stack.add(u)
                val v = edges[u]
                if (v != null) {
                    when (color.getOrDefault(v, 0)) {
                        1 -> {
                            // 找到环：栈中从 v 开始的都是环上节点
                            val idx = stack.indexOf(v)
                            for (i in idx until stack.size) inCycle.add(stack[i])
                        }
                        0 -> dfs(v, stack)
                    }
                }
                stack.removeAt(stack.size - 1)
                color[u] = 2
            }
            for (t in edges.keys) if (color.getOrDefault(t, 0) == 0) dfs(t, mutableListOf())
            if (inCycle.isNotEmpty()) {
                warnings.add("检测到前置代理环路（${inCycle.joinToString("→")}），已丢弃环上节点的 detour")
            }
            for ((_, finalTag, o) in nodeObjs) {
                val target = edges[finalTag]
                if (target != null && finalTag !in inCycle && target !in inCycle) {
                    o.put("detour", target)
                }
            }
        }

        // ---- 2. 国家分组（空组自动剔除） ----
        val activePools = activeCountries(data).map { name ->
            name to nodeTags.filter { CountryUtil.detect(it) == name }
        }
        val countryNames = activePools.map { it.first }

        // ---- 3. outbounds（v3.0 引用裁剪：只生成被引用到的分组选择器） ----
        // 收集被引用的 outbound tags：剩余内置规则 + 启用的自定义组 + final
        val referenced = mutableSetOf<String>()
        run {
            val rr = cfg.getJSONObject("route").optJSONArray("rules")
            if (rr != null) for (i in 0 until rr.length()) {
                rr.optJSONObject(i)?.optString("outbound")?.takeIf { it.isNotEmpty() }
                    ?.let { referenced.add(it) }
            }
        }
        for (g in data.ruleGroups) if (g.enabled) referenced.add(g.outboundTag)
        referenced.add(finalOutbound)
        // DNS 服务器的 detour 也算引用，否则 detour 指向的分组被裁剪会导致 SFA 报 outbound detour not found
        for (s in data.dns.allServers()) {
            if (s.detour.isNotEmpty()) referenced.add(s.detour)
        }

        // 分组选择器定义（tag → 成员），用于传递闭包
        val groupDefs = linkedMapOf<String, List<String>>()
        for (t in listOf("默认", "AI", "AI应用", "账号", "Google"))
            groupDefs[t] = countryNames + listOf("手动", "自动")
        groupDefs["Final"] = listOf("默认") + countryNames + listOf("手动", "自动", "直连")
        for ((name, arr) in activePools) groupDefs[name] = arr
        groupDefs["GLOBAL"] = listOf("默认", "AI", "AI应用", "账号", "Google", "Final") +
            countryNames + listOf("手动", "自动", "延迟辅助", "直连")
        groupDefs["延迟辅助"] = listOf("直连")

        val needed = referenced.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            for ((tag, members) in groupDefs) {
                if (tag in needed) for (m in members) if (needed.add(m)) changed = true
            }
        }

        val outbounds = JSONArray()
        fun sel(tag: String, list: List<String>) =
            JSONObject().put("type", "selector").put("tag", tag).put("outbounds", JSONArray(list))
        val pruned = mutableListOf<String>()
        fun emitGroup(tag: String, list: List<String>) {
            if (tag in needed) outbounds.put(sel(tag, list)) else pruned.add(tag)
        }

        for (t in listOf("默认", "AI", "AI应用", "账号", "Google"))
            emitGroup(t, countryNames + listOf("手动", "自动"))
        emitGroup("Final", listOf("默认") + countryNames + listOf("手动", "自动", "直连"))
        // 国家池始终保留
        for ((name, arr) in activePools) outbounds.put(sel(name, arr))
        // 手动/自动/节点/FirefoxVPN 始终保留
        outbounds.put(sel("手动", nodeTags + "FirefoxVPN"))
        outbounds.put(
            JSONObject().put("type", "urltest").put("tag", "自动")
                .put("outbounds", JSONArray(nodeTags))
                .put("url", "https://clients1.google.com/generate_204")
                .put("interval", "30m").put("tolerance", 100)
        )
        for ((_, _, o) in nodeObjs) outbounds.put(o)
        outbounds.put(
            JSONObject().put("type", "socks").put("tag", "FirefoxVPN")
                .put("server", "127.0.0.1").put("server_port", 10810).put("version", "5")
        )
        emitGroup("延迟辅助", listOf("直连"))
        emitGroup(
            "GLOBAL",
            listOf("默认", "AI", "AI应用", "账号", "Google", "Final") + countryNames +
                listOf("手动", "自动", "延迟辅助", "直连")
        )
        outbounds.put(JSONObject().put("type", "direct").put("tag", "直连").put("domain_resolver", "ali"))
        cfg.put("outbounds", outbounds)
        if (pruned.isNotEmpty()) warnings.add("已裁剪未被引用的分组：" + pruned.joinToString("、"))

        val outboundTagSet = mutableSetOf<String>()
        for (i in 0 until outbounds.length()) {
            outbounds.optJSONObject(i)?.optString("tag")?.let { outboundTagSet.add(it) }
        }

        // ---- 3b. ⑥ DNS 参数化 ----
        buildDns(cfg, data.dns, warnings, outboundTagSet)

        // ---- 4. 自定义规则组 → route logical 规则 + DNS 联动 ----
        val route = cfg.getJSONObject("route")
        val rules = route.getJSONArray("rules")
        // 插入点：AI应用 package_name 规则之后（用户规则优先于 geosite 通用规则）
        var insertIdx = rules.length()
        for (i in 0 until rules.length()) {
            if (rules.getJSONObject(i).has("package_name")) {
                insertIdx = i + 1
                break
            }
        }
        val routeRuleSets = route.getJSONArray("rule_set")
        val dnsRules = cfg.getJSONObject("dns").getJSONArray("rules")
        val customRsTag = mutableMapOf<String, String>() // url -> tag
        val rsCounter = Counter()

        val newRules = mutableListOf<JSONObject>()
        val newDnsRules = mutableListOf<JSONObject>()
        for (g in data.ruleGroups) {
            if (!g.enabled) continue
            if (g.conditions.isEmpty()) {
                warnings.add("规则组「${g.name}」没有匹配条件，已跳过")
                continue
            }
            if (!outboundTagSet.contains(g.outboundTag)) {
                warnings.add("规则组「${g.name}」绑定的出口「${g.outboundTag}」不存在，已跳过")
                continue
            }
            val matchers = buildMatchers(g.conditions, routeRuleSets, customRsTag, rsCounter)
            if (matchers.isEmpty()) {
                warnings.add("规则组「${g.name}」条件均为空，已跳过")
                continue
            }
            val rule = JSONObject()
            rule.put("type", "logical")
            rule.put("mode", g.mode)
            rule.put("rules", JSONArray(matchers))
            rule.put("outbound", g.outboundTag)
            newRules.add(rule)

            // DNS 联动：去掉 ip_cidr 与 geoip 类 rule_set（DNS 阶段还没有 IP）；
            // server 按 DNS 配置映射（直连→direct 组，否则→proxy 组；fakeip 模式→dns_fakeip）
            val dnsMatchers = buildDnsMatchers(g.conditions, customRsTag)
            if (dnsMatchers.isNotEmpty()) {
                val d = JSONObject()
                d.put("type", "logical")
                d.put("mode", g.mode)
                d.put("rules", JSONArray(dnsMatchers))
                d.put("server", data.dns.linkedServer(g.outboundTag))
                newDnsRules.add(d)
            }
        }

        // 插回 rules（JSONArray 不支持 insert，重建）
        val merged = JSONArray()
        for (i in 0 until insertIdx) merged.put(rules.getJSONObject(i))
        for (r in newRules) merged.put(r)
        for (i in insertIdx until rules.length()) merged.put(rules.getJSONObject(i))
        route.put("rules", merged)
        // DNS 规则追加到末尾（模板已有规则都不匹配自定义域名时才命中）
        for (d in newDnsRules) dnsRules.put(d)

        return BuildResult(cfg, warnings)
    }

    /**
     * ⑥ DNS 参数化：按 DnsConfig 重写 dns.servers 与 dns.final。
     * fakeip 模式追加 fakeip server（Karing 做法），并给出去警告。
     */
    private fun buildDns(cfg: JSONObject, dns: DnsConfig, warnings: MutableList<String>, outboundTagSet: Set<String>) {
        val dnsObj = cfg.getJSONObject("dns")
        var useDns = dns
        if (dns.allServers().isEmpty()) {
            warnings.add("DNS 服务器被删空，已恢复默认配置")
            useDns = DnsConfig.default()
        }
        val servers = JSONArray()
        val tagSet = mutableSetOf<String>()
        for (s in useDns.allServers()) {
            if (!tagSet.add(s.tag)) {
                warnings.add("DNS 服务器 tag 重复「${s.tag}」，已保留第一个")
                continue
            }
            // 兜底：detour 指向不存在的出站 tag 时去掉 detour（直连），防 SFA 报 outbound detour not found
            val srv = if (s.detour.isNotEmpty() && s.detour !in outboundTagSet) {
                warnings.add("DNS 服务器「${s.tag}」的 detour「${s.detour}」不存在，已改为直连")
                s.copy(detour = "")
            } else s
            servers.put(dnsServerJson(srv))
        }
        var finalTag = useDns.finalTag()
        if (useDns.resolveMode == "fakeip") {
            if (!tagSet.contains("dns_fakeip")) {
                servers.put(
                    JSONObject().put("type", "fakeip").put("tag", "dns_fakeip")
                        .put("inet4_range", "198.18.0.0/15")
                        .put("inet6_range", "fc00::/18")
                )
                tagSet.add("dns_fakeip")
            }
            warnings.add("已开启 fakeip 解析：SFA 刷新远程配置前请先完全断开，否则会陷入刷新死循环")
        }
        if (finalTag !in tagSet) {
            warnings.add("DNS final 指向的「$finalTag」不存在，已回退到第一个服务器")
            finalTag = tagSet.firstOrNull() ?: "local"
        }
        dnsObj.put("servers", servers)
        dnsObj.put("final", finalTag)
        // 模板 DNS 规则引用的 server tag 若被用户删改，给警告
        val rules = dnsObj.optJSONArray("rules")
        if (rules != null) {
            for (i in 0 until rules.length()) {
                val srv = rules.optJSONObject(i)?.optString("server", "")
                if (!srv.isNullOrEmpty() && srv !in tagSet) {
                    warnings.add("DNS 规则引用的服务器「$srv」不存在，可能导致解析失败")
                }
            }
        }
    }

    private fun dnsServerJson(s: DnsServer): JSONObject {
        val o = JSONObject()
        o.put("type", s.type)
        o.put("tag", s.tag)
        when (s.type) {
            "local" -> o.put("prefer_go", true) // 与模板一致
            "https" -> {
                // sing-box 1.14 的 https DNS server 只接受裸地址（IP/域名），
                // 不接受完整 URL（会被当成域名而报 missing domain resolver）。
                // 输入允许填 URL，生成时提取 host。
                var host = s.address.trim()
                if (host.startsWith("http", true)) {
                    host = host.substringAfter("://").substringBefore("/").substringBefore(":")
                } else {
                    host = host.substringBefore("/").substringBefore(":")
                }
                if (host.isEmpty()) host = s.address.trim()
                o.put("server", host)
            }
            else -> o.put("server", s.address.trim()) // tls / tcp / udp：server 为地址
        }
        if (s.detour.isNotEmpty()) o.put("detour", s.detour)
        return o
    }

    private class Counter {
        var n = 0
        fun next(): Int {
            n++
            return n
        }
    }

    /** 条件清单 → logical 子规则列表（每个条件独立成一个 matcher 对象） */
    private fun buildMatchers(
        c: RuleConditions,
        routeRuleSets: JSONArray,
        customRsTag: MutableMap<String, String>,
        rsCounter: Counter
    ): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun single(key: String, values: List<*>) {
            if (values.isNotEmpty()) out.add(JSONObject().put(key, JSONArray(values)))
        }
        single("domain_suffix", c.domainSuffix)
        single("domain", c.domain)
        single("domain_keyword", c.domainKeyword)
        single("domain_regex", c.domainRegex)
        single("ip_cidr", c.ipCidr)
        // 端口：纯数字进 port（int），范围进 port_range（冒号格式 8000:8100）
        val portInts = mutableListOf<Int>()
        val portRanges = mutableListOf<String>()
        for (p in c.ports) {
            val norm = p.replace("：", ":").replace("—", "-").replace("－", "-")
            val num = norm.toIntOrNull()
            if (num != null) {
                portInts.add(num)
            } else {
                val m = Regex("^(\\d+)[:-](\\d+)$").matchEntire(norm)
                if (m != null) portRanges.add("${m.groupValues[1]}:${m.groupValues[2]}")
            }
        }
        single("port", portInts)
        single("port_range", portRanges)
        single("protocol", c.protocols)
        // 自环防护：剔除 SFA 自身与本 App 包名
        val pkgs = c.packageNames.filter { it != SFA_PKG && it != SELF_PKG }
        single("package_name", pkgs)

        val rsTags = mutableListOf<String>()
        // 模板里 remote rule_set 的 tag 是 geosite-<code> / geoip-<code> 形式
        rsTags.addAll(c.geosite.map { "geosite-$it" })
        rsTags.addAll(c.geoip.map { "geoip-$it" })
        for (url in c.ruleSetUrls) {
            val tag = customRsTag.getOrPut(url) {
                val t = "custom-rs-${rsCounter.next()}"
                val format = if (url.lowercase().endsWith(".srs")) "binary" else "source"
                routeRuleSets.put(
                    JSONObject().put("type", "remote").put("tag", t)
                        .put("format", format).put("url", url)
                )
                t
            }
            rsTags.add(tag)
        }
        single("rule_set", rsTags)
        return out
    }

    /** DNS 联动 matcher：去掉 ip_cidr 与 geoip 类 rule_set */
    private fun buildDnsMatchers(
        c: RuleConditions,
        customRsTag: MutableMap<String, String>
    ): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun single(key: String, values: List<*>) {
            if (values.isNotEmpty()) out.add(JSONObject().put(key, JSONArray(values)))
        }
        single("domain_suffix", c.domainSuffix)
        single("domain", c.domain)
        single("domain_keyword", c.domainKeyword)
        single("domain_regex", c.domainRegex)
        // DNS 规则只接受纯数字端口，范围直接丢掉（DNS 阶段不支持范围）
        single("port", c.ports.mapNotNull { it.toIntOrNull() })
        val pkgs = c.packageNames.filter { it != SFA_PKG && it != SELF_PKG }
        single("package_name", pkgs)
        val rsTags = mutableListOf<String>()
        rsTags.addAll(c.geosite.map { "geosite-$it" })
        for (url in c.ruleSetUrls) customRsTag[url]?.let { rsTags.add(it) }
        single("rule_set", rsTags)
        return out
    }
}
