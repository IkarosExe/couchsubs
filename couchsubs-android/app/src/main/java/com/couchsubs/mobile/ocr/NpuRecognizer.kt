// SPDX-License-Identifier: AGPL-3.0-only
// IkarosExe fork'u (2026-11-16): ML Kit yerine satır okuyucu. PaddleOCR PP-OCRv4 tanıma modeli (Apache-2.0,
// RapidOCR'ın ONNX'inden TFLite'a çevrildi; 48x1920 sabit giriş) TFLite ile çalışır. TV'deki MediaTek yapay zekâ
// çipi (NNAPI "mtk-mdla_shim" / "mtk-neuron_shim") denenir; açılışta her yol ölçülür, en hızlısı seçilir, hiçbiri
// çalışmazsa işlemci (XNNPack). Yazının yeri TextBand ile bulunur, model yalnız okur (ML Kit her seferinde yeri de
// arıyordu, TV'de bir okuma ~1 sn).
package com.couchsubs.mobile.ocr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.couchsubs.mobile.core.Reading
import com.couchsubs.mobile.core.TextBox
import com.couchsubs.mobile.core.TextLine
import com.couchsubs.mobile.service.Measure
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NpuRecognizer private constructor(
    private val interpreter: Interpreter,
    private val delegate: NnApiDelegate?,
    val pathName: String,
    private val indices: IntArray,
    private val symbols: CharArray,
) : AutoCloseable {

    class Result(val reading: Reading, val confidence: Double)

    private val input = ByteBuffer.allocateDirect(4 * H * W * 3).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(4 * STEPS * CLASSES).order(ByteOrder.nativeOrder())

    /** [lines] bölgedeki satırlar ([y] bölgenin Y düzlemi). Kutular bölgeye oranlı. */
    @Synchronized
    fun read(y: ByteArray, width: Int, height: Int, lines: List<Band>): Result {
        val out = mutableListOf<TextLine>()
        var confidence = 0.0
        var counted = 0
        for (line in lines) {
            val (text, conf) = recognizeLine(y, width, line)
            if (text.isBlank()) continue
            out.add(TextLine(text, TextBox(line.left.toDouble() / width, line.top.toDouble() / height,
                line.right.toDouble() / width, line.bottom.toDouble() / height)))
            confidence += conf * text.length
            counted += text.length
        }
        return Result(Reading(out), if (counted == 0) 0.0 else confidence / counted)
    }

    private fun recognizeLine(y: ByteArray, width: Int, line: Band): Pair<String, Double> {
        // Satır 48 px yüksekliğe ölçeklenir (en boy oranı korunur, en çok W); sağ taraf gri (0) ile dolar.
        val scale = H.toFloat() / line.height
        val outW = minOf(W, (line.width * scale).toInt().coerceAtLeast(1))
        val sx = line.width.toFloat() / outW
        val sy = line.height.toFloat() / H
        input.rewind()
        val fb = input.asFloatBuffer()
        for (oy in 0 until H) {
            val fy = ((oy + 0.5f) * sy - 0.5f).coerceIn(0f, (line.height - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, line.height - 1)
            val wy = fy - y0
            val r0 = (line.top + y0) * width + line.left
            val r1 = (line.top + y1) * width + line.left
            for (ox in 0 until W) {
                val v = if (ox < outW) {
                    val fx = ((ox + 0.5f) * sx - 0.5f).coerceIn(0f, (line.width - 1).toFloat())
                    val x0 = fx.toInt()
                    val x1 = minOf(x0 + 1, line.width - 1)
                    val wx = fx - x0
                    val a = (y[r0 + x0].toInt() and 0xff) * (1 - wx) + (y[r0 + x1].toInt() and 0xff) * wx
                    val b = (y[r1 + x0].toInt() and 0xff) * (1 - wx) + (y[r1 + x1].toInt() and 0xff) * wx
                    (a * (1 - wy) + b * wy) / 127.5f - 1f
                } else 0f
                fb.put(v); fb.put(v); fb.put(v)
            }
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        return decode()
    }

    /** CTC çözümü yalnız ASCII sınıflarında (model Çince de biliyor; İngilizce altyazıda Çince harf çıkmasın). */
    private fun decode(): Pair<String, Double> {
        output.rewind()
        val probs = output.asFloatBuffer()
        val text = StringBuilder()
        var sum = 0.0
        var last = -1
        for (t in 0 until STEPS) {
            val base = t * CLASSES
            var best = -1
            var bestP = probs.get(base) // boş (blank) sınıfı
            for (k in indices.indices) {
                val p = probs.get(base + indices[k])
                if (p > bestP) {
                    bestP = p
                    best = k
                }
            }
            if (best >= 0 && best != last) {
                text.append(symbols[best])
                sum += bestP
            }
            last = best
        }
        val clean = text.toString().replace(Regex(" {2,}"), " ").trim()
        return clean to (if (text.isEmpty()) 0.0 else sum / text.length)
    }

    override fun close() {
        interpreter.close()
        delegate?.close()
    }

    companion object {
        private const val TAG = "CouchSubsNpu"
        const val H = 48
        const val W = 1920
        const val STEPS = W / 8
        const val CLASSES = 6625
        private const val MODEL = "ocr/ppocrv4_rec_1920.tflite"
        private const val ALPHABET = "ocr/ppocrv4_ascii.txt"

        /** Seçilen okuyucu (açılış ölçümü bitene kadar null: o sürede ML Kit okur). */
        @Volatile var active: NpuRecognizer? = null
            private set

        /**
         * Yolları dener ve ölçer: yapay zekâ çipi (iki MediaTek sürücüsü), adsız NNAPI, işlemci. Her yolun ilk açılışı
         * ve ortalama süresi ölçümlere yazılır; çıktısı işlemcininkinden belirgin farklı olan yol (bozuk sürücü) atılır.
         */
        fun prepare(context: Context) {
            active?.let { Measure.mark("NPU: hazır, yol ${it.pathName}"); return }
            val model = try { load(context) } catch (error: Throwable) {
                Measure.mark("NPU: model açılamadı (${error.javaClass.simpleName})")
                return
            }
            val (indices, symbols) = alphabet(context)
            val sample = sampleInput()
            var reference: FloatArray? = null
            var best: NpuRecognizer? = null
            var bestMs = Long.MAX_VALUE
            // İşlemci önce: doğruluk karşılaştırmasının ölçütü.
            val candidates = listOf<String?>("cpu", "mtk-mdla_shim", "mtk-neuron_shim", null)
            for (name in candidates) {
                var delegate: NnApiDelegate? = null
                try {
                    val opened = SystemClock.elapsedRealtime()
                    val options = Interpreter.Options()
                    if (name == "cpu") {
                        options.setNumThreads(4)
                        options.setUseXNNPACK(true)
                    } else {
                        val nn = NnApiDelegate.Options().setAllowFp16(true).setExecutionPreference(
                            NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                        if (name != null) nn.setAcceleratorName(name).setUseNnapiCpu(false)
                        delegate = NnApiDelegate(nn)
                        options.addDelegate(delegate)
                    }
                    model.rewind()
                    val interpreter = Interpreter(model, options)
                    val out = ByteBuffer.allocateDirect(4 * STEPS * CLASSES).order(ByteOrder.nativeOrder())
                    sample.rewind(); interpreter.run(sample, out) // ısınma (sürücü derlemesi)
                    val openMs = SystemClock.elapsedRealtime() - opened
                    val started = SystemClock.elapsedRealtime()
                    repeat(3) { sample.rewind(); out.rewind(); interpreter.run(sample, out) }
                    val ms = (SystemClock.elapsedRealtime() - started) / 3
                    out.rewind()
                    val values = FloatArray(STEPS * CLASSES).also { out.asFloatBuffer().get(it) }
                    val label = name ?: "nnapi"
                    val agree = reference?.let { agreement(it, values) }
                    if (reference == null) reference = values
                    Measure.mark("NPU deneme $label: $ms ms/satır (açılış $openMs ms)" + (agree?.let { " · uyum %$it" } ?: ""))
                    Log.i(TAG, "$label: $ms ms, açılış $openMs ms, uyum $agree")
                    val usable = agree == null || agree >= 90
                    if (usable && ms < bestMs) {
                        best?.close()
                        best = NpuRecognizer(interpreter, delegate, label, indices, symbols)
                        bestMs = ms
                    } else {
                        interpreter.close()
                        delegate?.close()
                    }
                } catch (error: Throwable) {
                    delegate?.close()
                    Measure.mark("NPU deneme ${name ?: "nnapi"}: çalışmadı (${error.javaClass.simpleName}: ${error.message?.take(80)})")
                    Log.w(TAG, "aday ${name ?: "nnapi"}", error)
                }
            }
            active = best
            Measure.mark(best?.let { "NPU: seçilen yol ${it.pathName} ($bestMs ms/satır)" } ?: "NPU: hiçbir yol çalışmadı, ML Kit")
        }

        fun release() {
            active?.close()
            active = null
        }

        private fun load(context: Context): ByteBuffer {
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }

        private fun alphabet(context: Context): Pair<IntArray, CharArray> {
            val rows = context.assets.open(ALPHABET).bufferedReader().readLines().filter { it.contains('\t') }
            val indices = IntArray(rows.size) { rows[it].substringBefore('\t').toInt() }
            val symbols = CharArray(rows.size) { rows[it].substringAfter('\t').firstOrNull() ?: ' ' }
            return indices to symbols
        }

        /** Ölçüm girdisi: yatay çizgili sabit desen (her yolda aynı). */
        private fun sampleInput(): ByteBuffer {
            val buffer = ByteBuffer.allocateDirect(4 * H * W * 3).order(ByteOrder.nativeOrder())
            val fb = buffer.asFloatBuffer()
            for (y in 0 until H) for (x in 0 until W) {
                val v = if (y in 12..36 && (x / 6) % 3 == 0 && x < 900) 1f else -0.8f
                fb.put(v); fb.put(v); fb.put(v)
            }
            return buffer
        }

        /** İki çıktının her adımda aynı sınıfı seçme oranı (%). */
        private fun agreement(a: FloatArray, b: FloatArray): Int {
            var same = 0
            for (t in 0 until STEPS) {
                var ia = 0; var ib = 0
                val base = t * CLASSES
                for (k in 1 until CLASSES) {
                    if (a[base + k] > a[base + ia]) ia = k
                    if (b[base + k] > b[base + ib]) ib = k
                }
                if (ia == ib) same++
            }
            return 100 * same / STEPS
        }
    }
}
