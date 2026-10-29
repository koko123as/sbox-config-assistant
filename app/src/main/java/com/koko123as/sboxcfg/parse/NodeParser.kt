package com.koko123as.sboxcfg.parse

import com.koko123as.sboxcfg.model.NodeEntry
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * 节点链接解析：一次到位，直接产出完整 sing-box outbound dict（Karing 的 raw 模式）。
 * URI 支持：vless / vmess / ss / trojan / trojan-go / hysteria(hy v1) / hysteria2(hy2) /
 * tuic / wireguard(wg) / socks(socks5) / http(s 代理) / ssh / anytls。
 * ssr:// 识别但标注"不受 sing-box 官方客户端支持"跳过（官方构建无 with_shadowsocksr 标签）。
 * v2 新增：skipped（跳过的行＋原因，与 errors 区分）、内容嗅探链、文件导入入口。
 */
object NodeParser {

    /** 文件导入类型（Karing：先选类型再过滤扩展名，默认自动识别） */
    enum class ImportType { AUTO, CLASH, SINGBOX, V2RAY, WIREGUARD }

    data class ParseResult(
        val nodes: List<NodeEntry>,
        val errors: List<String>,
        val skipped: List<String> = emptyList()
    )

    /** 逐行跳过（坏行/不支持的协议），不进 errors */
    class NodeSkippedException(msg: String) : Exception(msg)

    /** 整内容文档检测：命中返回非 null（供 parseText / parseFile(AUTO) 共用） */
    private fun tryParseDocument(t: String): ParseResult? {
        val tt = t.trim()
        if (tt.isEmpty()) return ParseResult(emptyList(), emptyList(), emptyList())
        if (tt.startsWith("{")) {
            try {
                return viaFileParser { SingboxJsonParser.parse(tt) }
            } catch (_: Exception) {
            }
            try {
                return viaFileParser { SsJsonParser.parse(tt) }
            } catch (_: Exception) {
            }
            return ParseResult(emptyList(), listOf("JSON 内容无法识别（不是 sing-box 配置也不是 ss 订阅）"), emptyList())
        }
        if (tt.contains("[Interface]")) return viaFileParser { WgConfParser.parse(tt) }
        if (Regex("(?m)^proxies:").containsMatchIn(tt)) return viaFileParser { ClashParser.parse(tt) }
        return null
    }

    /**
     * 解析粘贴文本：三级——①整内容文档嗅探 ②整块 base64 解码后递归 ③逐行。
     * depth 防止双重 base64 死循环；调用点签名不变（depth 有默认值）。
     */
    fun parseText(text: String, depth: Int = 0): ParseResult {
        val t = text.trim()
        if (t.isEmpty()) return ParseResult(emptyList(), emptyList(), emptyList())
        tryParseDocument(t)?.let { return it }
        if (depth < 2) {
            try {
                val dec = b64Decode(t).trim()
                // 解码后仍是 base64 块（双重编码）也继续递归，depth 守卫防死循环
                if (dec != t && (dec.contains("://") || Regex("(?m)^proxies:").containsMatchIn(dec) ||
                            dec.startsWith("{") || isB64Blob(dec))
                )
                    return parseText(dec, depth + 1)
            } catch (_: Exception) {
            }
        }
        return parseTextLines(t)
    }

    /** 单行 base64 块（双重编码的中间层）：足长、无空白、纯 base64 字符 */
    private fun isB64Blob(s: String): Boolean {
        val t = s.trim()
        return t.length >= 32 && !t.contains(Regex("\\s")) &&
                t.matches(Regex("^[A-Za-z0-9+/=_-]+$"))
    }

    /** 解析粘贴文本（多行链接 / base64 订阅 / 明文订阅 / sing-box JSON） */
    private fun parseTextLines(text: String): ParseResult {
        val nodes = mutableListOf<NodeEntry>()
        val errors = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val usedTags = mutableSetOf<String>()
        for (rawLine in text.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if ((line.startsWith("http://", true) || line.startsWith("https://", true)) &&
                !looksLikeHttpProxy(line)
            ) {
                errors.add(short(line) + " → 这是订阅 URL，请用「拉取订阅」按钮获取")
                continue
            }
            expandContent(line, nodes, errors, skipped, usedTags)
        }
        return ParseResult(nodes, errors, skipped)
    }

    /** http(s) 代理链接 vs 订阅 URL：带 userinfo（user@）的当作代理链接 */
    private fun looksLikeHttpProxy(line: String): Boolean {
        val after = line.substringAfter("://")
        val hostPart = after.substringBefore("?").substringBefore("#").substringBefore("/")
        return hostPart.contains("@")
    }

    /** 文件导入：按类型分发；AUTO 走嗅探链（Karing _tryconvertFromContentExcept 顺序） */
    fun parseFile(content: String, type: ImportType): ParseResult {
        val usedTags = mutableSetOf<String>()
        return when (type) {
            ImportType.CLASH -> viaFileParser { ClashParser.parse(content) }
            ImportType.SINGBOX -> viaFileParser { SingboxJsonParser.parse(content) }
            ImportType.V2RAY -> {
                val r = parseText(content)
                ParseResult(r.nodes, r.errors, r.skipped)
            }
            ImportType.WIREGUARD -> viaFileParser { WgConfParser.parse(content) }
            ImportType.AUTO -> sniffParse(content, usedTags)
        }
    }

    private fun viaFileParser(p: () -> FileParseResult): ParseResult {
        return try {
            val r = p()
            ParseResult(r.nodes, emptyList(), r.skipped)
        } catch (e: Exception) {
            ParseResult(emptyList(), listOf("文件解析失败：${e.message}"), emptyList())
        }
    }

    /** 嗅探链：整内容文档检测命中即用，否则逐行（与 parseText 同一入口收敛） */
    private fun sniffParse(content: String, usedTags: MutableSet<String>): ParseResult {
        val t = content.trim()
        if (t.isEmpty()) return ParseResult(emptyList(), listOf("文件内容为空"), emptyList())
        return tryParseDocument(t) ?: parseTextLines(t)
    }

    /** 拉取订阅 URL 内容 */
    fun fetchSubscription(urlStr: String, timeoutMs: Int = 20000): String {
        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "SBoxCfg/2.0")
        val code = conn.responseCode
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }

    // ---------------- 内部 ----------------

    private fun short(s: String) = if (s.length > 60) s.take(60) + "…" else s

    fun b64Decode(s: String): String {
        var t = s.trim().replace("-", "+").replace("_", "/").replace("\\s".toRegex(), "")
        val pad = t.length % 4
        if (pad > 0) t += "=".repeat(4 - pad)
        return String(Base64.getDecoder().decode(t), Charsets.UTF_8)
    }

    private fun urlDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun num(v: String?, d: Int): Int = v?.toIntOrNull() ?: d

    private fun splitHostPort(hp: String): Pair<String, String> {
        val t = hp.trim()
        val m = Regex("^\\[([^\\]]+)\\]:(\\d+)$").matchEntire(t)
        if (m != null) return m.groupValues[1] to m.groupValues[2]
        val i = t.lastIndexOf(":")
        if (i > 0) return t.substring(0, i) to t.substring(i + 1)
        return t to ""
    }

    private data class LinkParts(
        val userinfo: String,
        val hostPort: String,
        val query: Map<String, String>,
        val fragment: String
    )

    private fun parseQueryString(qs: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (qs.isEmpty()) return map
        for (kv in qs.split("&")) {
            if (kv.isEmpty()) continue
            val ei = kv.indexOf("=")
            if (ei >= 0) map[urlDecode(kv.substring(0, ei))] = urlDecode(kv.substring(ei + 1))
            else map[urlDecode(kv)] = ""
        }
        return map
    }

    private fun splitLink(u: String): LinkParts {
        var s = u
        var fragment = ""
        val hi = s.lastIndexOf('#')
        if (hi >= 0) {
            fragment = s.substring(hi + 1)
            s = s.substring(0, hi)
        }
        var queryStr = ""
        val qi = s.indexOf('?')
        if (qi >= 0) {
            queryStr = s.substring(qi + 1)
            s = s.substring(0, qi)
        }
        val afterScheme = s.substringAfter("://")
        var userinfo = ""
        var hostPort = afterScheme
        val ai = afterScheme.lastIndexOf('@')
        if (ai >= 0) {
            userinfo = afterScheme.substring(0, ai)
            hostPort = afterScheme.substring(ai + 1)
        }
        return LinkParts(userinfo, hostPort, parseQueryString(queryStr), fragment)
    }

    private fun tlsFromParams(p: Map<String, String>, host: String, hysteria: Boolean = false): JSONObject {
        val sni = p["sni"]?.ifEmpty { null } ?: host
        val fp = p["fp"] ?: ""
        val alpn = p["alpn"] ?: ""
        val insecure = p["insecure"] == "1" || p["insecure"] == "true" || p["allowInsecure"] == "1"
        val tls = JSONObject()
        tls.put("enabled", true)
        tls.put("server_name", sni)
        tls.put("insecure", insecure)
        if (alpn.isNotEmpty()) tls.put("alpn", org.json.JSONArray(alpn.split(",")))
        else if (hysteria) tls.put("alpn", org.json.JSONArray(listOf("h3")))
        val utls = JSONObject()
        utls.put("enabled", true)
        utls.put("fingerprint", fp.ifEmpty { "chrome" })
        tls.put("utls", utls)
        return tls
    }

    private fun wsTransport(p: Map<String, String>, host: String): JSONObject {
        val t = JSONObject()
        t.put("type", "ws")
        t.put("path", p["path"]?.ifEmpty { null } ?: "/")
        val headers = JSONObject()
        headers.put("Host", p["host"]?.ifEmpty { null } ?: p["sni"]?.ifEmpty { null } ?: host)
        t.put("headers", headers)
        return t
    }

    fun dedupeTag(t: String, used: MutableSet<String>): String {
        if (used.add(t)) return t
        var i = 2
        while (!used.add("$t-$i")) i++
        return "$t-$i"
    }

    // ---------------- 各协议 ----------------

    private fun parseVless(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "vless")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 443))
        val uuid = urlDecode(p.userinfo)
        if (uuid.isEmpty()) throw IllegalArgumentException("vless 缺少 uuid")
        ob.put("uuid", uuid)
        val q = p.query
        val sec = (q["security"] ?: "none").lowercase()
        if (sec == "tls" || sec == "reality") {
            val tls = tlsFromParams(q, server)
            if (sec == "reality") {
                val reality = JSONObject()
                reality.put("enabled", true)
                reality.put("public_key", q["pbk"] ?: "")
                reality.put("short_id", q["sid"] ?: "")
                tls.put("reality", reality)
            }
            ob.put("tls", tls)
        }
        when ((q["type"] ?: "tcp").lowercase()) {
            "ws" -> ob.put("transport", wsTransport(q, server))
            "grpc" -> {
                val t = JSONObject()
                t.put("type", "grpc")
                t.put("service_name", q["serviceName"] ?: "")
                ob.put("transport", t)
            }
            "httpupgrade" -> {
                val t = JSONObject()
                t.put("type", "httpupgrade")
                t.put("path", q["path"]?.ifEmpty { null } ?: "/")
                val h = JSONObject()
                h.put("Host", q["host"]?.ifEmpty { null } ?: q["sni"]?.ifEmpty { null } ?: server)
                t.put("headers", h)
                ob.put("transport", t)
            }
            "http" -> {
                val t = JSONObject()
                t.put("type", "http")
                t.put("host", org.json.JSONArray(listOf(q["host"]?.ifEmpty { null } ?: q["sni"]?.ifEmpty { null } ?: server)))
                t.put("path", q["path"]?.ifEmpty { null } ?: "/")
                t.put("method", "GET")
                ob.put("transport", t)
            }
        }
        if ((q["headerType"] ?: "").lowercase() == "http" && !ob.has("transport")) {
            val t = JSONObject()
            t.put("type", "http")
            t.put("host", org.json.JSONArray(listOf(q["host"]?.ifEmpty { null } ?: q["sni"]?.ifEmpty { null } ?: server)))
            t.put("path", q["path"]?.ifEmpty { null } ?: "/")
            t.put("method", "GET")
            ob.put("transport", t)
        }
        q["flow"]?.ifEmpty { null }?.let { ob.put("flow", it) }
        return ob
    }

    private fun parseVmess(u: String): JSONObject {
        val body = u.substringAfter("://")
        val head = body.substringBefore("#").substringBefore("?")
        if (!body.contains("@") && !body.contains("?") && head.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            // JSON 形式
            var b = body
            var tag = ""
            val hi = b.indexOf("#")
            if (hi >= 0) {
                tag = urlDecode(b.substring(hi + 1))
                b = b.substring(0, hi)
            }
            val j = JSONObject(b64Decode(b))
            val ob = JSONObject()
            ob.put("type", "vmess")
            ob.put("tag", tag.ifEmpty { j.optString("ps", "") })
            val server = j.optString("add", "")
            ob.put("server", server)
            ob.put("server_port", num(j.optString("port", ""), 443))
            val uuid = j.optString("id", "")
            if (uuid.isEmpty() || server.isEmpty()) throw IllegalArgumentException("vmess 缺少 uuid 或地址")
            ob.put("uuid", uuid)
            ob.put("security", "auto")
            ob.put("alter_id", num(j.optString("aid", ""), 0))
            if (j.optString("tls", "").lowercase() == "tls") {
                val tls = JSONObject()
                tls.put("enabled", true)
                tls.put("server_name", j.optString("sni", "").ifEmpty { server })
                tls.put("insecure", false)
                val utls = JSONObject()
                utls.put("enabled", true)
                utls.put("fingerprint", j.optString("fp", "").ifEmpty { "chrome" })
                tls.put("utls", utls)
                j.optString("alpn", "").ifEmpty { null }?.let {
                    tls.put("alpn", org.json.JSONArray(it.split(",")))
                }
                ob.put("tls", tls)
            }
            when (j.optString("net", "tcp").lowercase()) {
                "ws" -> {
                    val t = JSONObject()
                    t.put("type", "ws")
                    t.put("path", j.optString("path", "").ifEmpty { "/" })
                    val h = JSONObject()
                    h.put("Host", j.optString("host", "").ifEmpty { j.optString("sni", "").ifEmpty { server } })
                    t.put("headers", h)
                    ob.put("transport", t)
                }
                "grpc" -> {
                    val t = JSONObject()
                    t.put("type", "grpc")
                    t.put("service_name", j.optString("path", ""))
                    ob.put("transport", t)
                }
                "h2" -> {
                    val t = JSONObject()
                    t.put("type", "http")
                    t.put("host", org.json.JSONArray(listOf(j.optString("host", "").ifEmpty { server })))
                    t.put("path", j.optString("path", "").ifEmpty { "/" })
                    t.put("method", "GET")
                    ob.put("transport", t)
                }
            }
            return ob
        }
        // URL 形式
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "vmess")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 443))
        val uuid = urlDecode(p.userinfo)
        if (uuid.isEmpty()) throw IllegalArgumentException("vmess 缺少 uuid")
        ob.put("uuid", uuid)
        val q = p.query
        ob.put("security", q["encryption"]?.ifEmpty { null } ?: "auto")
        ob.put("alter_id", num(q["aid"] ?: q["alterId"], 0))
        if ((q["security"] ?: "").lowercase() == "tls") ob.put("tls", tlsFromParams(q, server))
        when ((q["type"] ?: "tcp").lowercase()) {
            "ws" -> ob.put("transport", wsTransport(q, server))
            "grpc" -> {
                val t = JSONObject()
                t.put("type", "grpc")
                t.put("service_name", q["serviceName"] ?: "")
                ob.put("transport", t)
            }
        }
        return ob
    }

    private fun parseSS(u: String): JSONObject {
        var body = u.substringAfter("://")
        var tag = ""
        val hi = body.indexOf("#")
        if (hi >= 0) {
            tag = urlDecode(body.substring(hi + 1))
            body = body.substring(0, hi)
        }
        var method = ""
        var password = ""
        var server = ""
        var port = ""
        if (body.contains("@")) {
            val at = body.lastIndexOf("@")
            var ui = body.substring(0, at)
            val hp = body.substring(at + 1)
            if (!ui.contains(":")) {
                ui = try {
                    b64Decode(ui)
                } catch (_: Exception) {
                    throw IllegalArgumentException("ss 用户信息解码失败")
                }
            }
            val ci = ui.indexOf(":")
            if (ci < 0) throw IllegalArgumentException("ss 用户信息格式错误")
            method = ui.substring(0, ci)
            password = urlDecode(ui.substring(ci + 1))
            val (s, pt) = splitHostPort(hp)
            server = s
            port = pt
        } else {
            val d = b64Decode(body)
            val m = Regex("^([^:]+):([^@]+)@(.+):(\\d+)$").matchEntire(d)
                ?: throw IllegalArgumentException("ss 链接格式错误")
            method = m.groupValues[1]
            password = urlDecode(m.groupValues[2])
            server = m.groupValues[3]
            port = m.groupValues[4]
        }
        if (server.isEmpty() || method.isEmpty()) throw IllegalArgumentException("ss 缺少服务器或加密方式")
        val ob = JSONObject()
        ob.put("type", "shadowsocks")
        ob.put("tag", tag)
        ob.put("server", server)
        ob.put("server_port", num(port.ifEmpty { null }, 8388))
        ob.put("method", method)
        ob.put("password", password)
        return ob
    }

    /** ssr:// 识别但跳过：sing-box 官方客户端无 with_shadowsocksr 编译标签，跑不起来 */
    private fun parseSSR(u: String): JSONObject {
        throw NodeSkippedException("ssr 协议不受 sing-box 官方客户端支持，已跳过")
    }

    private fun parseTrojan(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "trojan")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 443))
        val password = urlDecode(p.userinfo)
        if (password.isEmpty()) throw IllegalArgumentException("trojan 缺少密码")
        ob.put("password", password)
        ob.put("tls", tlsFromParams(p.query, server))
        when ((p.query["type"] ?: "tcp").lowercase()) {
            "ws" -> ob.put("transport", wsTransport(p.query, server))
            "grpc" -> {
                val t = JSONObject()
                t.put("type", "grpc")
                t.put("service_name", p.query["serviceName"] ?: "")
                ob.put("transport", t)
            }
        }
        return ob
    }

    /**
     * trojan-go://（字段映射抄 Karing _parseTrojanGo）→ sing-box trojan。
     * security=none 时不加 tls；ws 的 ed（early-data）参数做简化处理。
     */
    private fun parseTrojanGo(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "trojan")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 443))
        val password = urlDecode(p.userinfo)
        if (password.isEmpty()) throw IllegalArgumentException("trojan-go 缺少密码")
        ob.put("password", password)
        val q = p.query
        if ((q["security"] ?: "").lowercase() != "none") {
            ob.put("tls", tlsFromParams(q, server))
        }
        when ((q["type"] ?: "tcp").lowercase()) {
            "ws" -> {
                var path = q["path"]?.ifEmpty { null } ?: "/"
                var ed = 0
                // early-data 简化：path?ed=N → max_early_data
                val qi = path.indexOf("?")
                if (qi >= 0) {
                    val pq = parseQueryString(path.substring(qi + 1))
                    ed = pq["ed"]?.toIntOrNull() ?: 0
                    path = path.substring(0, qi).ifEmpty { "/" }
                }
                val t = JSONObject()
                t.put("type", "ws")
                t.put("path", path)
                if (ed > 0) {
                    t.put("max_early_data", ed)
                    t.put("early_data_header_name", "Sec-WebSocket-Protocol")
                }
                val headers = JSONObject()
                headers.put("Host", q["host"]?.ifEmpty { null } ?: q["sni"]?.ifEmpty { null } ?: server)
                t.put("headers", headers)
                ob.put("transport", t)
            }
            "grpc" -> {
                val t = JSONObject()
                t.put("type", "grpc")
                t.put("service_name", q["serviceName"] ?: "")
                ob.put("transport", t)
            }
        }
        return ob
    }

    /**
     * hysteria v1（hy://、hysteria://），字段映射抄 Karing _parseHysteria
     * ＋ clash_to_singbox convertHysteria。
     * 注意：sing-box hysteria v1 不支持 hop_ports，mport/ports 直接丢弃。
     */
    private fun parseHysteria(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "hysteria")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        if (server.isEmpty()) throw IllegalArgumentException("hysteria 缺少服务器")
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 443))
        val q = p.query
        // auth：能 base64 解码→auth，否则→auth_str（Karing 逻辑）
        val auth = q["auth"] ?: ""
        if (auth.isNotEmpty()) {
            val isB64 = try {
                b64Decode(auth); true
            } catch (_: Exception) {
                false
            }
            if (isB64) ob.put("auth", auth) else ob.put("auth_str", auth)
        }
        val up = q["upmbps"] ?: q["up"] ?: ""
        val down = q["downmbps"] ?: q["down"] ?: ""
        if (up.contains("ps", true)) ob.put("up", up.replace("ps", "", true))
        else up.toIntOrNull()?.let { if (it > 0) ob.put("up_mbps", it) }
        if (down.contains("ps", true)) ob.put("down", down.replace("ps", "", true))
        else down.toIntOrNull()?.let { if (it > 0) ob.put("down_mbps", it) }
        q["obfs"]?.ifEmpty { null }?.let { ob.put("obfs", it) }
        val proto = q["protocol"] ?: ""
        if (proto.isNotEmpty() && !proto.equals("udp", true))
            throw NodeSkippedException("hysteria 仅支持 udp 协议，已跳过")
        q["recvwindow"]?.toIntOrNull()?.let { if (it != 0) ob.put("recv_window", it) }
        q["recvwindowconn"]?.toIntOrNull()?.let { if (it != 0) ob.put("recv_window_conn", it) }
        if (q["disablemtudiscovery"] == "1" || q["disablemtudiscovery"] == "true")
            ob.put("disable_mtu_discovery", true)
        val tls = JSONObject()
        tls.put("enabled", true)
        tls.put("server_name", q["peer"]?.ifEmpty { null } ?: q["sni"]?.ifEmpty { null } ?: server)
        tls.put("insecure", q["insecure"] == "1" || q["insecure"] == "true")
        val alpn = q["alpn"] ?: ""
        if (alpn.isNotEmpty()) tls.put("alpn", org.json.JSONArray(alpn.split(",")))
        else tls.put("alpn", org.json.JSONArray(listOf("h3")))
        val utls = JSONObject()
        utls.put("enabled", true)
        utls.put("fingerprint", q["fp"]?.ifEmpty { null } ?: "chrome")
        tls.put("utls", utls)
        ob.put("tls", tls)
        return ob
    }

    private fun parseHysteria2(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "hysteria2")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 443))
        val password = urlDecode(p.userinfo)
        if (password.isEmpty()) throw IllegalArgumentException("hysteria2 缺少密码")
        ob.put("password", password)
        ob.put("tls", tlsFromParams(p.query, server, true))
        val q = p.query
        val up = num(q["up"] ?: q["upmbps"], 0)
        val down = num(q["down"] ?: q["downmbps"], 0)
        if (up > 0) ob.put("up_mbps", up)
        if (down > 0) ob.put("down_mbps", down)
        val obfs = q["obfs"]
        if (!obfs.isNullOrEmpty() && obfs.lowercase() != "none") {
            val o = JSONObject()
            o.put("type", obfs)
            o.put("password", q["obfs-password"] ?: q["obfsPassword"] ?: "")
            ob.put("obfs", o)
        }
        return ob
    }

    private fun parseTuic(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "tuic")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 443))
        val ui = p.userinfo
        val ci = ui.indexOf(":")
        val uuid = urlDecode(if (ci >= 0) ui.substring(0, ci) else ui)
        val password = urlDecode(if (ci >= 0) ui.substring(ci + 1) else "")
        if (uuid.isEmpty()) throw IllegalArgumentException("tuic 缺少 uuid")
        ob.put("uuid", uuid)
        ob.put("password", password)
        ob.put("tls", tlsFromParams(p.query, server))
        p.query["congestion_control"]?.ifEmpty { null }?.let { ob.put("congestion_control", it) }
        p.query["udp_relay_mode"]?.ifEmpty { null }?.let { ob.put("udp_relay_mode", it) }
        return ob
    }

    private fun parseWireguard(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "wireguard")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        if (server.isEmpty()) throw IllegalArgumentException("wireguard 缺少服务器")
        val port = num(portStr.ifEmpty { null }, 51820)
        ob.put("server", server)
        ob.put("server_port", port)
        val q = p.query
        val privateKey = urlDecode(p.userinfo)
        if (privateKey.isEmpty() && (q["privateKey"] ?: q["private-key"]).isNullOrEmpty())
            throw IllegalArgumentException("wireguard 缺少 private_key")
        if (privateKey.isNotEmpty()) ob.put("private_key", privateKey)
        else ob.put("private_key", q["privateKey"] ?: q["private-key"] ?: "")
        val peerKey = q["publicKey"] ?: q["public-key"] ?: q["peer"] ?: ""
        if (peerKey.isEmpty()) throw IllegalArgumentException("wireguard 缺少 peer 公钥")
        ob.put("peer_public_key", peerKey)
        (q["presharedKey"] ?: q["pre-shared-key"] ?: q["preshared-key"])?.ifEmpty { null }?.let {
            ob.put("pre_shared_key", it)
        }
        val localAddr = q["address"] ?: q["local_address"] ?: ""
        ob.put(
            "local_address",
            org.json.JSONArray(if (localAddr.isEmpty()) listOf("172.16.0.2/32") else localAddr.split(","))
        )
        val allowed = q["allowedIps"] ?: q["allowed_ips"] ?: ""
        ob.put(
            "allowed_ips",
            org.json.JSONArray(if (allowed.isEmpty()) listOf("0.0.0.0/0") else allowed.split(","))
        )
        (q["mtu"])?.toIntOrNull()?.let { ob.put("mtu", it) }
        return ob
    }

    private fun parseSocks(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "socks")
        ob.put("version", "5")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        if (server.isEmpty()) throw IllegalArgumentException("socks 缺少服务器")
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 1080))
        if (p.userinfo.isNotEmpty()) {
            val ci = p.userinfo.indexOf(":")
            if (ci >= 0) {
                ob.put("username", urlDecode(p.userinfo.substring(0, ci)))
                ob.put("password", urlDecode(p.userinfo.substring(ci + 1)))
            } else {
                ob.put("username", urlDecode(p.userinfo))
            }
        }
        return ob
    }

    /** http(s)://user:pass@host:port 代理链接 → sing-box http（schema 已用 check 验证） */
    private fun parseHttp(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "http")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        if (server.isEmpty()) throw IllegalArgumentException("http 缺少服务器")
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 8080))
        if (p.userinfo.isNotEmpty()) {
            val ci = p.userinfo.indexOf(":")
            if (ci >= 0) {
                ob.put("username", urlDecode(p.userinfo.substring(0, ci)))
                ob.put("password", urlDecode(p.userinfo.substring(ci + 1)))
            } else {
                ob.put("username", urlDecode(p.userinfo))
            }
        }
        if (u.startsWith("https://", true)) {
            val tls = JSONObject()
            tls.put("enabled", true)
            tls.put("server_name", server)
            tls.put("insecure", false)
            ob.put("tls", tls)
        }
        return ob
    }

    /**
     * ssh:// 链接（字段映射抄 Karing _parseSSH）→ sing-box ssh。
     * 注意 sing-box 的用户名字段是 `user`（已用 check 验证，user_name/username 都非法）。
     */
    private fun parseSSH(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "ssh")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        if (server.isEmpty() || !server.contains(".")) throw IllegalArgumentException("ssh 缺少服务器")
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 22))
        val ui = p.userinfo
        // userinfo 三种形态：user / user:pass / 整段 base64（user:pass 或 user:0:私钥）
        var user = ""
        var password = ""
        var pkFromBlob = ""
        val ci = ui.indexOf(":")
        val looksB64 = ui.matches(Regex("^[A-Za-z0-9+/=_-]+$")) && ui.length > 32
        if (ci >= 0 && !looksB64) {
            user = urlDecode(ui.substring(0, ci))
            password = ui.substring(ci + 1)
        } else if (looksB64) {
            try {
                val d = b64Decode(ui)
                val parts = d.split(":")
                if (parts.size >= 2) {
                    user = parts[0]
                    password = parts[1]
                    if (parts.size >= 3 && parts[2].contains("BEGIN")) {
                        pkFromBlob = parts.subList(2, parts.size).joinToString(":")
                    }
                } else {
                    user = d
                }
            } catch (_: Exception) {
                user = urlDecode(ui)
            }
        } else if (ui.isNotEmpty()) {
            user = urlDecode(ui)
        }
        if (user.isEmpty()) throw IllegalArgumentException("ssh 缺少用户名")
        ob.put("user", user)
        if (password.isNotEmpty()) ob.put("password", password)
        if (pkFromBlob.isNotEmpty()) ob.put("private_key", pkFromBlob)
        val q = p.query
        // pk：base64 私钥
        q["pk"]?.ifEmpty { null }?.let { pk ->
            try {
                ob.put("private_key", b64Decode(pk))
            } catch (_: Exception) {
                throw IllegalArgumentException("ssh 私钥 base64 解码失败")
            }
        }
        // hk：主机密钥 JSON 数组（原始链接里直接截取 [...]）
        val hkStart = u.indexOf("hk=[")
        if (hkStart >= 0) {
            val hkEnd = u.indexOf("]", hkStart)
            if (hkEnd > hkStart) {
                try {
                    val arr = org.json.JSONArray(u.substring(hkStart + 4, hkEnd + 1))
                    if (arr.length() > 0) ob.put("host_key", arr)
                } catch (_: Exception) {
                }
            }
        }
        q["cv"]?.ifEmpty { null }?.let { ob.put("client_version", it) }
        return ob
    }

    /** anytls://password@host:port/?sni= → sing-box anytls（1.12+，schema 已用 check 验证） */
    private fun parseAnytls(u: String): JSONObject {
        val p = splitLink(u)
        val ob = JSONObject()
        ob.put("type", "anytls")
        ob.put("tag", urlDecode(p.fragment))
        val (server, portStr) = splitHostPort(p.hostPort)
        if (server.isEmpty()) throw IllegalArgumentException("anytls 缺少服务器")
        ob.put("server", server)
        ob.put("server_port", num(portStr.ifEmpty { null }, 443))
        val password = urlDecode(p.userinfo)
        if (password.isEmpty()) throw IllegalArgumentException("anytls 缺少密码")
        ob.put("password", password)
        val tls = JSONObject()
        tls.put("enabled", true)
        tls.put("server_name", p.query["sni"]?.ifEmpty { null } ?: server)
        tls.put("insecure", false)
        ob.put("tls", tls)
        return ob
    }

    // ---------------- 内容展开 ----------------

    private fun expandContent(
        content: String,
        nodes: MutableList<NodeEntry>,
        errors: MutableList<String>,
        skipped: MutableList<String>,
        usedTags: MutableSet<String>
    ) {
        val t = content.trim()
        if (t.isEmpty()) return
        if (t.startsWith("proxies:") || Regex("(?m)^proxies:").containsMatchIn(t)) {
            // clash YAML：走文件解析器
            try {
                val r = ClashParser.parse(t)
                for (n in r.nodes) nodes.add(withDedupe(n, usedTags))
                skipped.addAll(r.skipped)
                if (r.nodes.isEmpty() && r.skipped.isEmpty())
                    errors.add("clash YAML 中没有可用的 proxies")
            } catch (e: Exception) {
                errors.add("clash YAML 解析失败：${e.message}")
            }
            return
        }
        if (t.startsWith("{")) {
            try {
                val j = JSONObject(t)
                if (j.has("outbounds")) {
                    // sing-box 配置：提取 outbounds
                    try {
                        val r = SingboxJsonParser.parse(t)
                        for (n in r.nodes) nodes.add(withDedupe(n, usedTags))
                        skipped.addAll(r.skipped)
                    } catch (e: Exception) {
                        errors.add("sing-box 配置解析失败：${e.message}")
                    }
                    return
                }
            } catch (_: Exception) {
            }
        }
        val compact = t.replace("\\s".toRegex(), "")
        if (!t.contains("://") && compact.length > 64 && compact.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            try {
                val decoded = b64Decode(compact)
                if (decoded.contains("://")) {
                    decoded.split("\n").forEach { l ->
                        val s = l.trim()
                        if (s.isNotEmpty()) parseOneLine(s, nodes, skipped, usedTags)
                    }
                    return
                }
            } catch (_: Exception) {
            }
        }
        var any = false
        t.split("\n").forEach { l ->
            val s = l.trim()
            if (s.isNotEmpty() && s.contains("://")) {
                any = true
                parseOneLine(s, nodes, skipped, usedTags)
            }
        }
        if (!any) errors.add(short(t) + " → 无法识别（不是节点链接也不是订阅内容）")
    }

    private fun withDedupe(n: NodeEntry, usedTags: MutableSet<String>): NodeEntry {
        var tag = n.tag.ifEmpty { "节点-${usedTags.size + 1}" }
        tag = dedupeTag(tag, usedTags)
        return n.copy(tag = tag)
    }

    private fun parseOneLine(
        line: String,
        nodes: MutableList<NodeEntry>,
        skipped: MutableList<String>,
        usedTags: MutableSet<String>
    ) {
        try {
            val scheme = line.substringBefore("://").lowercase()
            val ob: JSONObject = when (scheme) {
                "vless" -> parseVless(line)
                "vmess" -> parseVmess(line)
                "ss" -> parseSS(line)
                "ssr" -> parseSSR(line) // 内部抛 NodeSkippedException
                "trojan" -> parseTrojan(line)
                "trojan-go" -> parseTrojanGo(line)
                "hysteria2", "hy2" -> parseHysteria2(line)
                "hysteria", "hy" -> parseHysteria(line)
                "tuic" -> parseTuic(line)
                "wg", "wireguard" -> parseWireguard(line)
                "socks", "socks5" -> parseSocks(line)
                "http", "https" -> parseHttp(line)
                "ssh" -> parseSSH(line)
                "anytls" -> parseAnytls(line)
                else -> throw IllegalArgumentException("不支持的协议: $scheme")
            }
            var tag = ob.optString("tag", "")
            if (tag.isEmpty()) tag = "节点-${nodes.size + 1}"
            tag = dedupeTag(tag, usedTags)
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
        } catch (e: NodeSkippedException) {
            skipped.add(short(line) + " → " + (e.message ?: "已跳过"))
        } catch (e: Exception) {
            skipped.add(short(line) + " → " + (e.message ?: "解析失败"))
        }
    }
}

/** 文件解析器通用返回 */
data class FileParseResult(val nodes: List<NodeEntry>, val skipped: List<String>)
