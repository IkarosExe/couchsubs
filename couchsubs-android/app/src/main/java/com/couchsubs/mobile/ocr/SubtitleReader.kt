// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.ocr

import android.media.Image
import com.couchsubs.mobile.core.CropRegion
import com.couchsubs.mobile.core.Reading
import com.couchsubs.mobile.core.TextBox
import com.couchsubs.mobile.core.TextLine
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Karenin altyazı bölgesinden kopyalanmış parlaklık (Y) düzlemi: yazı tanımaya bu gider, kare hemen bırakılır. */
class RegionPixels(val nv21: ByteArray, val width: Int, val height: Int, val signature: Long)

/**
 * Altyazı bölgesini Google ML Kit (cihaz üstü, Latin modeli uygulamayla gelir) ile okur. Renk gerekmez: yalnız parlaklık
 * düzlemi kopyalanır, renk düzlemleri nötr (128) bırakılır; bu hem hızlıdır hem beyaz/sarı altyazıyı aynı okur.
 */
class SubtitleReader(
    /** TV (IkarosExe fork'u): yalnız yazı şeridi okunur, yazı yoksa okuma atlanır (bkz. [TextBand]). */
    private val cropToText: Boolean = false,
) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    /** Tanı: son okumada OCR'a giden alanın bölgeye oranı (%); şerit yoksa ya da tam okunduysa 100. */
    @Volatile var lastAreaPercent = 100
    /** Son okumanın yolu ve OCR'a giden görüntünün boyutu (ölçümler için). */
    @Volatile var lastMode = ""
    private var triedBand = false
    private var npuDoubt = false
    private var npuReads = 0
    /** Satır okuyucunun ortalama harf güveni bundan düşükse okuma ML Kit'e bırakılır. */
    private val NPU_MIN_CONFIDENCE = 0.80
    private val COMPARE_EVERY = 10
    @Volatile var lastWidth = 0
    @Volatile var lastHeight = 0
    /** Bu boydan alçak şerit 2 kat büyütülür (piksel; 1080p'de altyazı şeridi ~50-60 px, bundan alçağı küçük yazıdır). */
    private val SMALL_BAND = 48
    /** Tam bölge okumasının en büyük eni (1080p'deki %72'lik bölge 1382 px → 720p karşılığı ~920). */
    private val FULL_READ_MAX_WIDTH = 960

    companion object {
        /** YUV_420_888 karenin bölgesini NV21 gri görüntüye kopyalar (en ve boy çift sayıya yuvarlanır). */
        fun copyRegion(image: Image, region: CropRegion): RegionPixels? {
            val crop = image.cropRect
            val frameWidth = crop.width()
            val frameHeight = crop.height()
            if (frameWidth <= 0 || frameHeight <= 0) return null
            val left = (crop.left + region.left * frameWidth).toInt() and 1.inv()
            val top = (crop.top + region.top * frameHeight).toInt() and 1.inv()
            val width = ((region.right - region.left) * frameWidth).toInt().coerceAtMost(crop.right - left) and 1.inv()
            val height = ((region.bottom - region.top) * frameHeight).toInt().coerceAtMost(crop.bottom - top) and 1.inv()
            if (width < 16 || height < 16) return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val out = ByteArray(width * height * 3 / 2)
            var signature = 1125899906842597L
            val row = ByteArray(if (pixelStride == 1) width else (width - 1) * pixelStride + 1)
            for (y in 0 until height) {
                buffer.position((top + y) * rowStride + left * pixelStride)
                buffer.get(row, 0, row.size)
                val base = y * width
                if (pixelStride == 1) System.arraycopy(row, 0, out, base, width)
                else for (x in 0 until width) out[base + x] = row[x * pixelStride]
                // Kare değişmediyse yeniden okunmasın diye kaba bir imza (her 8. piksel).
                if (y % 8 == 0) for (x in 0 until width step 8) signature = 31 * signature + (out[base + x].toInt() and 0xf0)
            }
            java.util.Arrays.fill(out, width * height, out.size, 128.toByte())
            return RegionPixels(out, width, height, signature)
        }
    }

    /** Bölgeyi okur (bloklar; okuma iş parçacığında çağrılır). Satır kutuları bölgeye oranladır. */
    fun read(pixels: RegionPixels): Reading {
        triedBand = false
        npuDoubt = false
        if (cropToText) {
            val band = TextBand.find(pixels.nv21, pixels.width, pixels.height)
            if (band != null) {
                // Bölgede parlak yazı bulunamadıysa (band boş) okuma atlanmaz: boş okumalar kararlılayıcıya "altyazı kalktı"
                // dedirtip soluk yazılı satırların hiç gösterilmemesine yol açıyordu. Bölgenin tamamı okunur.
                if (band.width > 0) {
                    triedBand = true
                    // Önce yapay zekâ çipindeki satır okuyucu (hazırsa); emin değilse ML Kit okur.
                    NpuRecognizer.active?.let { npu ->
                        val lines = TextBand.lines(pixels.nv21, pixels.width, pixels.height, band)
                        if (lines.isNotEmpty()) {
                            val result = try { npu.read(pixels.nv21, pixels.width, pixels.height, lines) } catch (error: Exception) {
                                android.util.Log.w("CouchSubsNpu", "okuma", error); null
                            }
                            if (result != null && result.reading.lines.isNotEmpty() && result.confidence >= NPU_MIN_CONFIDENCE) {
                                lastMode = "NPU ${npu.pathName}"
                                lastWidth = NpuRecognizer.W
                                lastHeight = lines.size
                                lastAreaPercent = (100L * band.width * band.height / (pixels.width.toLong() * pixels.height)).toInt()
                                // Arada bir ML Kit de okur: iki okuyucunun yazısı ölçümlere yan yana yazılır.
                                if (++npuReads % COMPARE_EVERY == 0) {
                                    try {
                                        val kit = recognize(TextBand.crop(pixels.nv21, pixels.width, band), band.width, band.height,
                                            band, pixels.width, pixels.height)
                                        com.couchsubs.mobile.service.Measure.compare(
                                            result.reading.lines.joinToString(" / ") { it.text },
                                            kit.lines.joinToString(" / ") { it.text }, result.confidence)
                                        lastMode = "NPU ${npu.pathName}"
                                    } catch (_: Exception) {}
                                }
                                return result.reading
                            }
                            npuDoubt = true
                            if (result != null) com.couchsubs.mobile.service.Measure.compare(
                                result.reading.lines.joinToString(" / ") { it.text }, null, result.confidence)
                        }
                    }
                    lastAreaPercent = (100L * band.width * band.height / (pixels.width.toLong() * pixels.height)).toInt()
                    // Alçak şerit (küçük yazı, tek satır) 2 kat büyütülerek okunur. Geniş ve yeterince yüksek şerit
                    // FULL_READ_MAX_WIDTH'e küçültülür: ML Kit'in süresi enle artıyor (1348 px şerit ~1100 ms, 960 px
                    // tam bölge ~725 ms; TV ölçümü 2026-11-16).
                    val reading = try {
                        // Büyütme yalnız dar şeritte: 1948 px'e büyütülen şerit 1,5 sn sürüyordu (ölçüm, 2026-11-16).
                        if (band.height < SMALL_BAND && band.width <= 700) {
                            recognize(TextBand.cropScaled(pixels.nv21, pixels.width, band, 2),
                                band.width * 2, band.height * 2, band, pixels.width, pixels.height, 2.0)
                        } else if (band.width > FULL_READ_MAX_WIDTH && band.height * FULL_READ_MAX_WIDTH / band.width >= SMALL_BAND) {
                            val w = FULL_READ_MAX_WIDTH and 1.inv()
                            val h = (band.height * w / band.width) and 1.inv()
                            recognize(TextBand.resize(TextBand.crop(pixels.nv21, pixels.width, band), band.width, band.height, w, h),
                                w, h, band, pixels.width, pixels.height, w.toDouble() / band.width)
                        } else {
                            recognize(TextBand.crop(pixels.nv21, pixels.width, band), band.width, band.height,
                                band, pixels.width, pixels.height)
                        }
                    } catch (error: Exception) {
                        android.util.Log.w("CouchSubsOcr", "Şerit okunamadı (${band.width}x${band.height}): ${error.message}")
                        Reading.Empty
                    }
                    // Şeritte yazı çıkmadıysa şerit yanlış yerdedir: bölgenin tamamı okunur (asla eskisinden kötü değil).
                    if (reading.lines.isNotEmpty()) return reading
                }
            }
        }
        lastAreaPercent = 100
        // Tam bölge okuması 1080p'de ~2 kat yavaştı (300-400 ms, Horizon'da çimenli sahnede şerit bulunamayınca hep bu yol;
        // 2026-11-16). Geniş bölge 720p karşılığına küçültülür; keskinlik gereken küçük yazı yukarıdaki şerit yolundan okunur.
        if (pixels.width > FULL_READ_MAX_WIDTH) {
            val w = FULL_READ_MAX_WIDTH and 1.inv()
            val h = ((pixels.height.toLong() * w / pixels.width).toInt()).coerceAtLeast(32) and 1.inv()
            return recognize(TextBand.resize(pixels.nv21, pixels.width, pixels.height, w, h), w, h, null, w, h)
        }
        return recognize(pixels.nv21, pixels.width, pixels.height, null, pixels.width, pixels.height)
    }

    /** [band] verildiyse görüntü bölgenin o şerididir; kutular bölgenin tamamına göre hesaplanır. */
    private fun recognize(nv21: ByteArray, width: Int, height: Int, band: Band?, regionWidth: Int, regionHeight: Int,
                          scale: Double = 1.0): Reading {
        lastMode = (if (npuDoubt) "NPU emin değil → " else "") + when {
            band == null -> if (triedBand) "şerit boş → tam bölge" else "tam bölge"
            scale > 1.0 -> "şerit büyütülmüş"
            scale < 1.0 -> "şerit küçültülmüş"
            else -> "şerit"
        }
        lastWidth = width
        lastHeight = height
        val input = InputImage.fromByteArray(nv21, width, height, 0, InputImage.IMAGE_FORMAT_NV21)
        val text = Tasks.await(recognizer.process(input), 5, TimeUnit.SECONDS)
        val offsetX = band?.left ?: 0
        val offsetY = band?.top ?: 0
        val lines = mutableListOf<TextLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox?.let {
                    TextBox(
                        (offsetX + it.left / scale) / regionWidth, (offsetY + it.top / scale) / regionHeight,
                        (offsetX + it.right / scale) / regionWidth, (offsetY + it.bottom / scale) / regionHeight,
                    )
                }
                lines.add(TextLine(line.text, box))
            }
        }
        // Yukarıdan aşağı (ML Kit blok sırası her zaman öyle değil).
        return Reading(lines.sortedBy { it.box?.top ?: 0.0 })
    }

    override fun close() = recognizer.close()
}
