package com.koko123as.sboxcfg.parse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * ⑧ 延迟检测：TCP＋TLS 握手测延迟（标准库实现，无第三方依赖）。
 * - TCP：Socket.connect 计时；
 * - TLS：若 outbound 带 tls.enabled，做 SSLSocket 握手（含 SNI）计时。
 * 注意：UDP 系协议（hysteria/hysteria2/tuic）TCP 探测只是连通性参考，UI 会标注"仅供参考"。
 * 结果不持久化（防过期误导），每次现测；只标注不自动删节点。
 *
 * v2.1 修复误报（真机上可连节点被判"不通"）：
 * 1. 探测专用 SSLContext（trust-all）：只测延迟、不建安全连接、不传用户数据，
 *    证书自签/链不全不再导致误判；
 * 2. InetAddress.getAllByName 拿全部解析 IP 逐个试（IPv6 优先但网络不通时自动换 IPv4），
 *    之前只试第一个 IP，IPv6 不通即超时；
 * 3. 失败原因细分，UI 可显示具体原因。
 */
object LatencyChecker {

    /** 失败原因码 */
    const val REASON_DNS = "dns_fail"       // 域名解析失败
    const val REASON_TIMEOUT = "timeout"    // 连接超时
    const val REASON_REFUSED = "refused"    // 连接被拒绝
    const val REASON_TLS = "tls_fail"       // TCP 通但 TLS 握手失败
    const val REASON_UNKNOWN = "unknown"    // 未知错误

    /** null = 通（无失败原因） */
    data class ProbeResult(val tag: String, val ms: Long?, val tls: Boolean, val reason: String? = null)

    /** 原因码 → 中文文案（UI 显示用） */
    fun reasonText(reason: String?): String = when (reason) {
        REASON_DNS -> "DNS解析失败"
        REASON_TIMEOUT -> "超时"
        REASON_REFUSED -> "连接被拒绝"
        REASON_TLS -> "TLS握手失败"
        else -> "未知错误"
    }

    /** UDP 系协议集合（TCP 探测仅供参考） */
    val UDP_PROTOCOLS = setOf("hysteria", "hysteria2", "tuic", "wireguard")

    /**
     * 探测专用 TrustManager：信任所有证书。
     * 仅用于延迟探测（测"通不通/多快"），不建立真正的安全连接、不传输任何用户数据，
     * 因此证书有效性与探测目的无关，必须绕过，否则自签/链不全的节点会被误判为不通。
     * internal：供测试验证 trust-all 确实生效。
     */
    internal object ProbeTrustManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    }

    /** 探测专用 SSLContext：使用上面的 trust-all manager。internal 供 HandshakeValidator 复用。 */
    internal val probeSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(ProbeTrustManager), SecureRandom())
        }
    }

    /** 判断 host 是否为 IP 字面量（IPv4/IPv6）：SNI 不允许填 IP，必须跳过设置。internal 供复用。 */
    internal fun isIpLiteral(host: String): Boolean {
        if (':' in host) return true // IPv6
        return host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))
    }

    /**
     * v2.2.1 SNI 三档（抽取为 internal 纯函数，便于单测）：
     * 1. serverName 非空且非 IP → 用 serverName（CF 类裸 IP 节点靠它路由）；
     * 2. 否则 host 非 IP → 用 host；
     * 3. 否则跳过（SNIHostName 不接受 IP 字面量，会抛 IllegalArgumentException）。
     */
    internal fun applySni(s: SSLSocket, host: String, serverName: String?) {
        val sniHost = when {
            !serverName.isNullOrBlank() && !isIpLiteral(serverName.trim()) -> serverName.trim()
            !isIpLiteral(host) -> host
            else -> null
        }
        if (sniHost != null) {
            val params = s.sslParameters
            params.serverNames = listOf(SNIHostName(sniHost))
            s.sslParameters = params
        }
    }

    /**
     * 基础建连（v2.2 抽取，供 HandshakeValidator 复用）：
     * DNS 解析全部 IP 逐个试，返回已连通的 Socket（TLS 则握手已完成，trust-all）。
     * 失败时抛异常：UnknownHostException=DNS 失败，SocketTimeoutException=超时，
     * ConnectException=拒绝，SSLException=TLS 失败。调用方负责 close。
     *
     * v2.2.1 SNI 三档：优先用 serverName（outbound 的 tls.server_name，CF 类裸 IP 节点
     * 靠它告诉服务端路由到哪个站）；无 serverName 时 host 是域名则用 host；
     * IP 字面量且无 server_name 才跳过 SNI（SNIHostName 不接受 IP，会抛 IllegalArgumentException）。
     */
    @Throws(Exception::class)
    internal suspend fun openBaseSocket(
        host: String,
        port: Int,
        useTls: Boolean,
        timeoutMs: Int = 5000,
        serverName: String? = null
    ): Socket = withContext(Dispatchers.IO) {
        val addrs: Array<InetAddress> = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            throw e
        }
        if (addrs.isEmpty()) throw UnknownHostException(host)
        val perTryMs = minOf(timeoutMs, 3000)
        var lastEx: Exception = SocketTimeoutException("all addresses failed: $host")
        for (addr in addrs) {
            try {
                if (useTls) {
                    val s = probeSslContext.socketFactory.createSocket() as SSLSocket
                    try {
                        // v2.2.1：SNI 三档逻辑见 applySni
                        applySni(s, host, serverName)
                        s.connect(InetSocketAddress(addr, port), perTryMs)
                        s.soTimeout = perTryMs
                        s.startHandshake()
                        return@withContext s
                    } catch (e: Exception) {
                        try {
                            s.close()
                        } catch (_: Exception) {
                        }
                        throw e
                    }
                } else {
                    val s = Socket()
                    try {
                        s.connect(InetSocketAddress(addr, port), perTryMs)
                        s.soTimeout = perTryMs
                        return@withContext s
                    } catch (e: Exception) {
                        try {
                            s.close()
                        } catch (_: Exception) {
                        }
                        throw e
                    }
                }
            } catch (e: SocketTimeoutException) {
                lastEx = e
            } catch (e: ConnectException) {
                lastEx = e
            } catch (e: javax.net.ssl.SSLException) {
                lastEx = e
            } catch (e: Exception) {
                lastEx = e
            }
        }
        throw lastEx
    }

    /** null ms = 不通，reason 说明原因（建连逻辑复用 openBaseSocket） */
    suspend fun probe(
        tag: String,
        host: String,
        port: Int,
        useTls: Boolean,
        timeoutMs: Int = 5000,
        serverName: String? = null
    ): ProbeResult = withContext(Dispatchers.IO) {
        if (host.isEmpty() || port <= 0) return@withContext ProbeResult(tag, null, useTls, REASON_UNKNOWN)
        val start = System.currentTimeMillis()
        try {
            openBaseSocket(host, port, useTls, timeoutMs, serverName).close()
            ProbeResult(tag, System.currentTimeMillis() - start, useTls, null)
        } catch (_: UnknownHostException) {
            ProbeResult(tag, null, useTls, REASON_DNS)
        } catch (_: SocketTimeoutException) {
            ProbeResult(tag, null, useTls, REASON_TIMEOUT)
        } catch (_: ConnectException) {
            ProbeResult(tag, null, useTls, REASON_REFUSED)
        } catch (_: javax.net.ssl.SSLException) {
            // TCP 已通，但 TLS 握手失败
            ProbeResult(tag, null, useTls, REASON_TLS)
        } catch (_: Exception) {
            ProbeResult(tag, null, useTls, REASON_UNKNOWN)
        }
    }

    /** 批量探测目标（含可选 SNI server_name） */
    data class ProbeTarget(val tag: String, val host: String, val port: Int, val serverName: String? = null)

    /** 批量探测（并发 10）。rawJsons: tag → outbound JSON */
    suspend fun probeAll(
        targets: List<ProbeTarget>,
        tlsTags: Set<String>,
        timeoutMs: Int = 5000,
        parallelism: Int = 10
    ): List<ProbeResult> = coroutineScope {
        val sem = Semaphore(parallelism)
        targets.map { t ->
            async {
                sem.acquire()
                try {
                    probe(t.tag, t.host, t.port, tlsTags.contains(t.tag), timeoutMs, t.serverName)
                } finally {
                    sem.release()
                }
            }
        }.awaitAll()
    }

    /** 从 outbound JSON 判断是否启用 TLS */
    fun hasTls(rawJson: String): Boolean = try {
        JSONObject(rawJson).optJSONObject("tls")?.optBoolean("enabled", false) == true
    } catch (_: Exception) {
        false
    }

    /** 从 outbound JSON 取 tls.server_name（SNI 用）；空白或缺失则 null */
    fun tlsServerName(rawJson: String): String? = try {
        JSONObject(rawJson).optJSONObject("tls")?.optString("server_name")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
