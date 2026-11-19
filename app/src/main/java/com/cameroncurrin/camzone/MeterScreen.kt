package com.cameroncurrin.camzone

import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Mode(val label: String) { ZONES("Zones"), SPOT("Spot"), CONTRAST("Contrast") }

@Composable
fun MeterScreen(meter: PhoneMeter, link: () -> CameraLink?) {
    val settings by AppState.settings.collectAsState()
    val fromCamera by AppState.settingsFromCamera.collectAsState()
    val frame by AppState.frame.collectAsState()
    val cal by AppState.calibration.collectAsState()
    val look by AppState.look.collectAsState()
    val face by AppState.face.collectAsState()
    val linkState by AppState.link.collectAsState()
    val widest by AppState.widestT.collectAsState()
    val narrowest by AppState.narrowestT.collectAsState()
    val applyStatus by AppState.applyStatus.collectAsState()

    var mode by remember { mutableStateOf(Mode.ZONES) }
    var keyPt by remember { mutableStateOf<Spot?>(null) }
    var fillPt by remember { mutableStateOf<Spot?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCal by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PyxisParam?>(null) }
    var panel by remember { mutableStateOf<String?>(null) }
    val full by AppState.fullFeed.collectAsState()
    val matchImg by Match.image.collectAsState()
    val matchRead by Match.read.collectAsState()
    val ghost by Match.ghost.collectAsState()
    val matchTarget by AppState.matchTarget.collectAsState()
    // Full screen also hides the phone's status and navigation bars
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(full) {
        val win = (view.context as? android.app.Activity)?.window
        if (win != null) {
            val c = androidx.core.view.WindowCompat.getInsetsController(win, view)
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (full) c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (win != null) androidx.core.view.WindowCompat.getInsetsController(win, view).show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val phoneStatus by meter.status.collectAsState()
    val phone by AppState.phone.collectAsState()
    val tools by AppState.tools.collectAsState()
    val lenses by meter.lenses.collectAsState()

    // Keep the phone camera in manual, locked to the PYXIS settings or the manual controls
    LaunchedEffect(settings, cal, phone) { meter.applyManual(settings, cal, phone) }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val settingsStrip: @Composable () -> Unit = {
        PyxisStrip(
            settings,
            if (linkState.connected) "Live from ${linkState.name} · tap a setting to change it" else if (fromCamera) "Last read from camera" else "Not connected · tap a setting to set it",
            if (linkState.connected) Color(0xFF29B83D) else Color(0xFF9A9A9A),
            onPick = { picker = it },
            onMore = { showSettings = true },
        )
    }
    val previewBox: @Composable (Modifier) -> Unit = { m ->
        // Camera view with zone overlay
        Box(m.background(Color.Black, RoundedCornerShape(10.dp))) {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        meter.bind(lifecycle, this)
                    }
                },
            )
            val f = frame
            val bmp = remember(f, settings, cal, mode, tools) { f?.let { renderOverlay(it, settings, cal, mode == Mode.ZONES, tools) } }
            Canvas(
                Modifier.matchParentSize()
                    .pointerInput(Unit) {
                        // Swipe up on the picture for full screen, down to come back
                        detectVerticalDragGestures { _, d ->
                            if (d < -12f) AppState.fullFeed.value = true
                            else if (d > 12f) { if (panel != null) panel = null else AppState.fullFeed.value = false }
                        }
                    }
                    .pointerInput(mode) {
                    detectTapGestures { pos ->
                        val fr = AppState.frame.value ?: return@detectTapGestures
                        val x = pos.x / size.width
                        val y = pos.y / size.height
                        val ev = meter.sample(fr, x, y) + AppState.calibration.value
                        when (mode) {
                            Mode.SPOT, Mode.ZONES -> AppState.face.value = Spot(ev, x, y)
                            Mode.CONTRAST -> if (keyPt == null || fillPt != null) {
                                keyPt = Spot(ev, x, y); fillPt = null
                            } else {
                                fillPt = Spot(ev, x, y)
                            }
                        }
                        Unit
                    }
                },
            ) {
                if (bmp != null) {
                    drawImage(
                        bmp.asImageBitmap(),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        alpha = if (mode == Mode.ZONES) 0.9f else 1f,
                        filterQuality = FilterQuality.Low,
                    )
                }
                drawGuides(tools)
                fun marker(s: Spot?, c: Color) {
                    if (s == null) return
                    drawCircle(c, radius = 22f, center = Offset(s.x * size.width, s.y * size.height), style = Stroke(5f))
                }
                if (mode == Mode.CONTRAST) {
                    marker(keyPt, Color(0xFFFFFFFF)); marker(fillPt, Color(0xFF1F61EB))
                } else marker(face, Color.White)
            }
            // Ghost: the reference still over the live picture, for framing and light direction
            val gi = matchImg
            if (ghost && gi != null) {
                val gb = remember(gi) { gi.asImageBitmap() }
                androidx.compose.foundation.Image(gb, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = 0.4f, modifier = Modifier.matchParentSize())
            }
            // Reading badge, top-left
            val badge = if (mode == Mode.CONTRAST) {
                val k = keyPt; val fl = fillPt
                if (k != null && fl != null) "Contrast " + Expo.ratio(k.ev - fl.ev) else null
            } else face?.let { "Face " + Expo.formatStops(Expo.stopsAt(it.ev, settings)) }
            if (badge != null) {
                val col = if (mode == Mode.CONTRAST) Color.White else face?.let { Color(Expo.zone(Expo.stopsAt(it.ev, settings)).argb) } ?: Color.White
                Text(badge, color = col, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            // Setting buttons on the feed, right edge
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("Phone", "Tools", "View") + if (matchImg != null) listOf("Match") else emptyList()).forEach { n ->
                    FeedButton(n, panel == n) { panel = if (panel == n) null else n }
                }
                FeedButton(if (full) "Exit" else "Full", full) { AppState.fullFeed.value = !full }
            }
            if (panel != null) {
                FeedPanel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f), onDismiss = { panel = null }) {
                    when (panel) {
                        "Phone" -> PhoneControlsBody(lenses)
                        "Tools" -> ToolsRow()
                        "Match" -> {
                            val rr = matchRead
                            if (rr != null) {
                                val rc = Match.recipe(rr, AppState.kit.value, settings)
                                Text(rc.title, color = Color.White, fontWeight = FontWeight.Bold)
                                val fNow = face?.let { Expo.formatStops(Expo.stopsAt(it.ev, settings)) } ?: "tap the face"
                                Text("Face: target ${Expo.formatStops(rc.faceTarget)} · now $fNow", color = Color(0xFFDDDDDD), fontSize = 14.sp)
                                val k = keyPt; val fl = fillPt
                                val cNow = if (k != null && fl != null) Expo.ratio(k.ev - fl.ev) else "use Contrast mode"
                                Text("Contrast: target ${Expo.ratio(rc.ratioTarget)} · now $cNow", color = Color(0xFFDDDDDD), fontSize = 14.sp)
                                Text("Background: ${Expo.formatStops(rc.bgTarget)} vs the face", color = Color(0xFFDDDDDD), fontSize = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    FilterChip(ghost, { Match.ghost.value = !ghost }, { Text("Ghost overlay") })
                                    FilterChip(matchTarget != null, {
                                        AppState.matchTarget.value = if (matchTarget != null) null else rc.faceTarget
                                    }, { Text("Aim at this face target") })
                                }
                            }
                        }
                        else -> {
                            Text("Mode", color = Color(0xFF9A9A9A), fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Mode.values().forEach { md ->
                                    FilterChip(selected = mode == md, onClick = { mode = md }, label = { Text(md.label) })
                                }
                            }
                            Text("Look", color = Color(0xFF9A9A9A), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Look.values().forEach { l ->
                                    FilterChip(selected = look == l, onClick = { AppState.look.value = l; AppState.save() }, label = { Text(l.label) })
                                }
                            }
                        }
                    }
                    Text("Swipe the bar down to close", color = Color(0xFF8A8A8A), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Text(
            phoneStatus + if (mode == Mode.ZONES) "" else " · gray view shows what the PYXIS would see",
            color = Color(0xFF8A8A8A), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
        )
        if (tools.histogram) StopHistogram(f = frame, s = settings, cal = cal)
        Spacer(Modifier.height(6.dp))
        // Readouts
        if (mode == Mode.CONTRAST) {
            val k = keyPt
            val fl = fillPt
            ReadoutCard(
                when {
                    k == null -> "Tap the bright (key) side of the face"
                    fl == null -> "Key ${Expo.formatStops(Expo.stopsAt(k.ev, settings))}. Now tap the shadow (fill) side"
                    else -> {
                        val d = k.ev - fl.ev
                        "Contrast ${Expo.ratio(d)} · ${Expo.formatStops(abs(d)).removePrefix("+")} stops\n" +
                            "Key ${Expo.formatStops(Expo.stopsAt(k.ev, settings))} · Fill ${Expo.formatStops(Expo.stopsAt(fl.ev, settings))}\n" +
                            ratioHint(d)
                    }
                },
            )
        } else {
            face?.let {
                val s = Expo.stopsAt(it.ev, settings)
                val z = Expo.zone(s)
                ReadoutCard("Face ${Expo.formatStops(s)} stops · ${z.label}", Color(z.argb))
            }
        }

        // Advice
        @Suppress("UNUSED_VARIABLE") val mt = matchTarget // re-run advice when the match target changes
        val list = Advisor.build(settings, look, face, frame, cal, widest, narrowest)
        list.forEach { sug ->
            SuggestionCard(
                sug, applyStatus[sug.id], linkState.connected,
                onApply = {
                    when (val a = sug.action) {
                        is Action.SetIris, is Action.SetIso -> {
                            val l = link()
                            if (l == null) {
                                AppState.applyStatus.value = AppState.applyStatus.value + (sug.id to ApplyStatus.FAILED)
                            } else {
                                AppState.applyStatus.value = AppState.applyStatus.value + (sug.id to ApplyStatus.SENDING)
                                if (a is Action.SetIris) l.setIris(a.t) else if (a is Action.SetIso) l.setIso(a.iso)
                                scope.launch {
                                    delay(3000)
                                    val s = AppState.settings.value
                                    val done = when (a) {
                                        is Action.SetIris -> abs(Expo.stopsBetween(s.tStop, a.t)) < 0.17
                                        is Action.SetIso -> s.iso == a.iso
                                        else -> true
                                    }
                                    AppState.applyStatus.value = AppState.applyStatus.value - sug.id +
                                        (if (done) emptyMap<String, ApplyStatus>() else mapOf(sug.id to ApplyStatus.FAILED))
                                }
                            }
                        }
                        is Action.AddNd -> {
                            AppState.settings.value = settings.copy(nd = settings.nd + a.stops); AppState.save()
                        }
                        Action.Remeter -> { AppState.face.value = null }
                        null -> Unit
                    }
                    Unit
                },
                onDidIt = {
                    when (val a = sug.action) {
                        is Action.SetIris -> AppState.settings.value = settings.copy(tStop = a.t)
                        is Action.SetIso -> AppState.settings.value = settings.copy(iso = a.iso)
                        else -> Unit
                    }
                    AppState.save()
                    AppState.applyStatus.value = AppState.applyStatus.value - sug.id
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showCal = true }) { Text(if (cal == 0.0) "Calibrate with gray card" else "Recalibrate") }
            OutlinedButton(onClick = { AppState.face.value = null; keyPt = null; fillPt = null }) { Text("Clear") }
        }
        Text(
            "The phone meters the scene; the PYXIS Map LUT is the final word. Very bright points can read low on the phone.",
            color = Color(0xFF8A8A8A), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
    }
    if (full) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            previewBox(if (landscape) Modifier.fillMaxHeight().aspectRatio(4f / 3f) else Modifier.fillMaxWidth().aspectRatio(3f / 4f))
        }
    } else if (landscape) {
        Row(Modifier.fillMaxSize().padding(8.dp)) {
            previewBox(Modifier.fillMaxHeight().aspectRatio(4f / 3f))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 10.dp)) {
                settingsStrip()
                Spacer(Modifier.height(6.dp))
                controls()
            }
        }
    } else {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            settingsStrip()
            Spacer(Modifier.height(10.dp))
            previewBox(Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            controls()
        }
    }

    picker?.let { pp -> PyxisPicker(pp, settings, link()) { picker = null } }
    if (showSettings) SettingsDialog(settings) { showSettings = false }
    if (showCal) CalibrateDialog(meter, settings) { showCal = false }
}

private fun ratioHint(stops: Double): String {
    val a = abs(stops)
    return when {
        a < 0.75 -> "Flat, even light"
        a < 1.5 -> "Soft and natural (about 2:1 to 3:1)"
        a < 2.5 -> "Dramatic (about 4:1)"
        else -> "Hard and moody (8:1 or more)"
    }
}

fun fmt(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

fun zoneBitmap(f: ZoneFrame, s: CamSettings, cal: Double): Bitmap {
    val gray = Expo.grayEv(s)
    val px = IntArray(f.width * f.height) { i -> Expo.shadedColor(f.ev[i] + cal - gray) }
    return Bitmap.createBitmap(px, f.width, f.height, Bitmap.Config.ARGB_8888)
}

@Composable
fun ReadoutCard(text: String, swatch: Color? = null) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (swatch != null) {
                Box(Modifier.size(22.dp).background(swatch, RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SuggestionCard(s: Suggestion, status: ApplyStatus?, connected: Boolean, onApply: () -> Unit, onDidIt: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (s.good) Color(0xFF13331A) else Color(0xFF1B1B1B)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(s.title, color = if (s.good) Color(0xFF7BE38C) else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(s.detail, color = Color(0xFFCCCCCC), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            val a = s.action
            when {
                status == ApplyStatus.FAILED -> {
                    Text(
                        (if (connected) "The camera didn't take it. " else "") + (s.manual ?: ""),
                        color = Color(0xFFFFB46B), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onDidIt, modifier = Modifier.padding(top = 6.dp)) { Text("I did it") }
                }
                status == ApplyStatus.SENDING -> Text("Sending to the PYXIS…", color = Color(0xFF9A9A9A), modifier = Modifier.padding(top = 8.dp))
                a is Action.SetIris || a is Action.SetIso ->
                    Button(onClick = onApply, modifier = Modifier.padding(top = 8.dp)) { Text(if (connected) "Apply" else "Show me how") }
                a is Action.AddNd -> {
                    Text(s.manual ?: "", color = Color(0xFFCCCCCC), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = onApply) { Text("Done, ND added") }
                        OutlinedButton(onClick = { AppState.face.value = null }) { Text("Re-meter") }
                    }
                }
                a == Action.Remeter -> {
                    Text(s.manual ?: "", color = Color(0xFFCCCCCC), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Button(onClick = onApply, modifier = Modifier.padding(top = 6.dp)) { Text("Re-meter") }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(s: CamSettings, onClose: () -> Unit) {
    var iso by remember { mutableStateOf(s.iso.toString()) }
    var angle by remember { mutableStateOf(fmt(s.shutterAngle)) }
    var fps by remember { mutableStateOf(fmt(s.fps)) }
    var t by remember { mutableStateOf(fmt(s.tStop)) }
    var nd by remember { mutableStateOf(fmt(s.nd)) }
    var widest by remember { mutableStateOf(fmt(AppState.widestT.value)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("PYXIS settings") },
        text = {
            Column {
                Text("When the PYXIS is connected these update by themselves, except ND.", fontSize = 13.sp)
                Field("ISO", iso) { iso = it }
                Field("Shutter angle (°)", angle) { angle = it }
                Field("Frame rate (fps)", fps) { fps = it }
                Field("T-stop", t) { t = it }
                Field("ND on the lens, in stops (0.3 = 1, 0.6 = 2, 0.9 = 3)", nd) { nd = it }
                Field("Widest T-stop of this lens", widest) { widest = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                AppState.settings.value = CamSettings(
                    iso = iso.toIntOrNull() ?: s.iso,
                    shutterAngle = angle.toDoubleOrNull() ?: s.shutterAngle,
                    fps = fps.toDoubleOrNull() ?: s.fps,
                    tStop = t.toDoubleOrNull() ?: s.tStop,
                    nd = nd.toDoubleOrNull() ?: s.nd,
                )
                widest.toDoubleOrNull()?.let { AppState.widestT.value = it }
                AppState.save()
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

@Composable
fun CalibrateDialog(meter: PhoneMeter, s: CamSettings, onClose: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Calibrate with the gray card") },
        text = {
            Column {
                Text(
                    "1. Light the gray card the way you'll light the scene.\n" +
                        "2. On the PYXIS (Map LUT), set the iris so the card shows light green, middle gray.\n" +
                        "3. Make sure the settings at the top of this screen match the camera (connect it, or tap Edit).\n" +
                        "4. Fill the center of the phone's view with the card, from the same angle as the PYXIS, then tap Calibrate.",
                    fontSize = 14.sp,
                )
                if (msg.isNotEmpty()) Text(msg, color = Color(0xFF7BE38C), modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                val f = AppState.frame.value
                if (f == null) {
                    msg = "No camera view yet. Allow camera access and try again."
                } else {
                    val raw = meter.sample(f, 0.5f, 0.5f, radius = 4)
                    AppState.calibration.value = Expo.grayEv(s) - raw
                    AppState.save()
                    msg = "Calibrated. Offset ${"%.2f".format(AppState.calibration.value)} stops."
                }
            }) { Text("Calibrate") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { AppState.calibration.value = 0.0; AppState.save(); msg = "Reset." }) { Text("Reset") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}
