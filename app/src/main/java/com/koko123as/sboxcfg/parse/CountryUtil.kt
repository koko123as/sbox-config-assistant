package com.koko123as.sboxcfg.parse

/** 国家分组：按节点 tag 关键词识别，空组自动剔除（移植自网页版） */
object CountryUtil {
    private val DEFS = listOf(
        "美国" to Regex("(^|[^a-z])us([^a-z]|$)|美国|united\\s*states", RegexOption.IGNORE_CASE),
        "新加坡" to Regex("(^|[^a-z])sg([^a-z]|$)|新加坡|狮城|singapore", RegexOption.IGNORE_CASE),
        "日本" to Regex("(^|[^a-z])jp([^a-z]|$)|日本|japan", RegexOption.IGNORE_CASE),
        "韩国" to Regex("(^|[^a-z])kr([^a-z]|$)|韩国|korea", RegexOption.IGNORE_CASE),
        "香港" to Regex("(^|[^a-z])hk([^a-z]|$)|香港|hong\\s*kong", RegexOption.IGNORE_CASE),
        "台湾" to Regex("(^|[^a-z])tw([^a-z]|$)|台湾|taiwan", RegexOption.IGNORE_CASE)
    )

    val NAMES: List<String> = DEFS.map { it.first }

    fun detect(tag: String): String =
        DEFS.firstOrNull { it.second.containsMatchIn(tag) }?.first ?: ""
}
