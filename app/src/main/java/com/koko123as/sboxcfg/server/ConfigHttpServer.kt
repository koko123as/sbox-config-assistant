package com.koko123as.sboxcfg.server

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ⑤ 本地 HTTP 服务（v2.2）：把 App 生成的配置变成可远程更新的链接。
 * 手写 ServerSocket 实现，无第三方依赖。
 *
 * - 只绑定 127.0.0.1（本机回环；局域网不可达，是有意为之的安全选择）；
 * - 端口从 startPort 起顺延（18080→18089），全被占用抛 IOException；
 * - GET /config.json：每次请求现场调用 buildJson()，SFA 拿到的永远是 App 当前最新配置；
 * - 请求头上限 8KB（防滥用）；其他路径 404；异常时 500 + {"error":"build failed"}。
 *
 * 限制（已在 UI 文案说明）：无前台保活，Android 杀后台后 SFA 刷新会失败，属预期行为。
 */
class ConfigHttpServer(
    private val buildJson: () -> String,
    private val startPort: Int = 18080
) {
    @Volatile
    var isRunning = false
        private set

    private var thread: Thread? = null
    private var serverSocket: ServerSocket? = null

    var port: Int = -1
        private set

    val url: String get() = "http://127.0.0.1:$port/config.json"

    /** 供测试断言绑定地址确为回环 */
    internal val boundAddress: InetAddress?
        get() = try {
            serverSocket?.inetAddress
        } catch (_: Exception) {
            null
        }

    /** 启动服务，返回实际端口；18080~18089 全被占用时抛 IOException */
    @Throws(IOException::class)
    fun start(): Int {
        if (isRunning) return port
        var lastEx: IOException? = null
        for (p in startPort..startPort + 9) {
            try {
                serverSocket = ServerSocket(p, 50, InetAddress.getByName("127.0.0.1"))
                port = p
                lastEx = null
                break
            } catch (e: IOException) {
                lastEx = e
            }
        }
        val ss = serverSocket ?: throw (lastEx ?: IOException("cannot bind 127.0.0.1:$startPort"))
        isRunning = true
        thread = thread(isDaemon = true, name = "ConfigHttpServer") {
            while (isRunning) {
                try {
                    val s = ss.accept()
                    thread(isDaemon = true) { handle(s) }
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }
        return port
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        port = -1
    }

    private fun handle(s: Socket) {
        try {
            s.use { sock ->
                sock.soTimeout = 5000
                val inp = sock.getInputStream()
                val out = sock.getOutputStream()
                // 读请求头（上限 8KB）
                val hdr = StringBuilder()
                val one = ByteArray(1)
                while (hdr.length < 8192) {
                    val r = inp.read(one)
                    if (r < 0) break
                    hdr.append(one[0].toInt().toChar())
                    if (hdr.endsWith("\r\n\r\n")) break
                }
                val requestLine = hdr.toString().lineSequence().firstOrNull()?.trim() ?: ""
                // 只认 GET /config.json（允许 ?query 后缀）
                val isConfig = requestLine.startsWith("GET ") &&
                        requestLine.removePrefix("GET ").substringBefore(" ")
                            .substringBefore("?") == "/config.json"
                if (isConfig) {
                    val (body, status) = try {
                        buildJson().toByteArray(Charsets.UTF_8) to "200 OK"
                    } catch (_: Exception) {
                        "{\"error\":\"build failed\"}".toByteArray(Charsets.UTF_8) to "500 Internal Server Error"
                    }
                    val head = "HTTP/1.1 $status\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.US_ASCII))
                    out.write(body)
                } else {
                    out.write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                }
                out.flush()
            }
        } catch (_: Exception) {
        }
    }
}
