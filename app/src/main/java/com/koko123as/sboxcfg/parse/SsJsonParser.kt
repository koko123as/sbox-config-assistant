package com.koko123as.sboxcfg.parse

import com.koko123as.sboxcfg.model.NodeEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * ss JSON 订阅解析：[{server, server_port, method, password, remarks}]
 * 字段抄 Karing ss_json_utils.dart（SSJsonData）。
 * 内容可能是 base64 包一层，解不开就按原文解析。
 */
object SsJsonParser {

    fun parse(content: String): FileParseResult {
        val nodes = mutableListOf<NodeEntry>()
        val skipped = mutableListOf<String>()
        val usedTags = mutableSetOf<String>()
        val text = try {
            val d = NodeParser.b64Decode(content.trim())
            if (d.trim().startsWith("[")) d else content
        } catch (_: Exception) {
            content
        }
        val arr = try {
            JSONArray(text.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("ss JSON 解析失败：${e.message}")
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            try {
                val server = o.optString("server", "")
                val port = o.optInt("server_port", 0)
                val method = o.optString("method", "")
                val password = o.optString("password", "")
                if (server.isEmpty() || port == 0 || method.isEmpty()) {
                    skipped.add("第 ${i + 1} 项缺少 server/port/method，已跳过")
                    continue
                }
                var tag = o.optString("remarks", "").ifEmpty { "$server:$port" }
                tag = NodeParser.dedupeTag(tag, usedTags)
                val ob = JSONObject()
                ob.put("type", "shadowsocks")
                ob.put("tag", tag)
                ob.put("server", server)
                ob.put("server_port", port)
                ob.put("method", method)
                ob.put("password", password)
                nodes.add(
                    NodeEntry(
                        tag = tag,
                        protocol = "shadowsocks",
                        server = server,
                        port = port,
                        rawJson = ob.toString()
                    )
                )
            } catch (e: Exception) {
                skipped.add("第 ${i + 1} 项解析失败：${e.message}")
            }
        }
        return FileParseResult(nodes, skipped)
    }
}
