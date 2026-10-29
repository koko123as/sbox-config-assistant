package com.koko123as.sboxcfg.parse

import com.koko123as.sboxcfg.model.NodeEntry
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml

/**
 * Clash YAML 解析：只转 `proxies:` 列表（proxy-groups/rules 忽略，我们自己生成分流）。
 * 各类型字段映射抄 Karing clash_to_singbox.dart 的 convertXxx（直写 JSONObject 风格）。
 * snell / ssr → 标注跳过（sing-box 官方客户端不支持）。
 */
object ClashParser {

    fun parse(yaml: String): FileParseResult {
        val nodes = mutableListOf<NodeEntry>()
        val skipped = mutableListOf<String>()
        val usedTags = mutableSetOf<String>()
        val doc = try {
            Yaml().load<Map<String, Any?>>(yaml)
        } catch (e: Exception) {
            throw IllegalArgumentException("YAML 解析失败：${e.message}")
        } ?: throw IllegalArgumentException("YAML 内容为空")
        val proxies = doc["proxies"] as? List<*>
            ?: throw IllegalArgumentException("未找到 proxies 列表")
        if (proxies.isEmpty()) throw IllegalArgumentException("proxies 列表为空")
        for (p in proxies) {
            val m = p as? Map<*, *> ?: continue
            val name = m["name"]?.toString() ?: ""
            try {
                val ob = convertProxy(m)
                var tag = ob.optString("tag", "").ifEmpty { name }
                if (tag.isEmpty()) tag = "节点-${nodes.size + 1}"
                tag = NodeParser.dedupeTag(tag, usedTags)
                ob.put("tag", tag)
                nodes.add(
                    NodeEntry(
                        tag = tag,
                        protocol = ob.getString("type"),
                        server = ob.optString("server", ""),
                        port = ob.optInt("server_port", 0),
                        rawJson = ob.toString()
                    )
                )
            } catch (e: NodeParser.NodeSkippedException) {
                skipped.add(short(name) + " → " + (e.message ?: "已跳过"))
            } catch (e: Exception) {
                skipped.add(short(name) + " → " + (e.message ?: "解析失败"))
            }
        }
        return FileParseResult(nodes, skipped)
    }

    private fun short(s: String) = if (s.length > 50) s.take(50) + "…" else s.ifEmpty { "（未命名）" }

    // ---------------- 内部工具 ----------------

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.str(k: String): String = (this[k] as? String) ?: ""

    private fun Map<*, *>.int(k: String, d: Int): Int =
        (this[k] as? Number)?.toInt() ?: (this[k] as? String)?.toIntOrNull() ?: d

    private fun Map<*, *>.bool(k: String): Boolean =
        (this[k] as? Boolean) ?: (this[k] as? String)?.let {
            it == "true" || it == "1"
        } ?: false

    private fun clashTls(m: Map<*, *>, server: String): JSONObject {
        val tls = JSONObject()
        tls.put("enabled", true)
        tls.put("server_name", m.str("sni").ifEmpty { m.str("servername").ifEmpty { server } })
        tls.put("insecure", m.bool("skip-cert-verify"))
        val fp = m.str("fingerprint")
        val utls = JSONObject()
        utls.put("enabled", true)
        utls.put("fingerprint", fp.ifEmpty { "chrome" })
        tls.put("utls", utls)
        val alpn = m["alpn"]
        val alpnList = when (alpn) {
            is List<*> -> alpn.mapNotNull { it?.toString() }
            is String -> alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        if (alpnList.isNotEmpty()) tls.put("alpn", JSONArray(alpnList))
        return tls
    }

    @Suppress("UNCHECKED_CAST")
    private fun transportFromNetwork(m: Map<*, *>, server: String): JSONObject? {
        return when (m.str("network").lowercase()) {
            "ws" -> {
                val opts = m["ws-opts"] as? Map<*, *> ?: emptyMap<String, Any>()
                val t = JSONObject()
                t.put("type", "ws")
                t.put("path", opts.str("path").ifEmpty { "/" })
                val headers = JSONObject()
                val h = opts["headers"] as? Map<*, *>
                headers.put("Host", h?.str("Host")?.ifEmpty { null } ?: server)
                t.put("headers", headers)
                t
            }
            "grpc" -> {
                val opts = m["grpc-opts"] as? Map<*, *> ?: emptyMap<String, Any>()
                val t = JSONObject()
                t.put("type", "grpc")
                t.put("service_name", opts.str("grpc-service-name"))
                t
            }
            "h2" -> {
                val opts = m["h2-opts"] as? Map<*, *> ?: emptyMap<String, Any>()
                val t = JSONObject()
                t.put("type", "http")
                val hosts = opts["host"] as? List<*> ?: emptyList<Any>()
                t.put("host", JSONArray(if (hosts.isEmpty()) listOf(server) else hosts.map { it.toString() }))
                t.put("path", opts.str("path").ifEmpty { "/" })
                t.put("method", "GET")
                t
            }
            "http" -> {
                val opts = m["http-opts"] as? Map<*, *> ?: emptyMap<String, Any>()
                val t = JSONObject()
                t.put("type", "http")
                t.put("method", opts.str("method").ifEmpty { "GET" })
                t.put("path", opts.str("path").ifEmpty { "/" })
                t
            }
            else -> null
        }
    }

    private fun convertProxy(m: Map<*, *>): JSONObject {
        val type = m.str("type").lowercase()
        val name = m.str("name")
        val server = m.str("server")
        if (server.isEmpty()) throw IllegalArgumentException("缺少 server")
        val port = m.int("port", 0)
        val ob = JSONObject()
        ob.put("tag", name)
        ob.put("server", server)
        ob.put("server_port", port)
        when (type) {
            "ss", "shadowsocks" -> {
                ob.put("type", "shadowsocks")
                val method = m.str("cipher")
                val password = m.str("password")
                if (method.isEmpty() || password.isEmpty()) throw IllegalArgumentException("ss 缺少 cipher/password")
                ob.put("method", method)
                ob.put("password", password)
            }
            "ssr", "shadowsocksr" ->
                throw NodeParser.NodeSkippedException("ssr 协议不受 sing-box 官方客户端支持，已跳过")
            "snell" ->
                throw NodeParser.NodeSkippedException("snell 协议不受 sing-box 支持，已跳过")
            "vmess" -> {
                ob.put("type", "vmess")
                val uuid = m.str("uuid")
                if (uuid.isEmpty()) throw IllegalArgumentException("vmess 缺少 uuid")
                ob.put("uuid", uuid)
                ob.put("security", m.str("cipher").ifEmpty { "auto" })
                ob.put("alter_id", m.int("alterId", 0))
                if (m.bool("tls")) ob.put("tls", clashTls(m, server))
                transportFromNetwork(m, server)?.let { ob.put("transport", it) }
            }
            "vless" -> {
                ob.put("type", "vless")
                val uuid = m.str("uuid")
                if (uuid.isEmpty()) throw IllegalArgumentException("vless 缺少 uuid")
                ob.put("uuid", uuid)
                m.str("flow").ifEmpty { null }?.let { ob.put("flow", it) }
                if (m.bool("tls")) ob.put("tls", clashTls(m, server))
                transportFromNetwork(m, server)?.let { ob.put("transport", it) }
            }
            "trojan" -> {
                ob.put("type", "trojan")
                val password = m.str("password")
                if (password.isEmpty()) throw IllegalArgumentException("trojan 缺少 password")
                ob.put("password", password)
                ob.put("tls", clashTls(m, server))
                transportFromNetwork(m, server)?.let { ob.put("transport", it) }
            }
            "socks", "socks5" -> {
                ob.put("type", "socks")
                ob.put("version", "5")
                m.str("username").ifEmpty { null }?.let { ob.put("username", it) }
                m.str("password").ifEmpty { null }?.let { ob.put("password", it) }
                if (m.bool("tls")) ob.put("tls", clashTls(m, server))
            }
            "http" -> {
                ob.put("type", "http")
                m.str("username").ifEmpty { null }?.let { ob.put("username", it) }
                m.str("password").ifEmpty { null }?.let { ob.put("password", it) }
                if (m.bool("tls")) ob.put("tls", clashTls(m, server))
            }
            "hysteria" -> {
                ob.put("type", "hysteria")
                val auth = m.str("auth")
                val authStr = m.str("auth_str")
                if (auth.isNotEmpty()) ob.put("auth", auth)
                else if (authStr.isNotEmpty()) ob.put("auth_str", authStr)
                m.str("obfs").ifEmpty { null }?.let { ob.put("obfs", it) }
                val proto = m.str("protocol")
                if (proto.isNotEmpty() && !proto.equals("udp", true))
                    throw NodeParser.NodeSkippedException("hysteria 仅支持 udp 协议，已跳过")
                hysteriaSpeed(m, "up", ob, "up")
                hysteriaSpeed(m, "down", ob, "down")
                m.int("recv-window", 0).let { if (it != 0) ob.put("recv_window", it) }
                m.int("recv-window-conn", 0).let { if (it != 0) ob.put("recv_window_conn", it) }
                if (m.bool("disable-mtu-discovery")) ob.put("disable_mtu_discovery", true)
                val tls = clashTls(m, server)
                if (!tls.has("alpn")) tls.put("alpn", JSONArray(listOf("h3")))
                ob.put("tls", tls)
            }
            "hysteria2" -> {
                ob.put("type", "hysteria2")
                val password = m.str("password")
                if (password.isEmpty()) throw IllegalArgumentException("hysteria2 缺少 password")
                ob.put("password", password)
                hysteriaSpeed(m, "up", ob, "up_mbps", intOnly = true)
                hysteriaSpeed(m, "down", ob, "down_mbps", intOnly = true)
                val obfs = m.str("obfs")
                val obfsPw = m.str("obfs-password")
                if (obfsPw.isNotEmpty()) {
                    if (obfs.isNotEmpty() && obfs != "salamander")
                        throw NodeParser.NodeSkippedException("hysteria2 不支持的 obfs 类型 [$obfs]，已跳过")
                    val o = JSONObject()
                    o.put("type", obfs.ifEmpty { "salamander" })
                    o.put("password", obfsPw)
                    ob.put("obfs", o)
                }
                ob.put("tls", clashTls(m, server))
            }
            "tuic" -> {
                ob.put("type", "tuic")
                val uuid = m.str("uuid")
                if (uuid.isEmpty()) throw IllegalArgumentException("tuic 缺少 uuid")
                ob.put("uuid", uuid)
                m.str("password").ifEmpty { null }?.let { ob.put("password", it) }
                m.str("congestion-controller").ifEmpty { null }?.let { ob.put("congestion_control", it) }
                m.str("udp-relay-mode").ifEmpty { null }?.let { ob.put("udp_relay_mode", it) }
                if (m.bool("reduce-rtt")) ob.put("zero_rtt_handshake", true)
                m.int("heartbeat", 0).let { if (it > 0) ob.put("heartbeat", "${it}s") }
                ob.put("tls", clashTls(m, server))
            }
            "wireguard", "wg" -> {
                ob.put("type", "wireguard")
                val privateKey = m.str("private-key")
                val publicKey = m.str("public-key")
                if (privateKey.isEmpty() || publicKey.isEmpty())
                    throw IllegalArgumentException("wireguard 缺少 private-key/public-key")
                ob.put("private_key", privateKey)
                ob.put("peer_public_key", publicKey)
                m.str("pre-shared-key").ifEmpty { null }?.let { ob.put("pre_shared_key", it) }
                val addrs = mutableListOf<String>()
                m.str("ip").ifEmpty { null }?.let {
                    addrs.add(if (it.contains("/")) it else "$it/32")
                }
                m.str("ipv6").ifEmpty { null }?.let {
                    addrs.add(if (it.contains("/")) it else "$it/128")
                }
                ob.put("local_address", JSONArray(if (addrs.isEmpty()) listOf("172.16.0.2/32") else addrs))
                m.str("allowed-ips").ifEmpty { null }?.let {
                    ob.put("allowed_ips", JSONArray(it.split(",").map { s -> s.trim() }))
                } ?: ob.put("allowed_ips", JSONArray(listOf("0.0.0.0/0")))
                m.int("mtu", 0).let { if (it > 0) ob.put("mtu", it) }
                if (!m.bool("udp")) ob.put("network", "tcp")
            }
            "ssh" -> {
                ob.put("type", "ssh")
                val user = m.str("username").ifEmpty { m.str("user") }
                if (user.isEmpty()) throw IllegalArgumentException("ssh 缺少用户名")
                ob.put("user", user)
                m.str("password").ifEmpty { null }?.let { ob.put("password", it) }
                m.str("private-key").ifEmpty { null }?.let { ob.put("private_key", it) }
            }
            else -> throw IllegalArgumentException("不支持的 clash 类型: $type")
        }
        return ob
    }

    /** hysteria 速率：clash 的 up/down 可能是 "50" 或 "50 Mbps" */
    private fun hysteriaSpeed(m: Map<*, *>, key: String, ob: JSONObject, outKey: String, intOnly: Boolean = false) {
        val v = m.str(key)
        if (v.isEmpty()) return
        val numPart = v.replace(Regex("[^0-9]"), "")
        val n = numPart.toIntOrNull() ?: return
        if (v.contains("bps", true) && !intOnly) {
            ob.put(outKey, v.replace(Regex("\\s+"), " ").trim())
        } else {
            ob.put(if (intOnly) outKey else "${outKey}_mbps", n)
        }
    }
}
