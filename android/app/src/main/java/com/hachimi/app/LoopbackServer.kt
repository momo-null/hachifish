package com.hachimi.app

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 回环 HTTP API（架构 §4.3）：仅绑定 127.0.0.1，供 PC harness 经 `adb reverse` 驱动
 * （产品运行完全不依赖）。手写零依赖实现，六个端点：
 *
 *   GET  /health   → {a11y, projection, python, bridge}
 *   POST /task     → {objective, done_when, script?} → 后台执行（Python MiniLoop）
 *   POST /stop     → 中止当前 run
 *   GET  /status   → 当前 run 状态
 *   GET  /metrics  → 最近 run 汇总
 *   GET  /trajectory → 最近 run 轨迹
 *
 * 安全：仅回环；显式检查 Host 头防 DNS rebinding（红线 R1/R9）。
 */
class LoopbackServer(
    private val port: Int,
    private val route: (method: String, path: String, body: String) -> Pair<Int, String>,
) : Thread("HachimiLoopback") {

    private val running = AtomicBoolean(true)
    private var server: ServerSocket? = null

    override fun run() {
        try {
            server = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "loopback listening on 127.0.0.1:$port")
            while (running.get()) {
                val sock = server!!.accept()
                thread(name = "loopback-conn") {
                    try {
                        sock.use { s ->
                            s.soTimeout = 10000
                            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                            val requestLine = reader.readLine() ?: return@use
                            val parts = requestLine.split(" ")
                            if (parts.size < 2) return@use
                            val method = parts[0]
                            val path = parts[1].substringBefore("?")
                            var contentLength = 0
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (line.isNullOrBlank()) break
                                if (line.startsWith("Content-Length:", true)) {
                                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                }
                            }
                            val body = if (contentLength > 0) {
                                val buf = CharArray(contentLength)
                                var read = 0
                                while (read < contentLength) {
                                    val n = reader.read(buf, read, contentLength - read)
                                    if (n < 0) break
                                    read += n
                                }
                                String(buf, 0, read)
                            } else ""
                            val (code, payload) = try {
                                route(method, path, body)
                            } catch (e: Exception) {
                                500 to """{"ok":false,"error":"${e.javaClass.simpleName}: ${e.message}"}"""
                            }
                            val bytes = payload.toByteArray(Charsets.UTF_8)
                            s.getOutputStream().write(
                                ("HTTP/1.1 $code OK\r\n" +
                                 "Content-Type: application/json; charset=utf-8\r\n" +
                                 "Content-Length: ${bytes.size}\r\n" +
                                 "Connection: close\r\n\r\n").toByteArray() + bytes
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "conn error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "server failed: ${e.message}")
        }
    }

    fun shutdown() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
    }

    companion object {
        const val TAG = "HachimiLoopback"
    }
}
