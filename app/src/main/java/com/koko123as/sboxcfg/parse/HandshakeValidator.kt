package com.koko123as.sboxcfg.parse

import com.koko123as.sboxcfg.model.NodeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * ③ 协议握手有效性验证（v2.2）：vless / trojan（含 trojan-go，解析产物 type 均为 trojan）。
 * 在 LatencyChecker 连通性探测之上，真正发送协议握手，区分"服务器可达但认证失败"。
 *
 * 诚实边界（不糊弄）：
 * - vmess（AEAD 握手复杂）、ss（无握手）、hysteria/hysteria2/tuic/wireguard（UDP）、
 *   reality、grpc 等 transport → UNSUPPORTED，走原连通性探测并标注"仅测连通"。
 * - TLS 复用 LatencyChecker 的 trust-all 上下文：只验证"通不通/凭证对不对"，
 *   不建立安全连接、不传输用户数据。
 * - 不经过 detourTag：验证的是节点自身直连可达性，链式代理不参与。
 */
object HandshakeValidator {

    enum class Validity { VALID, AUTH_FAIL, UNREACHABLE, UNSUPPORTED }

    data class ValidationResult(
        val tag: String,
        val ms: Long?,
        val validity: Validity,
        val reason: String? = null // UNREACHABLE 时复用 LatencyChecker.REASON_*
    )

    /** 测试目标：www.gstatic.com:80 + /generate_204（204 无 body，轻量、中立、稳定） */
    private const val TEST_HOST = "www.gstatic.com"
    private const val TEST_PORT = 80
    private const val TEST_PATH = "/generate_204"

    // ---------------- 协议字节布局 ----------------

    /**
     * VLESS 请求头（PortThenAddress：端口在地址类型之前）：
     * Version(1B=0x00) + UUID(16B) + AddonLen(1B=0x00) + Command(1B=0x01=TCP)
     * + Port(2B BE) + AddrType(1B) + Addr(变长)
     * AddrType: 0x01=IPv4(4B), 0x02=Domain(1B len+N), 0x03=IPv6(16B)
     */
    private fun vlessRequest(uuidStr: String): ByteArray {
        val uuid = try {
            UUID.fromString(uuidStr)
        } catch (_: Exception) {
            throw IllegalArgumentException("bad uuid: $uuidStr")
        }
        val out = ByteArrayOutputStream()
        out.write(0x00) // version
        val bb = ByteBuffer.allocate(16)
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        out.write(bb.array()) // uuid 16B
        out.write(0x00) // addon length（parseVless 未存 flow，固定 0）
        out.write(0x01) // command: TCP
        out.write(TEST_PORT shr 8)
        out.write(TEST_PORT and 0xFF)
        out.write(0x02) // addr type: domain
        val hb = TEST_HOST.toByteArray(Charsets.US_ASCII)
        out.write(hb.size)
        out.write(hb)
        return out.toByteArray()
    }

    /**
     * Trojan 请求：hex(SHA224(password)) 56B ASCII + CRLF + Cmd(1B=0x01)
     * + SOCKS5 地址（ATYP 0x01=IPv4 / 0x03=Domain / 0x04=IPv6）+ CRLF
     */
    private fun trojanHello(password: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-224")
        val hex = md.digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val out = ByteArrayOutputStream()
        out.write(hex.toByteArray(Charsets.US_ASCII)) // 56B
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.write(0x01) // cmd: CONNECT
        out.write(0x03) // atyp: domain
        val hb = TEST_HOST.toByteArray(Charsets.US_ASCII)
        out.write(hb.size)
        out.write(hb)
        out.write(TEST_PORT shr 8)
        out.write(TEST_PORT and 0xFF)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun httpGet(): ByteArray =
        "GET $TEST_PATH HTTP/1.1\r\nHost: $TEST_HOST\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("eof reading $n bytes")
            off += r
        }
        return buf
    }

    /** 读到出现 "HTTP/" 或 EOF/上限为止 */
    private fun readUntilHttp(input: InputStream, cap: Int = 8192): String {
        val sb = StringBuilder()
        val buf = ByteArray(1024)
        while (sb.length < cap) {
            val r = input.read(buf)
            if (r < 0) break
            sb.append(String(buf, 0, r, Charsets.US_ASCII))
            if (sb.contains("HTTP/")) break
        }
        return sb.toString()
    }

    // ---------------- 最小 WebSocket 客户端（标准库，RFC 6455） ----------------

    private class WsConn(private val socket: Socket) {
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()

        fun upgrade(path: String, hostHeader: String) {
            val key = Base64.getEncoder().encodeToString(
                ByteArray(16).also { SecureRandom().nextBytes(it) }
            )
            val req = "GET $path HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
            output.write(req.toByteArray(Charsets.US_ASCII))
            output.flush()
            val statusLine = readHttpLine()
            if (!statusLine.contains("101")) throw IOException("ws upgrade failed: $statusLine")
            while (true) { // 消费剩余响应头
                if (readHttpLine().isEmpty()) break
            }
        }

        private fun readHttpLine(): String {
            val sb = StringBuilder()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("eof in http header")
                if (prev == '\r'.code && b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                prev = b
            }
            return sb.toString()
        }

        /** 发 binary 帧（客户端帧必须 mask） */
        fun sendBinary(payload: ByteArray) {
            val out = ByteArrayOutputStream()
            out.write(0x82) // FIN + binary
            val maskKey = ByteArray(4).also { SecureRandom().nextBytes(it) }
            writeLength(out, payload.size, masked = true)
            out.write(maskKey)
            for (i in payload.indices) out.write(payload[i].toInt() xor (maskKey[i % 4].toInt() and 0xFF))
            output.write(out.toByteArray())
            output.flush()
        }

        private fun writeLength(out: ByteArrayOutputStream, len: Int, masked: Boolean) {
            val m = if (masked) 0x80 else 0x00
            when {
                len < 126 -> out.write(m or len)
                len < 65536 -> {
                    out.write(m or 126)
                    out.write(len shr 8)
                    out.write(len and 0xFF)
                }
                else -> {
                    out.write(m or 127)
                    for (i in 7 downTo 0) out.write((len shr (i * 8)) and 0xFF)
                }
            }
        }

        /** 收完整消息（拼接 FIN 分片；服务端帧不 mask） */
        fun recvMessage(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val b0 = input.read()
                if (b0 < 0) throw EOFException("eof in ws frame")
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                if (opcode == 0x08) throw EOFException("ws closed by peer")
                val b1 = input.read()
                if (b1 < 0) throw EOFException("eof in ws frame")
                var len = (b1 and 0x7F).toLong()
                when (len) {
                    126L -> {
                        val e = readN(input, 2)
                        len = (((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF)).toLong()
                    }
                    127L -> {
                        len = 0
                        for (b in readN(input, 8)) len = (len shl 8) or (b.toLong() and 0xFF)
                    }
                }
                if (b1 and 0x80 != 0) readN(input, 4) // 服务端帧理论上不 mask，防御性跳过
                out.write(readN(input, len.toInt()))
                if (fin) return out.toByteArray()
            }
        }
    }

    // ---------------- 主流程 ----------------

    suspend fun validate(node: NodeEntry, timeoutMs: Int = 8000): ValidationResult =
        withContext(Dispatchers.IO) {
            val raw = try {
                JSONObject(node.rawJson)
            } catch (_: Exception) {
                return@withContext ValidationResult(node.tag, null, Validity.UNREACHABLE, LatencyChecker.REASON_UNKNOWN)
            }
            val proto = raw.optString("type", node.protocol).lowercase()
            val transport = raw.optJSONObject("transport")
            val transportType = transport?.optString("type", "")?.lowercase() ?: ""
            val tlsObj = raw.optJSONObject("tls")
            val reality = tlsObj?.optJSONObject("reality")?.optBoolean("enabled", false) == true
            val useTls = LatencyChecker.hasTls(node.rawJson)
            val host = raw.optString("server", node.server).ifEmpty { node.server }
            val port = raw.optInt("server_port", node.port).let { if (it > 0) it else node.port }
            // v2.2.1：SNI 取 outbound 的 tls.server_name（CF 类裸 IP 节点靠它路由），传给建连层
            val serverName = LatencyChecker.tlsServerName(node.rawJson)

            // 不支持验证的 → 连通性探测 + UNSUPPORTED（诚实标注，不冒充）
            val unsupported: Boolean = when {
                proto != "vless" && proto != "trojan" -> true
                reality -> true
                transportType.isNotEmpty() && transportType != "ws" -> true
                else -> false
            }
            if (unsupported) {
                val p = LatencyChecker.probe(node.tag, host, port, useTls, timeoutMs, serverName)
                return@withContext ValidationResult(node.tag, p.ms, Validity.UNSUPPORTED, p.reason)
            }

            val start = System.currentTimeMillis()
            val ms = { System.currentTimeMillis() - start }
            var socket: Socket? = null
            try {
                socket = LatencyChecker.openBaseSocket(host, port, useTls, timeoutMs, serverName)
                if (transportType == "ws") {
                    val ws = WsConn(socket)
                    val path = transport?.optString("path", "")?.ifEmpty { "/" } ?: "/"
                    val hostHeader = transport?.optJSONObject("headers")?.optString("Host", "")
                        ?.ifEmpty { host } ?: host
                    try {
                        ws.upgrade(path, hostHeader)
                    } catch (_: Exception) {
                        return@withContext ValidationResult(node.tag, ms(), Validity.AUTH_FAIL, null)
                    }
                    return@withContext if (proto == "vless") {
                        val req = try {
                            vlessRequest(raw.optString("uuid", ""))
                        } catch (_: Exception) {
                            return@withContext ValidationResult(node.tag, ms(), Validity.UNSUPPORTED, null)
                        }
                        ws.sendBinary(req)
                        val resp = try {
                            ws.recvMessage()
                        } catch (_: Exception) {
                            return@withContext ValidationResult(node.tag, ms(), Validity.AUTH_FAIL, null)
                        }
                        if (resp.size >= 2 && resp[0] == 0.toByte() && resp[1] == 0.toByte())
                            ValidationResult(node.tag, ms(), Validity.VALID, null)
                        else ValidationResult(node.tag, ms(), Validity.AUTH_FAIL, null)
                    } else {
                        ws.sendBinary(trojanHello(raw.optString("password", "")))
                        ws.sendBinary(httpGet())
                        val resp = try {
                            ws.recvMessage()
                        } catch (_: Exception) {
                            return@withContext ValidationResult(node.tag, ms(), Validity.AUTH_FAIL, null)
                        }
                        if (String(resp, Charsets.US_ASCII).contains("HTTP/"))
                            ValidationResult(node.tag, ms(), Validity.VALID, null)
                        else ValidationResult(node.tag, ms(), Validity.AUTH_FAIL, null)
                    }
                } else {
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    return@withContext if (proto == "vless") {
                        val req = try {
                            vlessRequest(raw.optString("uuid", ""))
                        } catch (_: Exception) {
                            return@withContext ValidationResult(node.tag, ms(), Validity.UNSUPPORTED, null)
                        }
                        output.write(req)
                        output.flush()
                        // VLESS 响应头：Version(1B 回显 0x00) + AddonLen(1B)；认证成功后立即发送
                        val resp = try {
                            readN(input, 2)
                        } catch (_: Exception) {
                            return@withContext ValidationResult(node.tag, ms(), Validity.AUTH_FAIL, null)
                        }
                        if (resp[0] == 0.toByte() && resp[1] == 0.toByte())
                            ValidationResult(node.tag, ms(), Validity.VALID, null)
                        else ValidationResult(node.tag, ms(), Validity.AUTH_FAIL, null)
                    } else {
                        output.write(trojanHello(raw.optString("password", "")))
                        output.flush()
                        output.write(httpGet())
                        output.flush()
                        val text = try {
                            readUntilHttp(input)
                        } catch (_: Exception) {
                            return@withContext ValidationResult(node.tag, ms(), Validity.AUTH_FAIL, null)
                        }
                        if (text.contains("HTTP/"))
                            ValidationResult(node.tag, ms(), Validity.VALID, null)
                        else ValidationResult(node.tag, ms(), Validity.AUTH_FAIL, null)
                    }
                }
            } catch (_: UnknownHostException) {
                ValidationResult(node.tag, null, Validity.UNREACHABLE, LatencyChecker.REASON_DNS)
            } catch (_: SocketTimeoutException) {
                ValidationResult(node.tag, null, Validity.UNREACHABLE, LatencyChecker.REASON_TIMEOUT)
            } catch (_: ConnectException) {
                ValidationResult(node.tag, null, Validity.UNREACHABLE, LatencyChecker.REASON_REFUSED)
            } catch (_: javax.net.ssl.SSLException) {
                ValidationResult(node.tag, null, Validity.UNREACHABLE, LatencyChecker.REASON_TLS)
            } catch (_: Exception) {
                ValidationResult(node.tag, null, Validity.UNREACHABLE, LatencyChecker.REASON_UNKNOWN)
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }

    /** 批量验证（并发 10，与 probeAll 同模式） */
    suspend fun validateAll(
        nodes: List<NodeEntry>,
        timeoutMs: Int = 8000,
        parallelism: Int = 10
    ): List<ValidationResult> = coroutineScope {
        val sem = Semaphore(parallelism)
        nodes.map { n ->
            async {
                sem.acquire()
                try {
                    validate(n, timeoutMs)
                } finally {
                    sem.release()
                }
            }
        }.awaitAll()
    }
}
