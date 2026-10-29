package com.koko123as.sboxcfg.config

import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.model.BuiltInRule
import com.koko123as.sboxcfg.model.RuleTemplate
import org.json.JSONObject

/**
 * v3.0 规则模版。
 * - minimal（极简分流，新用户默认）：国内直连、非国内走代理（final=手动）；
 *   国家组保留，其他分组按引用裁剪自动消失。
 * - original（原版完整）：base_template.json 的 34 条原样保留，老用户升级后落在此模版。
 * base_template.json 本体不动，仍是 34 条，作为原版来源。
 */
object TemplatePresets {

    const val MINIMAL_ID = "minimal"
    const val ORIGINAL_ID = "original"

    /** 极简分流：4 条规则（sing-box 1.12+ 已移除 geosite 数据库，国内网站/IP 走模板自带的远程 rule_set） */
    fun minimal(): RuleTemplate {
        fun r(index: Int, label: String, json: String) =
            BuiltInRule(index, label, true, json)
        return RuleTemplate(
            id = MINIMAL_ID,
            name = "极简分流",
            finalOutbound = "手动",
            rules = listOf(
                r(0, "国内域名（.cn 后缀）→ 直连", """{"domain_suffix":[".cn"],"outbound":"直连"}"""),
                r(1, "国内网站（geosite-cn）→ 直连", """{"rule_set":"geosite-cn","outbound":"直连"}"""),
                r(2, "国内 IP（geoip-cn）→ 直连", """{"rule_set":"geoip-cn","outbound":"直连"}"""),
                r(3, "私网 IP → 直连", """{"ip_is_private":true,"outbound":"直连"}""")
            ),
            isBuiltIn = true
        )
    }

    /** 原版完整：从模板 route.rules 逐条生成，label 复用 summarizeRule */
    fun original(template: JSONObject): RuleTemplate {
        val rules = template.getJSONObject("route").getJSONArray("rules")
        val list = (0 until rules.length()).map { i ->
            val o = rules.getJSONObject(i)
            BuiltInRule(i, ConfigBuilder.summarizeRule(o), true, o.toString())
        }
        return RuleTemplate(
            id = ORIGINAL_ID,
            name = "原版完整",
            finalOutbound = template.getJSONObject("route").optString("final", "Final").ifEmpty { "Final" },
            rules = list,
            isBuiltIn = true
        )
    }

    fun builtInTemplates(template: JSONObject): List<RuleTemplate> =
        listOf(minimal(), original(template))

    /**
     * v3.0 启动迁移（纯函数，可 JVM 测试）：
     * - 新用户（builtInRules 为空）→ 极简分流
     * - 老用户（有 ruleJson 为空）→ 按 index 从原模板回填，落在「原版完整」，行为不变
     * - 已是 v3.0 数据 → 原样返回
     */
    fun migrateOnLaunch(data: AppConfigData, templateJson: JSONObject): AppConfigData {
        if (data.builtInRules.isEmpty()) {
            val t = minimal()
            return data.copy(builtInRules = t.rules, activeTemplateId = t.id)
        }
        if (data.builtInRules.any { it.ruleJson.isBlank() }) {
            val byIdx = original(templateJson).rules.associateBy { it.index }
            val backfilled = data.builtInRules.map { b ->
                if (b.ruleJson.isBlank()) {
                    byIdx[b.index]?.let { b.copy(ruleJson = it.ruleJson, label = b.label.ifEmpty { it.label }) } ?: b
                } else b
            }
            return data.copy(builtInRules = backfilled, activeTemplateId = ORIGINAL_ID)
        }
        return data
    }

    /**
     * 找到 data 当前激活的模版。original 需要 template JSON 来还原 34 条规则；
     * 未知 id 回退到 minimal。
     */
    fun findTemplate(data: AppConfigData, templateJson: JSONObject?): RuleTemplate {
        data.customTemplates.find { it.id == data.activeTemplateId }?.let { return it }
        return when (data.activeTemplateId) {
            ORIGINAL_ID -> templateJson?.let { original(it) } ?: minimal()
            else -> minimal()
        }
    }
}
