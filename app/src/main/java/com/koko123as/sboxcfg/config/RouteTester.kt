package com.koko123as.sboxcfg.config

import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.model.RuleGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则命中测试（v3.1）：输入域名/IP，按生成配置时的规则顺序做本地匹配，
 * 告诉用户会命中哪条规则、走哪个出口。
 *
 * 纯函数，可 JVM 测试。不读模板文件；finalOutbound 取激活模版（需调用方传 templateJson，
 * 传 null 时原版模版回退到极简，见 TemplatePresets.findTemplate）。
 *
 * 诚实边界：
 * - rule_set（geosite/geoip/远程）本地无数据库 → 判为 UNKNOWN（无法本地判定），
 *   不冒充能判定；
 * - port/protocol/package_name：命中测试只有域名/IP 上下文，直接跳过；
 * - 域名输入不对 ip_cidr 做判定（sing-box 实际会先解析再比，本地不做 DNS）。
 */
object RouteTester {

    /** 命中结果 */
    data class HitResult(
        /** 命中的规则序号（生效规则列表 0-based），null=未命中 */
        val matchedIndex: Int?,
        /** 规则 label（未命中时为"未命中任何规则"） */
        val label: String,
        /** 出口（命中=规则出口；未命中=final） */
        val outbound: String,
        /** 备注（无法本地判定的原因等） */
        val note: String?,
        /** 是否为"无法本地判定" */
        val undetermined: Boolean = false
    )

    /** 生效规则（顺序与生成一致） */
    data class TestRule(val label: String, val rule: JSONObject, val outbound: String)

    internal enum class Eval { MATCH, NO_MATCH, UNKNOWN }

    internal data class Input(
        val raw: String,
        val isIp: Boolean,
        val ipv4: Int?, // IPv4 打包；IPv6/域名为 null
        val ipv6: String?, // IPv6 字面量（小写）
        val domain: String // 小写域名；IP 输入时为 ""
    )

    // ---------------- 输入解析 ----------------

    private val IPV4_RE = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
    private val DOMAIN_LABEL_RE = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * v3.1.1 域名合法性：按 label 逐段校验。
     * - 连字符允许出现在 label 内部（如 translate-static.brave.com）；
     * - label 首尾不能是连字符（-foo.bar.com、foo-.bar.com 非法）；
     * - 每段 1~63 字符，全长 ≤253；必须含点（保持原有行为）。
     */

    /**
     * v3.1.1 输入规范化：宽容处理用户粘贴的各种形式。
     * - 去首尾空白与斜杠：`/translate-static.brave.com/` → `translate-static.brave.com`
     * - 去 scheme：`https://google.com/abc` → `google.com`
     * - 去路径/query/fragment：取第一个 `/`、`?`、`#` 之前
     * - 去端口：`google.com:443` → `google.com`（IPv6 不动，交给 parseInput）
     * 返回 "" 表示规范化后为空（调用方按非法输入处理）。
     */
    internal fun normalizeInput(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        // 先去首尾斜杠（再处理 scheme，避免 "/host/" 被第一步的路径截断吃掉）
        t = t.trim('/')
        if (t.isEmpty()) return ""
        // 去 scheme：取 "://" 之后
        t = SCHEME_RE.replace(t, "")
        // 去路径/query/fragment
        val cut = t.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) t = t.substring(0, cut)
        t = t.trim().trim('/')
        if (t.isEmpty()) return ""
        // 去端口：只处理单冒号的 host:port；[v6]:port 与裸 IPv6 不动
        if (!t.startsWith("[") && t.count { it == ':' } == 1) {
            val host = t.substringBefore(':')
            val port = t.substringAfter(':')
            if (host.isNotEmpty() && port.all { it.isDigit() }) t = host
        }
        return t.trim()
    }

    /** null=非法输入 */
    internal fun parseInput(raw: String): Input? {
        val t = normalizeInput(raw).lowercase()
        if (t.isEmpty()) return null
        if (':' in t) {
            // IPv6 字面量（含 [v6]:port 形式，做宽容剥离）
            val host = t.removePrefix("[").substringBefore("]")
            if (host.isEmpty() || !host.all { it in '0'..'9' || it in 'a'..'f' || it == ':' }) return null
            return Input(t, isIp = true, ipv4 = null, ipv6 = host, domain = "")
        }
        if (IPV4_RE.matches(t)) {
            val parts = t.split(".").map { it.toIntOrNull() ?: return null }
            if (parts.any { it !in 0..255 }) return null
            val ip = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
            return Input(t, isIp = true, ipv4 = ip, ipv6 = null, domain = "")
        }
        if (!isValidDomain(t)) return null
        return Input(t, isIp = false, ipv4 = null, ipv6 = null, domain = t)
    }

    internal fun isValidDomain(d: String): Boolean {
        if (d.isEmpty() || d.length > 253 || '.' !in d) return false
        return d.split(".").all { label ->
            label.isNotEmpty() && label.length <= 63 && DOMAIN_LABEL_RE.matches(label)
        }
    }

    // ---------------- IP 工具 ----------------

    /** IPv4 CIDR 包含判断（task 要求支持 v4） */
    fun cidrContainsV4(cidr: String, ip: Int): Boolean {
        return try {
            val parts = cidr.trim().split("/")
            val segs = parts[0].trim().split(".")
            if (segs.size != 4) return false
            val nums = segs.map { it.toIntOrNull() ?: return false }
            if (nums.any { it !in 0..255 }) return false
            val base = (nums[0] shl 24) or (nums[1] shl 16) or (nums[2] shl 8) or nums[3]
            val prefix = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: return false else 32
            if (prefix !in 0..32) return false
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            (ip and mask) == (base and mask)
        } catch (_: Exception) {
            false
        }
    }

    /** 私网 IP 判断（v4 全段 + v6 ULA/链路本地） */
    internal fun isPrivateIp(inp: Input): Boolean {
        inp.ipv4?.let { ip ->
            if ((ip ushr 24) == 10) return true // 10/8
            if ((ip and 0xFFF00000.toInt()) == 0xAC100000.toInt()) return true // 172.16/12
            if ((ip and 0xFFFF0000.toInt()) == 0xC0A80000.toInt()) return true // 192.168/16
            if ((ip ushr 24) == 127) return true // 127/8
            return false
        }
        inp.ipv6?.let { v6 ->
            // fc00::/7 (fc/fd 开头)，fe80::/10
            return v6.startsWith("fc") || v6.startsWith("fd") || v6.startsWith("fe80")
        }
        return false
    }

    // ---------------- 规则求值（三值逻辑） ----------------

    private fun ruleSetNames(rule: JSONObject): List<String> {
        if (!rule.has("rule_set")) return emptyList()
        return when (val v = rule.opt("rule_set")) {
            is String -> if (v.isNotEmpty()) listOf(v) else emptyList()
            is JSONArray -> (0 until v.length()).map { v.optString(it) }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    /**
     * rule_set 在该输入下是否"确定不匹配"（无需数据库即可判定）：
     * - geosite 系永不匹配 IP；
     * - geoip 系不含私网 IP。
     */
    private fun isRuleSetDefiniteNoMatch(name: String, inp: Input): Boolean {
        val ln = name.lowercase()
        return when {
            ln.startsWith("geosite") -> inp.isIp
            ln.startsWith("geoip") -> inp.isIp && isPrivateIp(inp)
            else -> false
        }
    }

    /**
     * rule_set 是否"可能相关"（需数据库才能判定）：
     * - geosite 系对域名可能相关；
     * - geoip 系：IP 输入可能相关；域名输入时 sing-box 会先解析再比对，本地无法判定→也算相关；
     * - 自定义/未知 → 一律视为可能相关。
     */
    private fun isRuleSetMaybeRelevant(name: String, inp: Input): Boolean {
        val ln = name.lowercase()
        return when {
            ln.startsWith("geosite") -> !inp.isIp
            ln.startsWith("geoip") -> true
            else -> true
        }
    }

    private fun strArr(rule: JSONObject, key: String): List<String> {
        val a = rule.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }

    /** 叶子规则求值；logical 递归。port/protocol/package_name 无上下文，跳过。 */
    internal fun evalRule(rule: JSONObject, inp: Input): Eval {
        if (rule.optString("type") == "logical") {
            val subs = rule.optJSONArray("rules") ?: return Eval.UNKNOWN
            if (subs.length() == 0) return Eval.UNKNOWN
            val rs = (0 until subs.length()).map {
                try { evalRule(subs.getJSONObject(it), inp) } catch (_: Exception) { Eval.UNKNOWN }
            }
            return if (rule.optString("mode", "and") == "or") {
                when {
                    rs.any { it == Eval.MATCH } -> Eval.MATCH
                    rs.all { it == Eval.NO_MATCH } -> Eval.NO_MATCH
                    else -> Eval.UNKNOWN
                }
            } else {
                when {
                    rs.any { it == Eval.NO_MATCH } -> Eval.NO_MATCH
                    rs.all { it == Eval.MATCH } -> Eval.MATCH
                    else -> Eval.UNKNOWN
                }
            }
        }
        // 叶子：各 matcher 隐式 AND
        var sawEvaluable = false
        var sawUnknown = false

        val ds = strArr(rule, "domain_suffix")
        if (ds.isNotEmpty()) {
            sawEvaluable = true
            if (inp.isIp) return Eval.NO_MATCH
            val ok = ds.any { s ->
                val norm = s.trimStart('.').lowercase()
                inp.domain == norm || inp.domain.endsWith(".$norm")
            }
            if (!ok) return Eval.NO_MATCH
        }
        val dm = strArr(rule, "domain")
        if (dm.isNotEmpty()) {
            sawEvaluable = true
            if (inp.isIp) return Eval.NO_MATCH
            if (dm.none { it.lowercase() == inp.domain }) return Eval.NO_MATCH
        }
        val kw = strArr(rule, "domain_keyword")
        if (kw.isNotEmpty()) {
            sawEvaluable = true
            if (inp.isIp) return Eval.NO_MATCH
            if (kw.none { inp.domain.contains(it.lowercase()) }) return Eval.NO_MATCH
        }
        val rx = strArr(rule, "domain_regex")
        if (rx.isNotEmpty()) {
            sawEvaluable = true
            if (inp.isIp) return Eval.NO_MATCH
            val ok = rx.any {
                try { Regex(it).containsMatchIn(inp.domain) } catch (_: Exception) { false }
            }
            if (!ok) return Eval.NO_MATCH
        }
        val cidr = strArr(rule, "ip_cidr")
        if (cidr.isNotEmpty()) {
            sawEvaluable = true
            // 域名输入不做 DNS 解析，直接判为不匹配（诚实边界，见类注释）
            if (!inp.isIp || inp.ipv4 == null) return Eval.NO_MATCH
            if (cidr.none { cidrContainsV4(it, inp.ipv4) }) return Eval.NO_MATCH
        }
        if (rule.has("ip_is_private")) {
            sawEvaluable = true
            if (!inp.isIp) return Eval.NO_MATCH
            if (rule.optBoolean("ip_is_private", false) != isPrivateIp(inp)) return Eval.NO_MATCH
        }
        val rsNames = ruleSetNames(rule)
        if (rsNames.isNotEmpty()) {
            sawEvaluable = true
            // 叶子内 AND：rule_set 全部确定不匹配 → 整条规则确定不匹配
            if (rsNames.all { isRuleSetDefiniteNoMatch(it, inp) }) return Eval.NO_MATCH
            if (rsNames.any { isRuleSetMaybeRelevant(it, inp) }) sawUnknown = true
            // 既非全不匹配、也无可能相关 → 中性，忽略该 matcher
        }
        // port / protocol / package_name：无上下文，跳过
        if (!sawEvaluable) return Eval.UNKNOWN
        return if (sawUnknown) Eval.UNKNOWN else Eval.MATCH
    }

    /** UNKNOWN 的原因说明 */
    /** UNKNOWN 的原因说明（只列与本次输入可能相关的 rule_set） */
    private fun unknownReason(rule: JSONObject, inp: Input): String {
        val parts = mutableListOf<String>()
        val relevant = ruleSetNames(rule).filter { isRuleSetMaybeRelevant(it, inp) }
        if (relevant.isNotEmpty()) {
            parts.add("含 rule_set（${relevant.joinToString("、")}），本地无数据库")
        }
        // 剥掉 rule_set 后是否还有可判定条件
        val probe = JSONObject(rule.toString())
        probe.remove("rule_set")
        if (!evalRuleShallow(probe)) parts.add("其余条件无域名/IP 可判定项（如仅含端口/应用条件）")
        return parts.joinToString("；").ifEmpty { "存在无法本地判定的匹配条件" }
    }

    /** 粗检：去掉 rule_set 后是否还有可判定 matcher（不含 UNKNOWN 语义） */
    private fun evalRuleShallow(rule: JSONObject): Boolean {
        if (rule.optString("type") == "logical") {
            val subs = rule.optJSONArray("rules") ?: return false
            return (0 until subs.length()).any {
                try { evalRuleShallow(subs.getJSONObject(it)) } catch (_: Exception) { false }
            }
        }
        return rule.has("domain_suffix") || rule.has("domain") || rule.has("domain_keyword") ||
            rule.has("domain_regex") || rule.has("ip_cidr") || rule.has("ip_is_private")
    }

    // ---------------- 自定义组转换（与 build() step 4 一致的子集） ----------------

    /**
     * 自定义规则组 → logical 规则 JSON（命中测试用）。
     * 字段映射与 ConfigBuilder.buildMatchers 一致；port/protocol/package_name 跳过；
     * geosite/geoip/远程 rule_set 统一记为 rule_set（本地无法判定）。
     */
    internal fun customGroupToRule(g: RuleGroup): JSONObject? {
        val c = g.conditions
        val matchers = mutableListOf<JSONObject>()
        fun single(key: String, values: List<String>) {
            if (values.isNotEmpty()) matchers.add(JSONObject().put(key, JSONArray(values)))
        }
        single("domain_suffix", c.domainSuffix)
        single("domain", c.domain)
        single("domain_keyword", c.domainKeyword)
        single("domain_regex", c.domainRegex)
        single("ip_cidr", c.ipCidr)
        val rsTags = mutableListOf<String>()
        rsTags.addAll(c.geosite.map { "geosite-$it" })
        rsTags.addAll(c.geoip.map { "geoip-$it" })
        rsTags.addAll(c.ruleSetUrls)
        single("rule_set", rsTags)
        if (matchers.isEmpty()) return null
        return JSONObject()
            .put("type", "logical")
            .put("mode", g.mode)
            .put("rules", JSONArray(matchers))
            .put("outbound", g.outboundTag)
    }

    /**
     * 生效规则列表（顺序与生成一致）：
     * 启用的内置规则 → 自定义组插到"第一个含 package_name 的内置规则之后"（与 build() step 4 一致），否则追加末尾。
     */
    fun effectiveRules(data: AppConfigData): List<TestRule> {
        val list = mutableListOf<TestRule>()
        for (b in data.builtInRules) {
            if (!b.enabled || b.ruleJson.isBlank()) continue
            try {
                val o = JSONObject(b.ruleJson)
                list.add(TestRule(b.label, o, o.optString("outbound")))
            } catch (_: Exception) {
            }
        }
        var insertIdx = list.size
        for (i in list.indices) {
            if (list[i].rule.has("package_name")) {
                insertIdx = i + 1
                break
            }
        }
        val customs = mutableListOf<TestRule>()
        for (g in data.ruleGroups) {
            if (!g.enabled || g.conditions.isEmpty()) continue
            if (g.outboundTag.isBlank()) continue
            val rule = try { customGroupToRule(g) } catch (_: Exception) { null } ?: continue
            customs.add(TestRule(g.name.ifEmpty { "自定义规则" }, rule, g.outboundTag))
        }
        list.addAll(insertIdx, customs)
        return list
    }

    // ---------------- 主入口 ----------------

    /**
     * @param templateJson base_template.json 解析结果（可 null；原版模版无它时 final 回退极简）
     */
    fun test(data: AppConfigData, input: String, templateJson: JSONObject?): HitResult {
        val inp = parseInput(input)
            ?: return HitResult(null, "输入无效", "", "请输入域名（如 google.com）或 IP（如 8.8.8.8）")
        val rules = effectiveRules(data)
        val finalOb = TemplatePresets.findTemplate(data, templateJson).finalOutbound.ifEmpty { "手动" }
        for ((idx, tr) in rules.withIndex()) {
            when (evalRule(tr.rule, inp)) {
                Eval.MATCH -> return HitResult(idx, tr.label, tr.outbound, null)
                Eval.UNKNOWN -> return HitResult(
                    matchedIndex = idx,
                    label = tr.label,
                    outbound = tr.outbound.ifEmpty { finalOb },
                    note = "无法本地判定：" + unknownReason(tr.rule, inp) +
                        "。若实际未命中，将继续匹配后续规则。",
                    undetermined = true
                )
                Eval.NO_MATCH -> {}
            }
        }
        return HitResult(null, "未命中任何规则", finalOb, "将走 final 出口")
    }
}
