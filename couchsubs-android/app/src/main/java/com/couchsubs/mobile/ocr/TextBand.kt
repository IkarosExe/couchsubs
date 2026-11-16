// SPDX-License-Identifier: AGPL-3.0-only
// IkarosExe fork'u (2026-11-16): TV'de OCR'ı hızlandırmak için bölgede yazının olduğu şeridi bulur. Yazı tanıma (ML Kit)
// süresi okunan piksel sayısıyla büyüdüğünden yalnız yazılı satırlar okunur: TV işlemcisinde ~920×250 bölge 1 sn
// sürüyordu; altyazı şeridi bunun birkaç katı küçüktür.
package com.couchsubs.mobile.ocr

import kotlin.math.abs

/** Bölgenin içindeki yazı şeridi (piksel; sağ ve alt hariç). */
data class Band(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

object TextBand {
    /**
     * Yazı kenarı: yatay komşu (2 px) parlaklık farkı en az [EDGE] ve ikisinden biri en az [BRIGHT] (beyaz/sarı altyazı,
     * gölgeli ya da kutulu). Yalnız kenar saymak dokulu sahnede (yaprak, çimen) her satırı yazı sanıyordu.
     */
    private const val EDGE = 70
    private const val BRIGHT = 185
    /** Satırlar arası bu kadar boşluk aynı şerit sayılır (iki satırlık altyazı). */
    private const val ROW_GAP = 14
    private const val PAD_Y = 12
    private const val PAD_X = 16
    /** Şerit bölgenin bundan büyük kısmını kaplıyorsa kırpmanın faydası yok (doku yoğun sahne): bölgenin tamamı okunur. */
    private const val MAX_FRACTION = 0.8

    /**
     * Y düzleminde yazı şeridini arar. null: kırpmaya gerek yok (bölgenin tamamı okunmalı). [Band] boyu 0 ise bölgede
     * yazıya benzer hiçbir şey yok: okumaya gerek yok.
     */
    fun find(y: ByteArray, width: Int, height: Int): Band? {
        val rowEnergy = IntArray(height)
        for (row in 0 until height) {
            val base = row * width
            var count = 0
            var x = 0
            while (x < width - 2) {
                if (isEdge(y, base + x)) count++
                x += 1
            }
            rowEnergy[row] = count
        }
        // Yazı satırı: kenar sayısı genişliğin ~%1,5'inden çok (tek tük parlak noktalar sayılmaz).
        val threshold = maxOf(6, width / 64)
        var first = -1
        var last = -1
        // En uzun ardışık (ROW_GAP boşluklu) satır kümesi; altyazı genelde en yoğun kümedir.
        var bestStart = -1
        var bestEnd = -1
        var bestScore = 0L
        var start = -1
        var end = -1
        var score = 0L
        var gap = 0
        for (row in 0 until height) {
            if (rowEnergy[row] >= threshold) {
                if (first < 0) first = row
                last = row
                if (start < 0 || gap > ROW_GAP) {
                    if (start >= 0 && score > bestScore) { bestScore = score; bestStart = start; bestEnd = end }
                    start = row
                    score = 0
                }
                end = row
                score += rowEnergy[row]
                gap = 0
            } else if (start >= 0) {
                gap++
            }
        }
        if (start >= 0 && score > bestScore) { bestScore = score; bestStart = start; bestEnd = end }
        if (first < 0) return Band(0, 0, 0, 0)
        // Birden çok küme varsa (altyazı + doku), hepsini kapsayan şerit çok büyükse en yoğun küme alınır.
        var top = first
        var bottom = last + 1
        if ((bottom - top) > height * MAX_FRACTION) {
            top = bestStart
            bottom = bestEnd + 1
        }
        top = maxOf(0, top - PAD_Y)
        bottom = minOf(height, bottom + PAD_Y)

        // Sütunlar: şeridin içinde kenar bulunan en sol ve en sağ sütun.
        var left = width
        var right = 0
        for (row in top until bottom) {
            val base = row * width
            var x = 0
            while (x < width - 2) {
                if (isEdge(y, base + x)) {
                    if (x < left) left = x
                    if (x + 2 > right) right = x + 2
                }
                x++
            }
        }
        if (right <= left) return null
        left = maxOf(0, left - PAD_X) and 1.inv()
        right = minOf(width, right + PAD_X)
        top = top and 1.inv()
        // NV21 için en ve boy çift olmalı.
        val w = (right - left) and 1.inv()
        val h = (bottom - top) and 1.inv()
        val band = Band(left, top, left + w, top + h)
        if (band.width < 16 || band.height < 16) return null
        if (band.width.toLong() * band.height > width.toLong() * height * MAX_FRACTION) return null
        return band
    }

    private fun isEdge(y: ByteArray, i: Int): Boolean {
        val a = y[i].toInt() and 0xff
        val b = y[i + 2].toInt() and 0xff
        return abs(a - b) >= EDGE && maxOf(a, b) >= BRIGHT
    }

    /** Y düzleminden şeridi NV21 gri görüntü olarak kopyalar (renk nötr). */
    fun crop(y: ByteArray, width: Int, band: Band): ByteArray {
        val w = band.width
        val h = band.height
        val out = ByteArray(w * h * 3 / 2)
        for (row in 0 until h) System.arraycopy(y, (band.top + row) * width + band.left, out, row * w, w)
        java.util.Arrays.fill(out, w * h, out.size, 128.toByte())
        return out
    }
}
