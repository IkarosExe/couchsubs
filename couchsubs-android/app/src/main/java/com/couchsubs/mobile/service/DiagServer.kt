// SPDX-License-Identifier: AGPL-3.0-only
// IkarosExe fork'u (2026-11-16): TV'de ADB olmadan tanı. Çeviri sürerken yerel ağda küçük bir sayfa açılır
// (http://TV:8791): anlık durum satırı ve uygulamanın kendi kayıtları (logcat, yalnız bu süreç). Bilgisayarın
// tarayıcısında açılıp kaydedilebilir. Yalnız okuma: sayfa hiçbir şey değiştirmez.
package com.couchsubs.mobile.service

import android.os.Process
import android.util.Log
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class DiagServer(private val summary: () -> String) {
    @Volatile private var server: ServerSocket? = null

    fun start(): Boolean = try {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(PORT))
        server = socket
        thread(name = "CouchSubs diag", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { client.use { serve(it) } }
            }
        }
        true
    } catch (error: Exception) {
        Log.w(TAG, "Tanı sayfası açılamadı", error)
        false
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            // İsteğin yalnız ilk satırı okunur (GET /).
            socket.getInputStream().bufferedReader().readLine() ?: return
            val text = buildString {
                append("CouchSubs TV tanı\n")
                append(summary()).append("\n\n")
                append("=== Kayıtlar (son 800 satır) ===\n")
                append(logs())
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            val out = socket.getOutputStream()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (error: Exception) {
            Log.w(TAG, "Tanı isteği", error)
        }
    }

    private fun logs(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "800", "--pid", Process.myPid().toString())
            .redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().also { process.waitFor() }
    } catch (error: Exception) {
        "logcat okunamadı: ${error.message}"
    }

    companion object {
        private const val TAG = "CouchSubsDiag"
        const val PORT = 8791
    }
}
