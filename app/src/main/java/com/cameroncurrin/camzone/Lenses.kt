package com.cameroncurrin.camzone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One of Cam's lenses. [manual]: no electronic iris, so the camera can't report or change it.
 * [cal]: this lens's own gray-card calibration (vintage glass often passes less light than marked).
 */
data class Lens(val name: String, val widest: Double, val narrowest: Double, val manual: Boolean, val cal: Double = 0.0) {
    fun json(): JSONObject = JSONObject().put("n", name).put("w", widest).put("x", narrowest).put("m", manual).put("c", cal)
    companion object {
        fun from(o: JSONObject) = Lens(o.getString("n"), o.getDouble("w"), o.getDouble("x"), o.getBoolean("m"), o.optDouble("c", 0.0))
    }
}

object LensKit {
    /** Starting list from Cam's kit. Max apertures are the marked f-numbers; edit any of them in the app. */
    val defaults = listOf(
        Lens("Canon EF 24-105 f/4L", 4.0, 22.0, false),
        Lens("Sigma 18-35 f/1.8 Art (crop)", 1.8, 16.0, false),
        Lens("Tokina 11-16 f/2.8 (crop)", 2.8, 22.0, false),
        Lens("Zeiss Distagon 28 f/2.8", 2.8, 22.0, true),
        Lens("Takumar 50 f/1.4", 1.4, 16.0, true),
        Lens("Helios-44M-4 58 f/2", 2.0, 16.0, true),
        Lens("Industar-50-2 50 f/3.5", 3.5, 16.0, true),
        Lens("Carenar 135 f/2.8", 2.8, 22.0, true),
        Lens("Canon FD 70-210 f/4", 4.0, 22.0, true),
    )

    fun load(s: String?): List<Lens> = try {
        if (s == null) defaults else JSONArray(s).let { a -> (0 until a.length()).map { Lens.from(a.getJSONObject(it)) } }
    } catch (e: Exception) { defaults }

    fun save(l: List<Lens>): String = JSONArray().also { a -> l.forEach { a.put(it.json()) } }.toString()

    /** Third stops from wide open to the smallest opening. */
    fun stops(l: Lens): List<Double> {
        val out = mutableListOf<Double>()
        var i = 0
        while (true) {
            val t = 2.0.pow(i / 6.0)
            if (t > l.narrowest * 1.01) break
            if (t >= l.widest * 0.97) out += Expo.roundT(t)
            i++
        }
        if (out.isEmpty() || abs(out.first() - l.widest) > 0.05) out.add(0, l.widest)
        return out.distinct()
    }

    /** Puts a lens on: lens range, wide open, and its own calibration. */
    fun select(i: Int) {
        val l = AppState.lenses.value.getOrNull(i) ?: return
        AppState.lensIndex.value = i
        AppState.widestT.value = l.widest
        AppState.narrowestT.value = l.narrowest
        AppState.calibration.value = l.cal
        AppState.settings.value = AppState.settings.value.copy(tStop = l.widest)
        AppState.lensNote.value = null
        AppState.save()
    }

    fun current(): Lens? = AppState.lenses.value.getOrNull(AppState.lensIndex.value)

    /** Stores a new calibration on the lens that's on. */
    fun saveCal(c: Double) {
        val i = AppState.lensIndex.value
        val l = AppState.lenses.value.getOrNull(i) ?: return
        AppState.lenses.value = AppState.lenses.value.toMutableList().also { it[i] = l.copy(cal = c) }
        AppState.save()
    }
}

/** Pick the lens that's on the PYXIS, or add one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LensPicker(onClose: () -> Unit) {
    val lenses by AppState.lenses.collectAsState()
    val sel by AppState.lensIndex.collectAsState()
    var name by remember { mutableStateOf("") }
    var wide by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B))) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("Which lens is on?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Picking a lens sets its range, puts the iris wide open, and loads its own calibration. Manual lenses get an iris slider on the picture.",
                    color = Color(0xFF9A9A9A), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                lenses.forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip(l.name + if (l.manual) " · manual" else "", i == sel) { LensKit.select(i); onClose() }
                        TextButton(onClick = {
                            AppState.lenses.value = lenses.filterIndexed { j, _ -> j != i }
                            if (sel >= AppState.lenses.value.size) AppState.lensIndex.value = -1
                            AppState.save()
                        }) { Text("Remove", fontSize = 11.sp, color = Color(0xFF888888)) }
                    }
                }
                Text("Add a lens", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(wide, { wide = it }, label = { Text("Widest f or T (e.g. 1.8)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(manual, { manual = it }); Text("Manual (no electronic iris)", color = Color.White, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val w = wide.replace("f/", "").replace("T", "").toDoubleOrNull()
                        if (name.isNotBlank() && w != null) {
                            AppState.lenses.value = lenses + Lens(name.trim(), w, 22.0, manual)
                            AppState.save()
                            LensKit.select(AppState.lenses.value.size - 1)
                            onClose()
                        }
                    }) { Text("Add and use") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

/** Iris slider along the bottom of the picture, for manual lenses. Match it to the ring. */
@Composable
fun IrisBar(lens: Lens, s: CamSettings, modifier: Modifier) {
    val stops = remember(lens) { LensKit.stops(lens) }
    val cur = stops.indices.minByOrNull { abs(stops[it] - s.tStop) } ?: 0
    var idx by remember(lens) { mutableStateOf(cur.toFloat()) }
    Column(modifier.background(Color(0xBB000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 2.dp)) {
        val i = idx.roundToInt().coerceIn(0, stops.size - 1)
        Text("Iris ${"f/%.1f".format(stops[i])} · match the lens ring", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = idx,
            onValueChange = {
                idx = it
                val t = stops[it.roundToInt().coerceIn(0, stops.size - 1)]
                if (abs(t - AppState.settings.value.tStop) > 0.01) {
                    AppState.settings.value = AppState.settings.value.copy(tStop = t)
                }
            },
            onValueChangeFinished = { AppState.save() },
            valueRange = 0f..(stops.size - 1).coerceAtLeast(1).toFloat(),
            steps = (stops.size - 2).coerceAtLeast(0),
        )
    }
}
