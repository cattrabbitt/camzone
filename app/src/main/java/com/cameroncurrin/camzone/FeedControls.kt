package com.cameroncurrin.camzone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PyxisParam(val label: String) { ISO("ISO"), SHUTTER("Shutter"), FPS("FPS"), IRIS("Iris"), ND("ND") }

/** The PYXIS settings as big tap targets. Tap one for presets and a slider. */
@Composable
fun PyxisStrip(s: CamSettings, status: String, statusColor: Color, onPick: (PyxisParam) -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF1B1B1B), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Cell("ISO", "${s.iso}") { onPick(PyxisParam.ISO) }
            Cell("Shutter", fmt(s.shutterAngle) + "°") { onPick(PyxisParam.SHUTTER) }
            Cell("FPS", fmt(s.fps)) { onPick(PyxisParam.FPS) }
            Cell("Iris", Expo.formatT(s.tStop)) { onPick(PyxisParam.IRIS) }
            Cell("ND", if (s.nd <= 0) "Off" else Expo.ndName(s.nd)) { onPick(PyxisParam.ND) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, color = statusColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onMore) { Text("Lens & more", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Cell(label: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).background(Color(0xFF262626), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color(0xFF9A9A9A), fontSize = 11.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

private val isoPresets = listOf(100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 12800)
private val shutterPresets = listOf(45.0, 90.0, 144.0, 172.8, 180.0, 270.0, 360.0)
private val fpsPresets = listOf(23.98, 24.0, 25.0, 29.97, 30.0, 48.0, 50.0, 59.94, 60.0)

/** Third-stop T-stops between the lens's widest and narrowest. */
private fun thirdStops(widest: Double, narrowest: Double): List<Double> {
    val out = mutableListOf<Double>()
    var i = 0
    while (true) {
        val t = 2.0.pow(i / 6.0) // sqrt(2)^(i/3)
        if (t > narrowest * 1.01) break
        if (t >= widest * 0.99) out += Expo.roundT(t)
        i++
    }
    return out.distinct()
}

/**
 * Preset popup with a slider for one PYXIS setting. ISO, shutter and iris are sent to the camera when it's connected;
 * frame rate and ND are noted for the meter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PyxisPicker(param: PyxisParam, s: CamSettings, link: CameraLink?, onClose: () -> Unit) {
    val widest = AppState.widestT.value
    val narrowest = AppState.narrowestT.value
    fun commit(n: CamSettings) {
        AppState.settings.value = n
        AppState.save()
        if (link?.isConnected == true) when (param) {
            PyxisParam.ISO -> link.setIso(n.iso)
            PyxisParam.SHUTTER -> link.setShutterAngle(n.shutterAngle)
            PyxisParam.IRIS -> link.setIris(n.tStop)
            else -> Unit
        }
    }
    val options: List<Pair<String, CamSettings>> = when (param) {
        PyxisParam.ISO -> isoPresets.map { "$it" to s.copy(iso = it) }
        PyxisParam.SHUTTER -> shutterPresets.map { fmt(it) + "°" to s.copy(shutterAngle = it) }
        PyxisParam.FPS -> fpsPresets.map { fmt(it) to s.copy(fps = it) }
        PyxisParam.IRIS -> thirdStops(widest, narrowest).map { Expo.formatT(it) to s.copy(tStop = it) }
        PyxisParam.ND -> listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0).map { (if (it == 0.0) "Off" else Expo.ndName(it)) to s.copy(nd = it) }
    }
    val current = options.indexOfFirst { it.second == s }.let { if (it >= 0) it else nearestIndex(param, s, options) }
    var idx by remember { mutableFloatStateOf(current.toFloat()) }

    Dialog(onDismissRequest = onClose) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B))) {
            Column(Modifier.padding(16.dp)) {
                Text(param.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                val i = idx.roundToInt().coerceIn(0, options.size - 1)
                Text(options[i].first, color = Color(0xFF7BE38C), fontWeight = FontWeight.Bold, fontSize = 30.sp,
                    modifier = Modifier.padding(vertical = 4.dp))
                Slider(
                    value = idx,
                    onValueChange = { idx = it },
                    onValueChangeFinished = { commit(options[idx.roundToInt().coerceIn(0, options.size - 1)].second) },
                    valueRange = 0f..(options.size - 1).coerceAtLeast(1).toFloat(),
                    steps = (options.size - 2).coerceAtLeast(0),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEachIndexed { j, (label, n) ->
                        FilterChip(j == i, { idx = j.toFloat(); commit(n) }, { Text(label, fontSize = 13.sp) })
                    }
                }
                Text(
                    when (param) {
                        PyxisParam.ISO -> "The PYXIS has two native ISOs, 400 and 3200: they're the cleanest. " + sendNote(link)
                        PyxisParam.SHUTTER -> "180° gives normal motion blur. 172.8° stops flicker under 60 Hz lights at 24 fps. " + sendNote(link)
                        PyxisParam.IRIS -> "Each step is a third of a stop. " + sendNote(link)
                        PyxisParam.FPS -> "Set the frame rate on the camera too; the meter uses it for exposure."
                        PyxisParam.ND -> "ND on the lens isn't something the camera reports. Set it here so the meter counts it."
                    },
                    color = Color(0xFF9A9A9A), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("Done") }
            }
        }
    }
}

private fun sendNote(link: CameraLink?) =
    if (link?.isConnected == true) "Changes go to the PYXIS right away." else "Not connected: this sets the meter only."

private fun nearestIndex(p: PyxisParam, s: CamSettings, o: List<Pair<String, CamSettings>>): Int =
    o.indices.minByOrNull { j ->
        val n = o[j].second
        when (p) {
            PyxisParam.ISO -> abs(n.iso - s.iso).toDouble()
            PyxisParam.SHUTTER -> abs(n.shutterAngle - s.shutterAngle)
            PyxisParam.FPS -> abs(n.fps - s.fps)
            PyxisParam.IRIS -> abs(n.tStop - s.tStop)
            PyxisParam.ND -> abs(n.nd - s.nd)
        }
    } ?: 0

/** Small round buttons along the edge of the camera feed. */
@Composable
fun FeedButton(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).background(if (on) Color(0xDD29B83D) else Color(0x99000000), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A see-through panel over the bottom of the feed. Swipe its handle down (or tap it) to put it away. */
@Composable
fun FeedPanel(modifier: Modifier, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier.background(Color(0xCC000000), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))) {
        Box(
            Modifier.fillMaxWidth().height(30.dp)
                .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d > 8f) onDismiss() } }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(56.dp).height(5.dp).background(Color(0xAAFFFFFF), RoundedCornerShape(3.dp)))
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
            content()
        }
    }
}
