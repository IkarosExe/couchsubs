// SPDX-License-Identifier: AGPL-3.0-only
// IkarosExe fork'u (2026-11-15): TV'de PSN girişi telefondan yapılır. TV, yerel ağda küçük bir sayfa açar; kullanıcı
// telefonun tarayıcısında bu sayfadan Sony girişini yapar ve girişin bittiği adresi (remoteplay.dl.playstation.net/...)
// sayfaya yapıştırır. TV yalnız bu yönlendirme adresini alır; parola telefondan doğrudan Sony'ye gider.
package com.couchsubs.mobile.remoteplay

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

class PsnPhoneLink(private val onRedirect: (String) -> Unit) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var finished = false

    /** Sunucuyu açar; tarayıcıda açılacak adresi döndürür (ağ adresi bulunamazsa null). */
    fun start(): String? {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(PORT))
        server = socket
        thread(name = "psn-phone-link", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
        return lanAddress()?.let { "http://$it:$PORT" }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(client: Socket) {
        client.use { serve(it) }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            var length = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon > 0 && header.substring(0, colon).trim().equals("Content-Length", ignoreCase = true))
                    length = header.substring(colon + 1).trim().toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
            }
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val html = if (requestLine.startsWith("POST /redirect")) {
                val text = formValue(String(body, 0, read, Charsets.UTF_8), "url")
                val redirect = PsnAccount.redirectFromText(text)
                if (redirect != null && !finished) {
                    finished = true
                    onRedirect(redirect)
                    page(DONE)
                } else if (finished) page(DONE) else page(FORM, error = true)
            } else page(FORM)
            val bytes = html.toByteArray(Charsets.UTF_8)
            val out = socket.getOutputStream()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (error: Exception) {
            Log.w(TAG, "Telefon bağlantısı", error)
        }
    }

    private fun page(kind: Int, error: Boolean = false): String {
        val link = PsnAccount.authorizeUrl.replace("&", "&amp;").replace("\"", "&quot;")
        val content = if (kind == DONE) """
            <h1>Tamam ✓</h1>
            <p>Giriş TV'ye iletildi. Bu sayfayı kapatabilir, TV'ye dönebilirsin.</p>
        """ else """
            <h1>CouchSubs · TV'de PSN girişi</h1>
            <p class="step"><b>1.</b> Aşağıdaki düğmeyle Sony'nin sayfasını aç ve <b>çeviri için açtığın ikinci hesapla</b> giriş yap.</p>
            <p><a class="button" href="$link" target="_blank" rel="noopener">Sony ile giriş yap</a></p>
            <p class="step"><b>2.</b> Giriş bitince tarayıcı <code>remoteplay.dl.playstation.net</code> ile başlayan bir sayfaya gider
            (boş ya da hata sayfası olabilir, bu normal). O sayfanın adresini adres çubuğundan kopyala.</p>
            <p class="step"><b>3.</b> Bu sayfaya dön, adresi aşağıya yapıştır ve gönder.</p>
            ${if (error) "<p class=\"error\">Bu adres tanınmadı. remoteplay.dl.playstation.net ile başlayan adresin tamamını yapıştır.</p>" else ""}
            <form method="post" action="/redirect">
              <textarea name="url" rows="4" placeholder="https://remoteplay.dl.playstation.net/remoteplay/redirect?code=..."></textarea>
              <button type="submit">TV'ye gönder</button>
            </form>
        """
        return """<!DOCTYPE html><html lang="tr"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1"><title>CouchSubs PSN girişi</title>
            <style>
              body{font-family:-apple-system,Roboto,sans-serif;background:#111;color:#eee;margin:0;padding:20px;line-height:1.5}
              h1{font-size:22px} code{color:#9cf} .error{color:#f88}
              .button,button{display:block;width:100%;box-sizing:border-box;text-align:center;padding:14px;border:0;
                border-radius:10px;background:#3b82f6;color:#fff;font-size:17px;text-decoration:none;margin:8px 0}
              textarea{width:100%;box-sizing:border-box;font-size:15px;padding:10px;border-radius:10px;border:1px solid #555;
                background:#222;color:#eee}
            </style></head><body>$content</body></html>"""
    }

    companion object {
        private const val TAG = "CouchSubsPsnPhone"
        const val PORT = 8790
        private const val MAX_BODY = 16 * 1024
        private const val FORM = 0
        private const val DONE = 1

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (line.isEmpty()) null else line.toString()
                if (c == '\n'.code) return line.toString().trimEnd('\r')
                if (line.length < 8192) line.append(c.toChar())
            }
        }

        private fun formValue(body: String, name: String): String =
            body.split('&').firstNotNullOfOrNull { part ->
                val eq = part.indexOf('=')
                if (eq > 0 && part.substring(0, eq) == name) URLDecoder.decode(part.substring(eq + 1), "UTF-8") else null
            } ?: ""

        /** Yerel ağdaki IPv4 adresi (Wi-Fi ya da kablo); TV'de telefonun açacağı adres. */
        fun lanAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
