package com.cameroncurrin.camzone

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Draws the picture shown over the camera view.
 * Zones mode: the color code. Other modes: a gray "what the PYXIS sees" picture.
 * Zebras and focus peaking are painted on top in either case.
 */
fun renderOverlay(f: ZoneFrame, s: CamSettings, cal: Double, zones: Boolean, tools: Tools): Bitmap {
    val gray = Expo.grayEv(s)
    val w = f.width
    val h = f.height
    val px = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val st = f.ev[i] + cal - gray
            var c = if (zones) Expo.shadedColor(st) else {
                // gray monitor: middle gray at 50%, gentle roll-off at both ends
                val v = (255 / (1 + 2.0.pow(-st * 0.6))).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            if (tools.peaking && x < w - 1 && y < h - 1) {
                val d = maxOf(abs(f.ev[i] - f.ev[i + 1]), abs(f.ev[i] - f.ev[i + w]))
                if (d > 0.9f) c = 0xFF00E5FF.toInt()
            }
            if (tools.zebra && st >= tools.zebraAt && ((x + y) / 3) % 2 == 0) c = 0xFFFFFFFF.toInt()
            // The phone itself clipped here, so the true brightness is unknown (at least this bright).
            // Black and white diagonal hatch: lower the phone ISO or use Match PYXIS to read it.
            if (f.phoneClipped[i] && st < Expo.CLIP) c = if (((x - y + 4096) / 4) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            px[i] = c
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

/** Frame guides and rule-of-thirds grid, drawn over the camera view. */
fun DrawScope.drawGuides(tools: Tools) {
    val line = Color(0xCCFFFFFF)
    if (tools.grid) {
        for (k in 1..2) {
            drawLine(line, Offset(size.width * k / 3, 0f), Offset(size.width * k / 3, size.height), 1.5f)
            drawLine(line, Offset(0f, size.height * k / 3), Offset(size.width, size.height * k / 3), 1.5f)
        }
    }
    if (tools.guide != Guide.OFF) {
        val gh = size.width / tools.guide.ratio
        val top = (size.height - gh) / 2
        val shade = Color(0x99000000)
        drawRect(shade, Offset.Zero, Size(size.width, top))
        drawRect(shade, Offset(0f, top + gh), Size(size.width, size.height - top - gh))
        drawRect(line, Offset(0f, top), Size(size.width, gh), style = Stroke(2f))
    }
}

/** Histogram in stops from middle gray, each bar in its zone color. */
@Composable
fun StopHistogram(f: ZoneFrame?, s: CamSettings, cal: Double) {
    if (f == null) return
    val lo = -8.0
    val hi = 7.0
    val bins = ((hi - lo) * 3).toInt() // third-stop bars
    val counts = IntArray(bins)
    val gray = Expo.grayEv(s)
    for (v in f.ev) {
        val st = v + cal - gray
        val b = floor((st - lo) * 3).toInt().coerceIn(0, bins - 1)
        counts[b]++
    }
    val max = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
    Column(Modifier.padding(top = 6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val bw = size.width / bins
            for (b in 0 until bins) {
                val st = lo + (b + 0.5) / 3
                val hgt = size.height * counts[b] / max
                drawRect(Color(Expo.zone(st).argb), Offset(b * bw, size.height - hgt), Size(bw * 0.85f, hgt))
            }
            val zx = ((0 - lo) * 3 * bw).toFloat()
            drawLine(Color.White, Offset(zx, 0f), Offset(zx, size.height), 1f)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−8", color = Color(0xFF8A8A8A), fontSize = 10.sp)
            Text("0 = middle gray", color = Color(0xFF8A8A8A), fontSize = 10.sp)
            Text("+7", color = Color(0xFF8A8A8A), fontSize = 10.sp)
        }
    }
}

@Composable
fun ToolsRow() {
    val t by AppState.tools.collectAsState()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(t.zebra, { AppState.tools.value = t.copy(zebra = !t.zebra) }, { Text("Zebras") })
        FilterChip(t.peaking, { AppState.tools.value = t.copy(peaking = !t.peaking) }, { Text("Peaking") })
        FilterChip(t.guide != Guide.OFF, {
            val next = Guide.values()[(t.guide.ordinal + 1) % Guide.values().size]
            AppState.tools.value = t.copy(guide = next)
        }, { Text(if (t.guide == Guide.OFF) "Guides" else "Guide ${t.guide.label}") })
        FilterChip(t.grid, { AppState.tools.value = t.copy(grid = !t.grid) }, { Text("Grid") })
        FilterChip(t.histogram, { AppState.tools.value = t.copy(histogram = !t.histogram) }, { Text("Histogram") })
    }
    if (t.zebra) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Zebras at", color = Color(0xFF9A9A9A), fontSize = 12.sp)
            listOf(Expo.CLIP to "Clip +6", Expo.WARN to "+5⅓", 3.0 to "+3", Expo.GOOD_HIGH to "+1⅓ (hot skin)").forEach { (v, l) ->
                FilterChip(t.zebraAt == v, { AppState.tools.value = t.copy(zebraAt = v) }, { Text(l, fontSize = 12.sp) })
            }
        }
    }
}

/** Manual controls for the phone camera, like the Blackmagic Camera app's control strip. */
@Composable
fun PhoneControlsPanel(lenses: List<Pair<String, String>>) {
    Section("Phone camera") { PhoneControlsBody(lenses) }
}

@Composable
fun PhoneControlsBody(lenses: List<Pair<String, String>>) {
    val p by AppState.phone.collectAsState()
    fun set(n: PhoneControls) { AppState.phone.value = n }
    run {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(p.matchPyxis, { set(p.copy(matchPyxis = true)) }, { Text("Match PYXIS") })
            FilterChip(!p.matchPyxis, { set(p.copy(matchPyxis = false)) }, { Text("Manual") })
        }
        Text(
            if (p.matchPyxis) "ISO and shutter follow the PYXIS, a few stops darker to protect highlights. Readings stay exact either way."
            else "Phone ISO and shutter only change how bright the phone's picture is. The zone colors show what the PYXIS will record, so they don't move. To see the zones shift, change the PYXIS ISO or iris at the top. Black-and-white hatching means the phone is too bright to read that spot: lower the phone ISO.",
            color = Color(0xFF9A9A9A), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp),
        )
        if (!p.matchPyxis) {
            Label("Phone ISO ${p.iso} (picture brightness only)")
            ChipRow(listOf(50, 100, 200, 400, 800, 1600, 3200), p.iso, { "$it" }) { set(p.copy(iso = it)) }
            Label("Shutter ${fmt(p.shutterAngle)}°")
            ChipRow(listOf(45.0, 90.0, 172.8, 180.0, 270.0, 360.0), p.shutterAngle, { fmt(it) + "°" }) { set(p.copy(shutterAngle = it)) }
            Label("Frame rate ${fmt(p.fps)}")
            ChipRow(listOf(23.98, 24.0, 25.0, 30.0, 60.0), p.fps, { fmt(it) }) { set(p.copy(fps = it)) }
        }
        Label("White balance")
        ChipRow(Wb.values().toList(), p.wb, { it.label }) { set(p.copy(wb = it)) }
        Label(if (p.autoFocus) "Focus: auto" else "Focus: manual")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(p.autoFocus, { set(p.copy(autoFocus = true)) }, { Text("Auto") })
            FilterChip(!p.autoFocus, { set(p.copy(autoFocus = false)) }, { Text("Manual") })
        }
        if (!p.autoFocus) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("∞", color = Color.White, fontSize = 14.sp)
                Slider(p.focus, { set(p.copy(focus = it)) }, Modifier.weight(1f).padding(horizontal = 8.dp))
                Text("Near", color = Color.White, fontSize = 14.sp)
            }
        }
        if (lenses.size > 1) {
            Label("Lens")
            ChipRow(lenses.map { it.first }, p.lensId ?: lenses.first().first, { id -> lenses.first { it.first == id }.second }) {
                set(p.copy(lensId = it))
            }
        }
    }
}

@Composable
internal fun Label(t: String) {
    Text(t, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(o == selected, { onPick(o) }, { Text(label(o), fontSize = 13.sp) }) }
    }
}
