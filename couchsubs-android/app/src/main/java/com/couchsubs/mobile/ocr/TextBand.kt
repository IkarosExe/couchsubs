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
    private const val PAD_Y = 12
    private const val MIN_H = 40
    private const val MIN_W = 96
    private const val PAD_X = 16
    private const val LINE_PAD_Y = 4
    private const val LINE_PAD_X = 8
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
        for (row in 0 until height) {
            if (rowEnergy[row] >= threshold) {
                if (first < 0) first = row
                last = row
            }
        }
        if (first < 0) return Band(0, 0, 0, 0)
        // Bütün yazı benzeri satırları kapsayan şerit. Çok büyükse (altyazının yanında parlak doku) kırpılmaz, bölgenin
        // tamamı okunur. En yoğun kümeyi seçmek denendi: parlak gökyüzü/yaprak altyazının yerine seçilip altyazı hiç
        // okunmuyordu (Horizon, 2026-11-16).
        var top = first
        var bottom = last + 1
        if ((bottom - top) > height * MAX_FRACTION) return null
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
        // ML Kit en az 32×32 ister; küçük yazının şeridi bundan küçük kalınca okuma hata verip satır kayboluyordu
        // ("InputImage width and height should be at least 32", 2026-11-16). Şerit en az MIN_W×MIN_H'ye genişletilir.
        if (bottom - top < MIN_H) {
            val grow = MIN_H - (bottom - top)
            top = maxOf(0, top - (grow + 1) / 2)
            bottom = minOf(height, top + MIN_H)
            top = maxOf(0, bottom - MIN_H) and 1.inv()
        }
        if (right - left < MIN_W) {
            val grow = MIN_W - (right - left)
            left = maxOf(0, left - (grow + 1) / 2)
            right = minOf(width, left + MIN_W)
            left = maxOf(0, right - MIN_W) and 1.inv()
        }
        // NV21 için en ve boy çift olmalı.
        val w = (right - left) and 1.inv()
        val h = (bottom - top) and 1.inv()
        val band = Band(left, top, left + w, top + h)
        if (band.width < 32 || band.height < 32) return null
        if (band.width.toLong() * band.height > width.toLong() * height * MAX_FRACTION) return null
        return band
    }

    private fun isEdge(y: ByteArray, i: Int): Boolean {
        val a = y[i].toInt() and 0xff
        val b = y[i + 2].toInt() and 0xff
        return abs(a - b) >= EDGE && maxOf(a, b) >= BRIGHT
    }

    /**
     * Şeridi [scale] kat büyütüp NV21 gri görüntü olarak verir (doğrusal ara değer). Küçük yazıda ML Kit harfleri
     * karıştırıyordu; harf başına ~16 px önerilir.
     */
    fun cropScaled(y: ByteArray, width: Int, band: Band, scale: Int): ByteArray {
        if (scale <= 1) return crop(y, width, band)
        val w = band.width * scale
        val h = band.height * scale
        val out = ByteArray(w * h * 3 / 2)
        val maxX = band.width - 1
        val maxY = band.height - 1
        for (oy in 0 until h) {
            val fy = (oy + 0.5f) / scale - 0.5f
            val y0 = fy.toInt().coerceIn(0, maxY)
            val y1 = (y0 + 1).coerceAtMost(maxY)
            val wy = (fy - y0).coerceIn(0f, 1f)
            val row0 = (band.top + y0) * width + band.left
            val row1 = (band.top + y1) * width + band.left
            for (ox in 0 until w) {
                val fx = (ox + 0.5f) / scale - 0.5f
                val x0 = fx.toInt().coerceIn(0, maxX)
                val x1 = (x0 + 1).coerceAtMost(maxX)
                val wx = (fx - x0).coerceIn(0f, 1f)
                val a = (y[row0 + x0].toInt() and 0xff) * (1 - wx) + (y[row0 + x1].toInt() and 0xff) * wx
                val b = (y[row1 + x0].toInt() and 0xff) * (1 - wx) + (y[row1 + x1].toInt() and 0xff) * wx
                out[oy * w + ox] = (a * (1 - wy) + b * wy + 0.5f).toInt().toByte()
            }
        }
        java.util.Arrays.fill(out, w * h, out.size, 128.toByte())
        return out
    }

    /**
     * [band] içindeki yazı satırlarını ayrı ayrı verir (satır okuyucu için; boşluklarla ayrılan satır kümeleri). Her
     * satır sıkı kırpılır: PaddleOCR satır okuyucusu yazının satır yüksekliğini doldurmasını istiyor, gevşek kırpmada
     * çimen gibi arka plan harflere karışıyordu (2026-11-16 denemesi).
     */
    fun lines(y: ByteArray, width: Int, height: Int, band: Band): List<Band> {
        val rows = IntArray(band.height)
        for (r in 0 until band.height) {
            val base = (band.top + r) * width
            var count = 0
            var x = band.left
            val end = minOf(band.right, width - 2)
            while (x < end) {
                if (isEdge(y, base + x)) count++
                x++
            }
            rows[r] = count
        }
        val threshold = maxOf(4, band.width / 80)
        val runs = mutableListOf<IntArray>()
        var start = -1
        var gap = 0
        for (r in 0 until band.height) {
            if (rows[r] >= threshold) {
                if (start < 0) start = r
                gap = 0
            } else if (start >= 0) {
                gap++
                // 3 satırdan kısa boşluk aynı yazı satırının içidir (harflerin ortası, noktalar).
                if (gap >= 3) {
                    runs.add(intArrayOf(start, r - gap + 1))
                    start = -1
                    gap = 0
                }
            }
        }
        if (start >= 0) runs.add(intArrayOf(start, band.height - gap))
        val result = mutableListOf<Band>()
        for (run in runs) {
            if (run[1] - run[0] < 8) continue
            val top = maxOf(0, band.top + run[0] - LINE_PAD_Y)
            val bottom = minOf(height, band.top + run[1] + LINE_PAD_Y)
            var left = width
            var right = 0
            for (row in top until bottom) {
                val base = row * width
                var x = band.left
                val end = minOf(band.right, width - 2)
                while (x < end) {
                    if (isEdge(y, base + x)) {
                        if (x < left) left = x
                        if (x + 2 > right) right = x + 2
                    }
                    x++
                }
            }
            if (right - left < 16) continue
            result.add(Band(maxOf(0, left - LINE_PAD_X), top, minOf(width, right + LINE_PAD_X), bottom))
        }
        return result
    }

    /** Bütün Y görüntüsünü [outW]×[outH]'ye küçültüp NV21 gri görüntü olarak verir (kutu ortalaması). */
    fun resize(y: ByteArray, width: Int, height: Int, outW: Int, outH: Int): ByteArray {
        val out = ByteArray(outW * outH * 3 / 2)
        for (oy in 0 until outH) {
            val y0 = oy * height / outH
            val y1 = maxOf(y0 + 1, (oy + 1) * height / outH)
            for (ox in 0 until outW) {
                val x0 = ox * width / outW
                val x1 = maxOf(x0 + 1, (ox + 1) * width / outW)
                var sum = 0
                for (yy in y0 until y1) {
                    val base = yy * width
                    for (xx in x0 until x1) sum += y[base + xx].toInt() and 0xff
                }
                out[oy * outW + ox] = (sum / ((y1 - y0) * (x1 - x0))).toByte()
            }
        }
        java.util.Arrays.fill(out, outW * outH, out.size, 128.toByte())
        return out
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
