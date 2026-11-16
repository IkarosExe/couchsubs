// SPDX-License-Identifier: AGPL-3.0-only
// IcarusTranslate (IkarosExe): TV'ye göre baştan tasarlanmış arayüz (Claude Design taslağı "IcarusTranslate TV
// arayüzü", 2026-11-16). Dört ekran: Ana ekran, Çeviri sürüyor, Okuma alanı, Ayarlar. Her şey kumandanın ok ve OK
// tuşlarıyla kullanılır; odaklı öğe beyaz çerçeve ve hafif büyümeyle belli olur. Ölçüler 1280x720 taslağa göredir,
// ekran genişliğine ölçeklenir.
package com.couchsubs.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.couchsubs.mobile.Services
import com.couchsubs.mobile.core.AreaPreset
import com.couchsubs.mobile.core.CropRegion
import com.couchsubs.mobile.core.SubtitleTextColor
import com.couchsubs.mobile.service.FramePreview
import com.couchsubs.mobile.service.LiveStatus
import com.couchsubs.mobile.settings.EngineChoice

private object Ic {
    val Ground = Color(0xFF0E1020)
    val Panel = Color(0xFF12152B)
    val Surface = Color(0xFF171A33)
    val Raised = Color(0xFF20244A)
    val Line = Color(0xFF2A2F5A)
    val Text = Color(0xFFF4F5FF)
    val Muted = Color(0xFFA9AED0)
    val Soft = Color(0xFFC9CDEA)
    val Accent = Color(0xFFFF9F43)
    val AccentSoft = Color(0xFFFFB45C)
    val OnAccent = Color(0xFF1A1208)
    val Sun = Color(0xFFFFD98E)
    val Ok = Color(0xFF8FE3B0)
    val Warn = Color(0xFFFF8A80)
}

private enum class IcScreen { HOME, AREA, SETTINGS }

/** Hazır okuma alanları (taslaktaki sırayla). */
private class AreaChoice(val title: String, val note: String, val region: CropRegion)

private val areaChoices = listOf(
    AreaChoice("Alt altyazı", "Çoğu oyun", CropRegion()),
    AreaChoice("Alt geniş", "İki satırlık altyazı", CropRegion(top = 0.55, bottom = 0.98, left = 0.05, right = 0.95)),
    AreaChoice("Orta ipucu", "\"Tuşa bas\" yazıları", CropRegion(top = 0.40, bottom = 0.62, left = 0.20, right = 0.80)),
    AreaChoice("Görev yazısı", "Sol üst köşe", CropRegion(top = 0.03, bottom = 0.30, left = 0.0, right = 0.40)),
    AreaChoice("Tam ekran", "Yavaş, her yeri okur", CropRegion(top = 0.0, bottom = 1.0, left = 0.0, right = 1.0)),
)

private fun areaName(region: CropRegion): String =
    areaChoices.firstOrNull { it.region.isCloseTo(region) }?.title ?: "Özel alan"

private fun CropRegion.isCloseTo(other: CropRegion) =
    kotlin.math.abs(top - other.top) < 0.01 && kotlin.math.abs(bottom - other.bottom) < 0.01 &&
        kotlin.math.abs(left - other.left) < 0.01 && kotlin.math.abs(right - other.right) < 0.01

/** TV'nin tamamı: ekranlar arasında geçiş, geri tuşu. Kurulum ekranları (PSN, PS5) eski akıştan açılır. */
@Composable
fun IcarusTvApp(
    status: LiveStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPsn: () -> Unit,
    onPairing: () -> Unit,
    onPacks: () -> Unit,
    onHistory: () -> Unit,
) {
    val context = LocalContext.current
    // Taslak 1280 birim genişliğindedir; ekrana ölçeklenir (yazılar da aynı oranda).
    val width = context.resources.displayMetrics.widthPixels
    val density = Density(width / 1280f, 1f)
    var screen by remember { mutableStateOf(IcScreen.HOME) }
    BackHandler(enabled = screen != IcScreen.HOME) { screen = IcScreen.HOME }
    CompositionLocalProvider(LocalDensity provides density) {
        Box(Modifier.fillMaxSize().background(Ic.Ground)) {
            when (screen) {
                IcScreen.HOME -> if (status.running) RunningScreen(status, onStop, onHistory)
                    else HomeScreen(status, onStart, onArea = { screen = IcScreen.AREA },
                        onSettings = { screen = IcScreen.SETTINGS }, onPacks = onPacks, onHistory = onHistory)
                IcScreen.AREA -> AreaScreen()
                IcScreen.SETTINGS -> SettingsPane(status, onPsn, onPairing, onPacks, onHistory)
            }
        }
    }
}

// ---------- Ortak parçalar ----------

@Composable
private fun T(text: String, size: TextUnit, color: Color = Ic.Text, weight: FontWeight = FontWeight.Normal,
              modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified) {
    Text(text, modifier = modifier, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, textAlign = align, lineHeight = lineHeight)
}

/** Kumandayla odaklanan kutu: odakta beyaz 4 birimlik çerçeve ve %4 büyüme. */
@Composable
private fun Focusy(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    background: Color = Ic.Surface,
    border: Color = Ic.Line,
    requester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "focus")
    LaunchedEffect(focused) { if (focused) onFocus?.invoke() }
    Box(
        modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(background)
            .border(if (focused) 4.dp else 2.dp, if (focused) Color.White else border, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) { content(focused) }
}

@Composable
private fun Logo(size: Dp = 56.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.25f)).background(Ic.Accent)) {
        Box(Modifier.size(size * 0.4f).offset(size * 0.46f, size * 0.14f).clip(CircleShape).background(Ic.Sun))
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.width / 104f
            fun arc(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, w: Float, a: Float) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo((10 + x0 * 0.68f) * s, (30 + y0 * 0.68f) * s)
                    quadraticTo((10 + cx * 0.68f) * s, (30 + cy * 0.68f) * s, (10 + x1 * 0.68f) * s, (30 + y1 * 0.68f) * s)
                }
                drawPath(path, Ic.Ground.copy(alpha = a), style = Stroke(width = w * s,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            arc(14f, 84f, 40f, 30f, 94f, 12f, 10f, 1f)
            arc(34f, 86f, 52f, 50f, 90f, 34f, 9f, 0.75f)
        }
    }
}

@Composable
private fun SectionLabel(text: String) = T(text.uppercase(), 16.sp, Ic.Muted, FontWeight.SemiBold)

@Composable
private fun Chip(text: String, strong: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Ic.Raised).padding(horizontal = 14.dp, vertical = 8.dp)) {
        T(text, 16.sp, if (strong) Color.White else Ic.Soft, if (strong) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private fun engineName(engine: EngineChoice) = when (engine) {
    EngineChoice.FREE -> "Google (ücretsiz)"
    EngineChoice.DEEPL -> "DeepL"
    EngineChoice.GEMINI -> "Gemini"
    EngineChoice.OPENAI -> "OpenAI"
    EngineChoice.CLAUDE -> "Claude"
}

private fun seconds(ms: Int?) = ms?.let { "%.1f sn".format(it / 1000.0) } ?: "-"

// ---------- 1 · Ana ekran ----------

@Composable
private fun HomeScreen(status: LiveStatus, onStart: () -> Unit, onArea: () -> Unit, onSettings: () -> Unit,
                       onPacks: () -> Unit, onHistory: () -> Unit) {
    val settings = Services.settings
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    var hdmi by remember { mutableIntStateOf(settings.tvHdmiPort) }
    val paired = settings.console != null
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.size(520.dp).offset(940.dp, (-260).dp).clip(CircleShape).background(Ic.Accent.copy(alpha = 0.12f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 56.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.width(440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Logo()
                    Row {
                        T("Icarus", 28.sp, Ic.Text, FontWeight.ExtraBold)
                        T("Translate", 28.sp, Ic.Accent, FontWeight.ExtraBold)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val line = when {
                        status.error != null -> status.error
                        !paired -> "PS5 eşleştirilmedi · Ayarlar › PS5 bağlantısı"
                        else -> "PS5 hazır" + (status.game?.let { " · $it" } ?: "")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (status.error == null && paired) Ic.Ok else Ic.Warn))
                        T(line ?: "", 18.sp, if (status.error == null && paired) Ic.Ok else Ic.Warn, FontWeight.SemiBold, maxLines = 2)
                    }
                    T("Oyun ekranındaki yazıyı Türkçe göster", 44.sp, Ic.Text, FontWeight.Bold, lineHeight = 52.sp)
                }
                Focusy(onClick = onStart, modifier = Modifier.fillMaxWidth().height(88.dp), shape = RoundedCornerShape(20.dp),
                    background = Ic.Accent, border = Ic.Accent, requester = start) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Canvas(Modifier.size(26.dp)) {
                            val p = androidx.compose.ui.graphics.Path().apply {
                                moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.2f, size.height); close()
                            }
                            drawPath(p, Ic.OnAccent)
                        }
                        T("Çeviriyi başlat", 30.sp, Ic.OnAccent, FontWeight.Bold)
                    }
                }
                T("Başlatınca oyuna dön; altyazılar oyunun üstünde çıkar.", 17.sp, Ic.Muted)
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SectionLabel("Hızlı ayarlar")
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Tile("Oyun", status.game ?: "Bilinmiyor",
                        status.packInUse?.let { "Hazır çeviri: $it" } ?: "Hazır çeviriler", Modifier.weight(1f), onPacks)
                    Tile("Okuma alanı", areaName(settings.region), "Değiştir", Modifier.weight(1f), onArea)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    val hasKey = !settings.apiKey(EngineChoice.DEEPL).isNullOrBlank()
                    Tile("Çeviri", engineName(settings.engine),
                        if (settings.engine == EngineChoice.DEEPL) "Anahtar kayıtlı" else if (hasKey) "DeepL'e geçilebilir" else "Ayarlardan değiştir",
                        Modifier.weight(1f), onSettings)
                    Tile("PS5 girişi", if (hdmi == 0) "Otomatik" else "HDMI $hdmi", "OK: sıradaki giriş", Modifier.weight(1f)) {
                        hdmi = (hdmi + 1) % 5
                        settings.tvHdmiPort = hdmi
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Focusy(onClick = onSettings, modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(16.dp),
                        background = Color.Transparent) { T("Ayarlar", 19.sp, Ic.Text, FontWeight.SemiBold, Modifier.align(Alignment.Center)) }
                    Focusy(onClick = onHistory, modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(16.dp),
                        background = Color.Transparent) { T("Son çeviriler", 19.sp, Ic.Text, FontWeight.SemiBold, Modifier.align(Alignment.Center)) }
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, note: String, modifier: Modifier, onClick: () -> Unit) {
    Focusy(onClick = onClick, modifier = modifier.height(150.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            T(label, 16.sp, Ic.Muted)
            T(value, 24.sp, Ic.Text, FontWeight.Bold, maxLines = 1)
            T(note, 15.sp, Ic.AccentSoft, maxLines = 1)
        }
    }
}

// ---------- 2 · Çeviri sürüyor ----------

@Composable
private fun RunningScreen(status: LiveStatus, onStop: () -> Unit, onHistory: () -> Unit) {
    val context = LocalContext.current
    val back = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.startedAt) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    val elapsed = if (status.startedAt > 0) ((now - status.startedAt) / 1000).coerceAtLeast(0) else 0
    val current = status.recent.firstOrNull()
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (status.error == null) Ic.Ok else Ic.Warn))
            T(status.phase.ifBlank { "Çeviri sürüyor" }, 30.sp, Ic.Text, FontWeight.Bold)
            T(listOfNotNull(status.game, status.engineLabel.ifBlank { null }, "%d:%02d".format(elapsed / 60, elapsed % 60)).joinToString(" · "),
                18.sp, Ic.Muted, maxLines = 1, modifier = Modifier.weight(1f))
            Focusy(onClick = {
                if (!com.couchsubs.tv.HdmiInputs.open(context, Services.settings.tvHdmiPort))
                    android.widget.Toast.makeText(context, "PS5'in girişini kumandadan seçin", android.widget.Toast.LENGTH_LONG).show()
            }, modifier = Modifier.height(60.dp).width(190.dp), shape = RoundedCornerShape(16.dp), background = Ic.Text, border = Ic.Text,
                requester = back) { T("Oyuna dön", 20.sp, Ic.Ground, FontWeight.Bold, Modifier.align(Alignment.Center)) }
            Focusy(onClick = onStop, modifier = Modifier.height(60.dp).width(150.dp), shape = RoundedCornerShape(16.dp),
                background = Color.Transparent) { T("Durdur", 20.sp, Ic.Text, FontWeight.SemiBold, Modifier.align(Alignment.Center)) }
        }
        status.error?.let { T(it, 18.sp, Ic.Warn) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ic.Surface).padding(horizontal = 36.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Şimdi")
            T(current?.translation ?: "Altyazı bekleniyor…", 40.sp, Ic.AccentSoft, FontWeight.Bold, lineHeight = 52.sp, maxLines = 3)
            current?.let { T(it.source, 21.sp, Ic.Soft, maxLines = 2) }
            if (current != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip("Okuma ${seconds(current.ocrMs)}")
                Chip(if (current.origin == "paket") "Hazır çeviri" else "Çeviri ${seconds(current.translationMs)}")
                Chip("Toplam ${seconds(current.totalMs)}", strong = true)
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Ic.Panel)
            .padding(horizontal = 36.dp, vertical = 8.dp)) {
            status.recent.drop(1).take(3).forEachIndexed { index, pair ->
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T(pair.translation, 22.sp, Ic.Text, FontWeight.Medium, maxLines = 1)
                    T(pair.source, 16.sp, Ic.Muted, maxLines = 1)
                }
                if (index < 2) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF23284D)))
            }
        }
    }
}

// ---------- 3 · Okuma alanı ----------

@Composable
private fun AreaScreen() {
    val settings = Services.settings
    var region by remember { mutableStateOf(settings.region) }
    var editing by remember { mutableStateOf(false) }
    fun save(r: CropRegion) {
        region = r
        settings.region = r
        settings.areaPreset = AreaPreset.matching(r)
    }
    DisposableEffect(Unit) {
        FramePreview.watch(true)
        onDispose { FramePreview.watch(false) }
    }
    val frame by FramePreview.frame.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(728.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            T("Okuma alanı", 30.sp, Ic.Text, FontWeight.Bold)
            T("Altyazının çıktığı yeri seç. Alan ne kadar dar olursa çeviri o kadar hızlı gelir.", 18.sp, Ic.Muted)
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            Box(
                Modifier.width(728.dp).height(410.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2C3A2A))
                    .border(if (focused) 4.dp else 0.dp, Color.White, RoundedCornerShape(16.dp))
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val step = 0.02
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { editing = !editing; true }
                            Key.DirectionUp -> if (editing) { save(region.shifted(-step)); true } else false
                            Key.DirectionDown -> if (editing) { save(region.shifted(step)); true } else false
                            Key.DirectionLeft -> if (editing) { save(region.widened(-step)); true } else false
                            Key.DirectionRight -> if (editing) { save(region.widened(step)); true } else false
                            else -> false
                        }
                    }
                    .focusable(interactionSource = source),
            ) {
                frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    ?: T("Görüntü, çeviri çalışırken PS5'ten gelir", 18.sp, Ic.Soft, modifier = Modifier.align(Alignment.Center))
                Canvas(Modifier.fillMaxSize()) {
                    val l = (region.left * size.width).toFloat(); val t = (region.top * size.height).toFloat()
                    val r = (region.right * size.width).toFloat(); val b = (region.bottom * size.height).toFloat()
                    val shade = Ic.Ground.copy(alpha = 0.45f)
                    drawRect(shade, Offset.Zero, Size(size.width, t))
                    drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                    drawRect(shade, Offset(0f, t), Size(l, b - t))
                    drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                    drawRect(if (editing) Color.White else Ic.Accent, Offset(l, t), Size(r - l, b - t), style = Stroke(4.dp.toPx()))
                }
            }
            T(if (editing) "Yukarı/aşağı: alanı kaydır · Sol/sağ: daralt/genişlet · OK: bitir"
                else if (focused) "OK: alanı kumandayla ayarla" else "Görüntüye gelip OK'a basarak alanı elle ayarlayabilirsin",
                17.sp, if (editing) Color.White else Ic.Soft)
        }
        Column(Modifier.fillMaxSize().padding(top = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Hazır alanlar")
            areaChoices.forEachIndexed { index, choice ->
                val selected = choice.region.isCloseTo(region)
                Focusy(onClick = { save(choice.region) }, modifier = Modifier.fillMaxWidth().height(76.dp),
                    background = if (selected) Ic.Raised else Ic.Surface, requester = if (index == 0) first else null) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        MiniArea(choice.region, selected)
                        Column(Modifier.weight(1f)) {
                            T(choice.title, 21.sp, Ic.Text, FontWeight.SemiBold)
                            T(choice.note, 15.sp, Ic.Muted)
                        }
                        if (selected) T("✓", 22.sp, Ic.Ok, FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniArea(region: CropRegion, selected: Boolean) {
    Box(Modifier.width(64.dp).height(38.dp).clip(RoundedCornerShape(6.dp)).border(2.dp, Color(0xFF6A70A8), RoundedCornerShape(6.dp))) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            drawRoundRect(if (selected) Ic.Accent else Color(0xFF6A70A8),
                Offset((region.left * size.width).toFloat(), (region.top * size.height).toFloat()),
                Size(((region.right - region.left) * size.width).toFloat(), ((region.bottom - region.top) * size.height).toFloat()),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
    }
}

private fun CropRegion.shifted(dy: Double): CropRegion {
    val h = bottom - top
    val t = (top + dy).coerceIn(0.0, 1.0 - h)
    return copy(top = t, bottom = t + h)
}

private fun CropRegion.widened(dx: Double): CropRegion {
    val l = (left - dx).coerceIn(0.0, 0.45)
    val r = (right + dx).coerceIn(0.55, 1.0)
    return copy(left = l, right = r)
}

// ---------- 4 · Ayarlar ----------

private val settingsMenu = listOf("Çeviri motoru", "Altyazı görünümü", "HDMI girişi", "PSN hesabı", "PS5 bağlantısı",
    "Hazır çeviriler", "Tanı ve ölçümler")

@Composable
private fun SettingsPane(status: LiveStatus, onPsn: () -> Unit, onPairing: () -> Unit, onPacks: () -> Unit, onHistory: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(340.dp).fillMaxHeight().background(Ic.Panel).padding(horizontal = 28.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T("Ayarlar", 30.sp, Ic.Text, FontWeight.Bold, Modifier.padding(start = 16.dp, bottom = 20.dp))
            settingsMenu.forEachIndexed { index, title ->
                Focusy(onClick = { selected = index }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(14.dp),
                    background = if (selected == index) Ic.Raised else Color.Transparent, border = Color.Transparent,
                    requester = if (index == 0) first else null, onFocus = { selected = index }) {
                    T(title, 19.sp, if (selected == index) Color.White else Ic.Soft,
                        if (selected == index) FontWeight.Bold else FontWeight.Medium, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            T(settingsMenu[selected], 26.sp, Ic.Text, FontWeight.Bold)
            when (selected) {
                0 -> EnginePane()
                1 -> StylePane()
                2 -> HdmiPane()
                3 -> ActionPane("PSN hesabı uzaktan oynama için kullanılır. Hesabı değiştirmek ya da girişi yenilemek için:", "Yeniden giriş yap", onPsn)
                4 -> ActionPane("PS5 değiştiyse ya da bağlantı kurulamıyorsa yeniden eşleştir. PS5'te Ayarlar › Sistem › Uzaktan Oynatma › Cihazı Bağla'dan kodu al.",
                    "PS5'i yeniden eşleştir", onPairing)
                5 -> PacksPane(onPacks)
                else -> DiagPane(status, onHistory)
            }
        }
    }
}

@Composable
private fun Options(items: List<String>, selected: Int, width: Dp = 170.dp, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { index, item ->
            Focusy(onClick = { onSelect(index) }, modifier = Modifier.width(width).height(60.dp), shape = RoundedCornerShape(14.dp),
                background = if (index == selected) Color(0xFF2A2340) else Ic.Surface,
                border = if (index == selected) Ic.Accent else Ic.Line) {
                T(item, 18.sp, Ic.Text, if (index == selected) FontWeight.Bold else FontWeight.SemiBold, Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun EnginePane() {
    val settings = Services.settings
    var engine by remember { mutableStateOf(settings.engine) }
    val hasKey = !settings.apiKey(EngineChoice.DEEPL).isNullOrBlank()
    val engines = listOf(EngineChoice.FREE, EngineChoice.DEEPL)
    Options(listOf("Google (ücretsiz)", "DeepL"), engines.indexOf(engine).coerceAtLeast(0), 260.dp) { index ->
        if (index == 1 && !hasKey) return@Options
        engine = engines[index]
        settings.engine = engine
    }
    T(if (hasKey) "DeepL anahtarı kayıtlı. Değişiklik bir sonraki çeviri başlatışında geçerli olur."
        else "DeepL için anahtar gerekli. Çeviri açıkken bilgisayarda PowerShell'e yaz:\ncurl.exe -d \"deepl=ANAHTAR\" http://TV-ADRESİ:8791",
        18.sp, Ic.Soft, lineHeight = 28.sp)
}

@Composable
private fun StylePane() {
    val settings = Services.settings
    val sizes = listOf(36, 46, 58, 72)
    var size by remember { mutableIntStateOf(settings.tvFontSize) }
    var color by remember { mutableStateOf(settings.textColor) }
    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF4F6E52))) {
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 18.dp, vertical = 10.dp)) {
            T("Rost: İşaretimi bekle.", (size * 0.72f).sp, if (color == SubtitleTextColor.YELLOW) Color(0xFFFFE066) else Color.White, FontWeight.Bold)
        }
        T("Önizleme", 13.sp, Color.White, modifier = Modifier.padding(14.dp).clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp))
    }
    T("Yazı boyu", 17.sp, Ic.Muted)
    Options(listOf("Küçük", "Orta", "Büyük", "Çok büyük"), sizes.indexOf(size).coerceAtLeast(1)) { index ->
        size = sizes[index]
        settings.tvFontSize = size
    }
    T("Renk", 17.sp, Ic.Muted)
    Options(listOf("Beyaz", "Sarı"), if (color == SubtitleTextColor.YELLOW) 1 else 0) { index ->
        color = if (index == 1) SubtitleTextColor.YELLOW else SubtitleTextColor.WHITE
        settings.textColor = color
    }
}

@Composable
private fun HdmiPane() {
    val settings = Services.settings
    var port by remember { mutableIntStateOf(settings.tvHdmiPort) }
    T("PS5'in TV'de bağlı olduğu giriş. Otomatik: bağlı tek girişi bulur.", 18.sp, Ic.Soft)
    Options(listOf("Otomatik", "HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4"), port, 150.dp) { index ->
        port = index
        settings.tvHdmiPort = index
    }
}

@Composable
private fun ActionPane(text: String, action: String, onClick: () -> Unit) {
    T(text, 18.sp, Ic.Soft, lineHeight = 28.sp)
    Focusy(onClick = onClick, modifier = Modifier.width(360.dp).height(64.dp), shape = RoundedCornerShape(16.dp),
        background = Ic.Accent, border = Ic.Accent) { T(action, 20.sp, Ic.OnAccent, FontWeight.Bold, Modifier.align(Alignment.Center)) }
}

@Composable
private fun PacksPane(onPacks: () -> Unit) {
    val settings = Services.settings
    var enabled by remember { mutableStateOf(settings.packsEnabled) }
    T("Hazır çevirisi olan oyunlarda satırlar anında ve insan çevirisiyle gelir.", 18.sp, Ic.Soft)
    Options(listOf("Açık", "Kapalı"), if (enabled) 0 else 1) { index ->
        enabled = index == 0
        settings.packsEnabled = enabled
    }
    Focusy(onClick = onPacks, modifier = Modifier.width(300.dp).height(60.dp), shape = RoundedCornerShape(16.dp),
        background = Color.Transparent) { T("Oyun listesini gör", 19.sp, Ic.Text, FontWeight.SemiBold, Modifier.align(Alignment.Center)) }
}

@Composable
private fun DiagPane(status: LiveStatus, onHistory: () -> Unit) {
    T(status.diagnostics.ifBlank { "Ölçümler çeviri çalışırken görünür. Tanı sayfası: http://TV-ADRESİ:8791" }, 18.sp, Ic.Soft, lineHeight = 28.sp)
    T("Bu oturumda ${status.shownLines} satır, ${status.packLines} tanesi hazır çeviriden.", 18.sp, Ic.Soft)
    Focusy(onClick = onHistory, modifier = Modifier.width(300.dp).height(60.dp), shape = RoundedCornerShape(16.dp),
        background = Color.Transparent) { T("Son çeviriler", 19.sp, Ic.Text, FontWeight.SemiBold, Modifier.align(Alignment.Center)) }
}

@Suppress("unused")
private fun Modifier.dim(on: Boolean) = if (on) alpha(0.5f) else this
