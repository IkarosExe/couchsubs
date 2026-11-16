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

class DiagServer(private val summary: () -> String, private val onForm: ((Map<String, String>) -> String)? = null) {
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
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val request = input.readLine() ?: return
            var length = 0
            while (true) {
                val header = input.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
                // Windows PowerShell 5 POST'ta "Expect: 100-continue" gönderip gövdeyi bekletiyor.
                if (header.startsWith("expect:", ignoreCase = true) && header.contains("100-continue", ignoreCase = true)) {
                    socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
            }
            // POST: ayar (ör. PowerShell'den DeepL anahtarı). Gövde "ad=değer&..." ya da yalnız "ad=değer".
            val text = if (request.startsWith("POST") && onForm != null) {
                val body = CharArray(length.coerceIn(0, 4096)).let { buf ->
                    var read = 0
                    while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
                    String(buf, 0, read)
                }
                val form = body.split('&').mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else java.net.URLDecoder.decode(part.substring(0, i), "UTF-8") to
                        java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
                }.toMap().toMutableMap()
                // Gövde "ad=değer" biçiminde değilse içindeki DeepL anahtarı biçimi aranır.
                if ("deepl" !in form) Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}(:fx)?").find(body)?.let { form["deepl"] = it.value }
                onForm.invoke(form) + " (gelen gövde ${body.length} harf)\n"
            } else buildString {
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
