package com.koko123as.sboxcfg.parse

import com.koko123as.sboxcfg.model.NodeEntry
import org.json.JSONObject

/**
 * sing-box JSON 配置解析：直抄 Karing SingboxJsonUtils.tryJsonConvert
 * （singbox_json_utils.dart:17-200）的提取逻辑。
 * 取 outbounds 数组中可用的节点类型；跳过 direct/block/dns/selector/urltest；
 * detour 指向 direct 的剥掉；tag 去重。
 */
object SingboxJsonParser {

    /** 保留的 outbound 类型（sing-box 官方客户端可运行的） */
    private val KEEP = setOf(
        "socks", "http", "shadowsocks", "vmess", "trojan", "wireguard",
        "hysteria", "vless", "shadowtls", "tuic", "hysteria2", "tor", "ssh", "anytls"
    )
    private val SKIP_SILENT = setOf("direct", "block", "dns", "selector", "urltest")

    fun parse(json: String): FileParseResult {
        val nodes = mutableListOf<NodeEntry>()
        val skipped = mutableListOf<String>()
        val usedTags = mutableSetOf<String>()
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON 解析失败：${e.message}")
        }
        val arr = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("不是 sing-box 配置：缺少 outbounds")
        // 第一遍：收集 direct 的 tag（detour 指向 direct 的要剥掉）
        val directTags = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "direct") directTags.add(o.optString("tag"))
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type", "")
            val tag = o.optString("tag", "")
            if (type.isEmpty() || tag.isEmpty()) continue
            if (type in SKIP_SILENT) continue
            if (type !in KEEP) {
                skipped.add("$tag → 不支持的 outbound 类型 [$type]，已跳过")
                continue
            }
            val detour = o.optString("detour", "")
            if (detour.isNotEmpty() && detour in directTags) {
                o.remove("detour")
            }
            var newTag = NodeParser.dedupeTag(tag, usedTags)
            o.put("tag", newTag)
            nodes.add(
                NodeEntry(
                    tag = newTag,
                    protocol = type,
                    server = o.optString("server", ""),
                    port = o.optInt("server_port", 0),
                    rawJson = o.toString()
                )
            )
        }
        return FileParseResult(nodes, skipped)
    }
}
