package com.koko123as.sboxcfg.parse

import com.koko123as.sboxcfg.model.NodeEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * WireGuard .conf 文件解析。
 * INI 分节解析直抄 Karing ConfParser（conf_parser.dart）；
 * 字段映射抄 WireguardConfUtils.tryConvert（wireguard_conf_utils.dart:52-115），
 * 直接拼 sing-box wireguard outbound（组装格式沿用 NodeParser.parseWireguard）。
 */
object WgConfParser {

    fun parse(content: String): FileParseResult {
        val sections = parseIni(content)
        val iface = sections["Interface"]
            ?: throw IllegalArgumentException("缺少 [Interface] 分节")
        val peer = sections["Peer"]
            ?: throw IllegalArgumentException("缺少 [Peer] 分节")
        val privateKey = iface["PrivateKey"] ?: ""
        val address = iface["Address"] ?: ""
        val mtu = iface["MTU"]?.toIntOrNull()
        val endpoint = peer["Endpoint"] ?: ""
        val publicKey = peer["PublicKey"] ?: ""
        val presharedKey = peer["PresharedKey"] ?: ""
        if (privateKey.isEmpty() || address.isEmpty() || endpoint.isEmpty() || publicKey.isEmpty()) {
            throw IllegalArgumentException("wg 配置缺少必要字段（PrivateKey/Address/Endpoint/PublicKey）")
        }
        val ci = endpoint.lastIndexOf(":")
        if (ci <= 0) throw IllegalArgumentException("Endpoint 格式错误：$endpoint")
        val server = endpoint.substring(0, ci).trim().removeSurrounding("[", "]")
        val port = endpoint.substring(ci + 1).trim().toIntOrNull() ?: 51820

        val ob = JSONObject()
        ob.put("type", "wireguard")
        val tag = "wireguard-$server"
        ob.put("tag", tag)
        ob.put("server", server)
        ob.put("server_port", port)
        ob.put("private_key", privateKey)
        ob.put("peer_public_key", publicKey)
        if (presharedKey.isNotEmpty()) ob.put("pre_shared_key", presharedKey)
        val addrs = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (addrs.isEmpty()) throw IllegalArgumentException("Address 格式错误")
        ob.put("local_address", JSONArray(addrs))
        ob.put("allowed_ips", JSONArray(listOf("0.0.0.0/0")))
        if (mtu != null && mtu > 0) ob.put("mtu", mtu)

        val node = NodeEntry(
            tag = tag,
            protocol = "wireguard",
            server = server,
            port = port,
            rawJson = ob.toString()
        )
        return FileParseResult(listOf(node), emptyList())
    }

    /** INI 分节解析（Karing ConfParser 逻辑） */
    fun parseIni(content: String): Map<String, Map<String, String>> {
        val config = mutableMapOf<String, Map<String, String>>()
        var currentSection = ""
        var currentItems: MutableMap<String, String>? = null
        for (rawLine in content.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                if (currentSection.isNotEmpty() && currentItems != null) {
                    config[currentSection] = currentItems
                }
                currentSection = line.substring(1, line.length - 1)
                currentItems = mutableMapOf()
            } else {
                val pos = line.indexOf("=")
                if (pos > 0) {
                    currentItems?.put(
                        line.substring(0, pos).trim(),
                        line.substring(pos + 1).trim()
                    )
                }
            }
        }
        if (currentSection.isNotEmpty() && currentItems != null) {
            config[currentSection] = currentItems
        }
        return config
    }
}
