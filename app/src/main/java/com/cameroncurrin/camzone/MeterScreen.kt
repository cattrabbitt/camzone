package com.cameroncurrin.camzone

import androidx.compose.foundation.clickable
import kotlin.math.pow
import androidx.compose.runtime.SideEffect
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

    // One view: the phone shows exactly what the CamZone Map LUT shows on the PYXIS
    val mode = Mode.ZONES
    var keyPt by remember { mutableStateOf<Spot?>(null) }
    var fillPt by remember { mutableStateOf<Spot?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCal by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PyxisParam?>(null) }
    var phonePick by remember { mutableStateOf<PyxisParam?>(null) }
    var panel by remember { mutableStateOf<String?>(null) }
    var calMsg by remember { mutableStateOf("") }
    var zoom by remember { mutableStateOf(1f) }
    var meterRead by remember { mutableStateOf<List<String>?>(null) }
    var showLens by remember { mutableStateOf(false) }
    val lensList by AppState.lenses.collectAsState()
    val lensIdx by AppState.lensIndex.collectAsState()
    val lensOn = lensList.getOrNull(lensIdx)
    val full by AppState.fullFeed.collectAsState()
    val lensNote by AppState.lensNote.collectAsState()
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
    LaunchedEffect(settings, cal, phone, linkState.connected) { meter.applyManual(settings, cal, phone) }

    // Phone mode: no PYXIS connected, so the colors describe the phone's own picture (its ISO and shutter).
    // Connected: the colors describe what the PYXIS records.
    val phoneOnly = !linkState.connected
    SideEffect { Expo.phoneMode = phoneOnly; Expo.greenShift = look.shift }
    // Iris slider on the picture: a manual lens, or the camera isn't reporting the iris
    val showIris = !phoneOnly && panel == null && (lensOn?.manual == true || lensNote != null)
    val bottomPad = if (showIris) 72.dp else 8.dp
    val vs: CamSettings = if (!phoneOnly) settings else {
        // Settings whose middle gray equals the phone's actual exposure (calibration folded in so it cancels out)
        val sec = Expo.shutterSeconds(phone.shutterAngle, phone.fps)
        val g = (frame?.exposureEv ?: Expo.ev100(meter.aperture, sec, phone.iso.toDouble())) + cal
        CamSettings(iso = phone.iso, shutterAngle = phone.shutterAngle, fps = phone.fps,
            tStop = kotlin.math.sqrt(2.0.pow(g) * sec * phone.iso / 100.0), nd = 0.0)
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val settingsStrip: @Composable () -> Unit = {
        if (phoneOnly) PhoneStrip(phone, meter.aperture, onPick = { phonePick = it }, onConnect = null) else PyxisStrip(
            settings,
            lensNote ?: if (linkState.connected) "Live from ${linkState.name} · tap a setting to change it" else if (fromCamera) "Last read from camera" else "Not connected · tap a setting to set it",
            if (lensNote != null) Color(0xFFFF8500) else if (linkState.connected) Color(0xFF29B83D) else Color(0xFF9A9A9A),
            onPick = { picker = it },
            onMore = { showSettings = true },
            onRefresh = { link()?.refresh() },
            lensName = lensList.getOrNull(lensIdx)?.name?.let { "Lens: " + it.substringBefore(" f/") },
            onLens = { showLens = true },
        )
        if (phoneOnly && linkState.type == LinkType.BLUETOOTH && linkState.status.isNotBlank()) {
            Text("PYXIS: ${linkState.status}", color = Color(0xFFFF8500), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
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
            val bmp = remember(f, vs, cal, mode, tools, phoneOnly, look) { f?.let { renderOverlay(it, vs, cal, mode == Mode.ZONES, tools) } }
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
                    // Tap = the face (key side). Press and hold = the shadow side, for the contrast ratio.
                    detectTapGestures(
                        onTap = { pos ->
                            val fr = AppState.frame.value ?: return@detectTapGestures
                            val x = pos.x / size.width; val y = pos.y / size.height
                            val sp = Spot(meter.sample(fr, x, y) + AppState.calibration.value, x, y)
                            AppState.face.value = sp; keyPt = sp
                        },
                        onLongPress = { pos ->
                            val fr = AppState.frame.value ?: return@detectTapGestures
                            val x = pos.x / size.width; val y = pos.y / size.height
                            fillPt = Spot(meter.sample(fr, x, y) + AppState.calibration.value, x, y)
                        },
                    )
                },
            ) {
                if (bmp != null) {
                    drawImage(
                        bmp.asImageBitmap(),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        alpha = 1f,
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
            // Aiming target: in Spot mode, point the center at something and tap Lock (or tap anywhere as before)
            val fa = frame
            if (panel != "Cal") {
                Canvas(Modifier.matchParentSize()) {
                    val r = size.width * 0.03f
                    val w = 3f
                    drawCircle(Color.White, radius = r, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                    drawLine(Color.White, Offset(center.x - r * 2.2f, center.y), Offset(center.x - r * 1.2f, center.y), w)
                    drawLine(Color.White, Offset(center.x + r * 1.2f, center.y), Offset(center.x + r * 2.2f, center.y), w)
                    drawLine(Color.White, Offset(center.x, center.y - r * 2.2f), Offset(center.x, center.y - r * 1.2f), w)
                    drawLine(Color.White, Offset(center.x, center.y + r * 1.2f), Offset(center.x, center.y + r * 2.2f), w)
                }
                if (fa != null && panel == null) {
                    val aimEv = meter.sample(fa, 0.5f, 0.5f, radius = 4) + cal
                    val aimSt = Expo.stopsAt(aimEv, vs)
                    Row(Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottomPad), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Aim " + Expo.formatStops(aimSt), color = Color(Expo.zone(aimSt).argb), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                        Button(onClick = { AppState.face.value = Spot(aimEv, 0.5f, 0.5f) }) { Text("Lock as face") }
                    }
                }
            }
            // Light meter button, bottom-right: reads what's in the target and gives the stops
            if (panel == null && fa != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottomPad).size(64.dp)
                        .background(Color(0xEE29B83D), androidx.compose.foundation.shape.CircleShape)
                        .clickable { meterRead = lightMeter(meter.sample(fa, 0.5f, 0.5f, radius = 4) + cal, vs, look, phoneOnly, AppState.widestT.value, AppState.narrowestT.value) },
                    contentAlignment = Alignment.Center,
                ) { Text("METER", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            meterRead?.let { lines ->
                Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 56.dp, end = 64.dp)
                    .background(Color(0xE6000000), RoundedCornerShape(10.dp)).clickable { meterRead = null }.padding(10.dp)) {
                    Text(lines.first(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    lines.drop(1).forEach { Text(it, color = Color(0xFFDDDDDD), fontSize = 13.sp) }
                    Text("Tap to close", color = Color(0xFF888888), fontSize = 11.sp)
                }
            }
            // Phone zoom, bottom-left (zooms the preview and the readings together)
            if (panel == null) {
                if (showIris) IrisBar(lensOn ?: Lens("Lens", AppState.widestT.value, AppState.narrowestT.value, true), settings,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottomPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1f, 2f, 4f).forEach { z ->
                        FeedButton(if (z == 1f) "1×" else "${z.toInt()}×", zoom == z) { zoom = z; meter.setZoom(z) }
                    }
                }
            }
            // Reading badge, top-left
            val badge = if (mode == Mode.CONTRAST) {
                val k = keyPt; val fl = fillPt
                if (k != null && fl != null) "Contrast " + Expo.ratio(k.ev - fl.ev) else null
            } else face?.let { "Face " + Expo.formatStops(Expo.stopsAt(it.ev, vs)) }
            if (badge != null) {
                val col = if (mode == Mode.CONTRAST) Color.White else face?.let { Color(Expo.zone(Expo.stopsAt(it.ev, vs)).argb) } ?: Color.White
                Text(badge, color = col, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            // Setting buttons on the feed, right edge
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("Phone", "Tools", "View", "Cal") + if (matchImg != null) listOf("Match") else emptyList()).forEach { n ->
                    FeedButton(n, panel == n) { panel = if (panel == n) null else n }
                }
                FeedButton(if (full) "Exit" else "Full", full) { AppState.fullFeed.value = !full }
            }
            if (panel == "Cal") {
                // Calibration target: the phone measures inside this circle
                Canvas(Modifier.matchParentSize()) {
                    val r = size.width * 4f / (frame?.width ?: 240).coerceAtLeast(1) * 1.4f
                    drawCircle(Color.White, radius = r, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                    drawCircle(Color.Black, radius = r + 4f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                }
            }
            if (panel != null) {
                FeedPanel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f), onDismiss = { panel = null }) {
                    when (panel) {
                        "Phone" -> PhoneControlsBody(lenses)
                        "Tools" -> ToolsRow()
                        "Cal" -> {
                            Text("Calibrate with the gray card", color = Color.White, fontWeight = FontWeight.Bold)
                            if (phoneOnly) Text("Connect the PYXIS first: calibration matches the phone to the camera.", color = Color(0xFFFF8500), fontSize = 13.sp)
                            Text("1. Check these match the PYXIS: ISO ${settings.iso} · ${fmt(settings.shutterAngle)}° · ${fmt(settings.fps)} fps · ${Expo.formatT(settings.tStop)}" +
                                (if (settings.nd > 0) " · ${Expo.ndName(settings.nd)}" else "") + ". If not, tap them in the top strip.",
                                color = Color(0xFFDDDDDD), fontSize = 13.sp)
                            Text("2. On the PYXIS, set the iris so the card is middle gray (light green on the Map LUT, green on false color).", color = Color(0xFFDDDDDD), fontSize = 13.sp)
                            Text("3. Put the card inside the white circle and tap Calibrate.", color = Color(0xFFDDDDDD), fontSize = 13.sp)
                            val fr = frame
                            if (fr != null && !phoneOnly) {
                                val now = Expo.stopsAt(meter.sample(fr, 0.5f, 0.5f, radius = 4) + cal, settings)
                                Text("Circle reads ${Expo.formatStops(now)} (should be 0 after calibrating)", color = Color(Expo.zone(now).argb), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Button(enabled = !phoneOnly, onClick = {
                                    val f = AppState.frame.value
                                    if (f == null) calMsg = "No camera view yet." else {
                                        AppState.calibration.value = Expo.grayEv(settings) + Expo.RATING - meter.sample(f, 0.5f, 0.5f, radius = 4)
                                        LensKit.saveCal(AppState.calibration.value)
                                        AppState.save()
                                        calMsg = "Calibrated. Offset ${Expo.formatStops(AppState.calibration.value)} stops."
                                    }
                                }) { Text("Calibrate") }
                                OutlinedButton(onClick = { AppState.calibration.value = 0.0; AppState.save(); calMsg = "Reset." }) { Text("Reset") }
                            }
                            if (calMsg.isNotEmpty()) Text(calMsg, color = Color(0xFF7BE38C), fontSize = 13.sp)
                        }
                        "Match" -> {
                            val rr = matchRead
                            if (rr != null) {
                                val rc = Match.recipe(rr, AppState.kit.value, settings)
                                Text(rc.title, color = Color.White, fontWeight = FontWeight.Bold)
                                val fNow = face?.let { Expo.formatStops(Expo.stopsAt(it.ev, vs)) } ?: "tap the face"
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
                            Text("Which CamZone LUT is on the PYXIS (the phone shows the same one)", color = Color(0xFF9A9A9A), fontSize = 12.sp)
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
        if (tools.histogram) StopHistogram(f = frame, s = vs, cal = cal)
        Spacer(Modifier.height(6.dp))
        // Readouts
        run {
            val k = keyPt
            val fl = fillPt
            face?.let {
                val s = Expo.stopsAt(it.ev, vs)
                val z = Expo.zone(s)
                ReadoutCard("Face ${Expo.formatStops(s)} stops · ${z.label}", Color(z.argb))
            }
            if (k != null || fl != null) ReadoutCard(
                when {
                    k == null -> "Tap the bright (key) side of the face"
                    fl == null -> "Press and hold the shadow side of the face for the contrast ratio"
                    else -> {
                        val d = k.ev - fl.ev
                        "Contrast ${Expo.ratio(d)} · ${Expo.formatStops(abs(d)).removePrefix("+")} stops\n" +
                            "Key ${Expo.formatStops(Expo.stopsAt(k.ev, vs))} · Fill ${Expo.formatStops(Expo.stopsAt(fl.ev, vs))}\n" +
                            ratioHint(d)
                    }
                },
            )
        }

        // Advice
        @Suppress("UNUSED_VARIABLE") val mt = matchTarget // re-run advice when the match target changes
        val list = if (phoneOnly) phoneAdvice(face, vs, look) else Advisor.build(settings, look, face, frame, cal, widest, narrowest)
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
                        is Action.SetPhoneIso -> { AppState.phone.value = AppState.phone.value.copy(iso = a.iso, matchPyxis = false) }
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
            OutlinedButton(onClick = { panel = "Cal"; calMsg = "" }) { Text(if (cal == 0.0) "Calibrate with gray card" else "Recalibrate") }
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
    phonePick?.let { pp -> PhonePicker(pp, phone) { phonePick = null } }
    if (showLens) LensPicker { showLens = false }
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
                a is Action.SetPhoneIso ->
                    Button(onClick = onApply, modifier = Modifier.padding(top = 8.dp)) { Text("Set phone ISO ${a.iso}") }
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
                    AppState.calibration.value = Expo.grayEv(s) + Expo.RATING - raw
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


/**
 * Classic spot meter on whatever is in the target: the stops it sits at now, the EV and light level,
 * and the iris (or ISO) that puts it at middle gray and at the Look's face target.
 */
fun lightMeter(ev: Double, s: CamSettings, look: Look, phoneOnly: Boolean, widest: Double, narrowest: Double): List<String> {
    val now = Expo.stopsAt(ev, s)
    val ev100 = ev
    val lux = 2.5 * 2.0.pow(ev100)            // reflected reading of an average (18%) subject
    val fc = lux / 10.76
    val target = AppState.matchTarget.value ?: look.target
    fun irisFor(t: Double): String {
        val need = Expo.tStopFor(ev, t, s)
        val r = Expo.roundT(need)
        return when {
            phoneOnly -> "phone ISO " + (s.iso / 2.0.pow(Expo.stopsAt(ev, s) - t)).roundToInt()
            need < widest * 0.97 -> "${Expo.formatT(widest)} wide open is ${Expo.formatStops(Expo.stopsBetween(need, widest)).removePrefix("+")} short: add light or ISO 3200"
            need > narrowest * 1.03 -> "past ${Expo.formatT(narrowest)}: add ND ${Expo.ndName(Expo.stopsBetween(narrowest, need))}"
            else -> Expo.formatT(r) + (if (s.nd > 0) " (with ${Expo.ndName(s.nd)})" else "")
        }
    }
    return listOf(
        "${Expo.formatStops(now)} stops · ${Expo.zone(now).label}",
        "EV ${"%.1f".format(ev100)} · about ${fc.roundToInt()} fc (${lux.roundToInt()} lux) on an average surface",
        "Gray card on the line: ${irisFor(Expo.RATING)}",
        "As a face at ${Expo.formatStops(target)}: ${irisFor(target)}",
        "At ISO ${s.iso}, ${fmt(s.shutterAngle)}°, ${fmt(s.fps)} fps",
    )
}
