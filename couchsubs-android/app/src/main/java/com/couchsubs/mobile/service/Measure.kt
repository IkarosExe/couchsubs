// SPDX-License-Identifier: AGPL-3.0-only
// IkarosExe fork'u (2026-11-16): bütün ölçümler tek yerde. Bağlantının her adımı (saniye), her yazı tanıma çağrısı
// (yol, boyut, ms) ve gösterilen her satır (OCR, çeviri, toplam gecikme, kaynağı) toplanır; tanı sayfası
// (http://TV:8791) bunların özetini ve son kayıtlarını verir. Hangi adımın yavaş olduğu tahmin değil ölçümle görülür.
package com.couchsubs.mobile.service

import android.os.SystemClock
import com.couchsubs.mobile.core.ShownPair

object Measure {
    private class Ocr(val mode: String, val width: Int, val height: Int, val ms: Int, val lines: Int)
    private class Line(val at: Long, val pair: ShownPair)

    private val lock = Any()
    private var startedAt = SystemClock.elapsedRealtime()
    private val timeline = ArrayList<String>()
    private val ocrCalls = ArrayDeque<Ocr>()
    private val lines = ArrayDeque<Line>()

    /** Yeni çeviri oturumu: önceki ölçümler silinir. */
    fun reset() = synchronized(lock) {
        startedAt = SystemClock.elapsedRealtime()
        timeline.clear()
        ocrCalls.clear()
        lines.clear()
        timeline.add("0,0 sn  çeviri başlatıldı")
    }

    /** Bağlantı ve kurulum adımı (başlangıçtan geçen süreyle). */
    fun mark(step: String) = synchronized(lock) {
        if (timeline.size >= 200) timeline.removeAt(1)
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        timeline.add("%.1f sn  %s".format(seconds, step).replace('.', ','))
    }

    fun ocr(mode: String, width: Int, height: Int, ms: Int, lineCount: Int) = synchronized(lock) {
        if (ocrCalls.size >= 2000) ocrCalls.removeFirst()
        ocrCalls.addLast(Ocr(mode, width, height, ms, lineCount))
    }

    fun line(pair: ShownPair) = synchronized(lock) {
        if (lines.size >= 500) lines.removeFirst()
        lines.addLast(Line(SystemClock.elapsedRealtime(), pair))
    }

    private fun stats(values: List<Int>): String {
        if (values.isEmpty()) return "-"
        val sorted = values.sorted()
        fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
        return "${sorted.size} adet · orta ${at(0.5)} · %90 ${at(0.9)} · en çok ${sorted.last()} ms"
    }

    fun report(): String = synchronized(lock) {
        buildString {
            append("=== Bağlantı ve kurulum ===\n")
            timeline.forEach { append(it).append('\n') }

            append("\n=== Yazı tanıma çağrıları (son ").append(ocrCalls.size).append(") ===\n")
            ocrCalls.groupBy { it.mode }.forEach { (mode, calls) ->
                val size = calls.groupingBy { "${it.width}x${it.height}" }.eachCount().maxByOrNull { it.value }?.key
                append(mode).append(" (sık boyut ").append(size).append("): ").append(stats(calls.map { it.ms }))
                append(" · boş okuma %").append(100 * calls.count { it.lines == 0 } / calls.size).append('\n')
            }
            append("Tümü: ").append(stats(ocrCalls.map { it.ms })).append('\n')

            append("\n=== Gösterilen satırlar (son ").append(lines.size).append(") ===\n")
            lines.groupBy { it.pair.origin }.forEach { (origin, list) ->
                append(origin).append(": ").append(list.size).append(" satır\n")
                append("  OCR: ").append(stats(list.mapNotNull { it.pair.ocrMs })).append('\n')
                append("  çeviri: ").append(stats(list.mapNotNull { it.pair.translationMs })).append('\n')
                append("  toplam gecikme: ").append(stats(list.mapNotNull { it.pair.totalMs })).append('\n')
            }
            append("\nSon 40 satır (sn · OCR/çeviri/toplam ms · kaynak · metin):\n")
            lines.toList().takeLast(40).forEach { l ->
                val p = l.pair
                append("%.1f".format((l.at - startedAt) / 1000.0).replace('.', ',')).append(" · ")
                append(p.ocrMs ?: "-").append('/').append(p.translationMs ?: "-").append('/').append(p.totalMs ?: "-")
                append(" · ").append(p.origin).append(" · ").append(p.source).append(" → ").append(p.translation).append('\n')
            }
        }
    }
}
