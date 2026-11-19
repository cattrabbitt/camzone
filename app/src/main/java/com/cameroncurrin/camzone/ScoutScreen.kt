package com.cameroncurrin.camzone

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Step { START, WALK, TAG, SUBJECT, SUMMARY }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoutScreen(meter: PhoneMeter) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val session by Scout.session.collectAsState()
    val settings by AppState.settings.collectAsState()
    val cal by AppState.calibration.collectAsState()
    val phone by AppState.phone.collectAsState()
    val tracker = remember { OrientationTracker(ctx) }
    val speed by tracker.speed.collectAsState()
    DisposableEffect(Unit) {
        tracker.start()
        onDispose { tracker.stop(); meter.unlockScan() }
    }

    var step by remember { mutableStateOf(if (session == null) Step.START else Step.SUMMARY) }
    var name by remember { mutableStateOf("") }
    var walking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) } // bumps when the session's lists change
    var lastQ by remember { mutableStateOf<FloatArray?>(null) }
    var msg by remember { mutableStateOf("") }

    // Auto-shoot during the walkthrough: a photo every ~8 degrees of turn, only while the phone is steady
    LaunchedEffect(walking) {
        while (walking) {
            val s = Scout.session.value
            val q = tracker.quaternion
            val turned = if (q == null) 0f else lastQ?.let { OrientationTracker.angleBetween(it, q) } ?: 999f
            if (s != null && !busy && turned >= 8f && tracker.speed.value < 25f) {
                busy = true
                val f = s.nextPhotoFile("walk")
                val or = tracker.orientation.value
                meter.takePhoto(f) { ok ->
                    if (ok) {
                        s.photos += ScoutPhoto(f.name, or, System.currentTimeMillis())
                        lastQ = q
                        version++
                    }
                    busy = false
                }
            }
            delay(120)
        }
    }

    fun tagNow(type: TagType?) {
        val s = Scout.session.value ?: return
        val fr = AppState.frame.value ?: run { msg = "No camera view yet."; return }
        val ev = meter.sample(fr, 0.5f, 0.5f, radius = 6) + AppState.calibration.value
        val f = s.nextPhotoFile(if (type == null) "subject" else "tag")
        val or = tracker.orientation.value
        busy = true
        meter.takePhoto(f) { ok ->
            busy = false
            if (!ok) { msg = "Photo failed. Try again."; return@takePhoto }
            if (type == null) {
                s.subjectEv = ev; s.subjectPhoto = f.name
                version++
            } else {
                scope.launch {
                    val k = withContext(Dispatchers.IO) { Scout.estimateKelvin(f, AppState.phone.value.wb) }
                    s.tags += ScoutTag(type, ev, k, f.name, or)
                    version++
                }
            }
            s.save(AppState.settings.value, AppState.calibration.value)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        val stepTitle = when (step) {
            Step.START -> "Scout a location"
            Step.WALK -> "1 of 3 · Walk the room"
            Step.TAG -> "2 of 3 · Tag the light sources"
            Step.SUBJECT -> "3 of 3 · Mark the subject spot"
            Step.SUMMARY -> "Room read"
        }
        Text(stepTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))

        if (step == Step.WALK || step == Step.TAG || step == Step.SUBJECT) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Color.Black, RoundedCornerShape(10.dp))) {
                AndroidView(
                    modifier = Modifier.matchParentSize(),
                    factory = { c ->
                        PreviewView(c).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            meter.bind(lifecycle, this)
                        }
                    },
                )
                if (step != Step.WALK) {
                    Canvas(Modifier.matchParentSize()) {
                        val c = Offset(size.width / 2, size.height / 2)
                        drawCircle(Color.White, radius = size.width * 0.09f, center = c, style = Stroke(4f))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        when (step) {
            Step.START -> {
                Text(
                    "About 3 minutes. Walk the room for the PC scan, tag the windows and lamps, mark where the actor will stand. " +
                        "You get a room read with advice right away, no internet needed.",
                    color = Color(0xFFCCCCCC), fontSize = 14.sp,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Location or scene name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Button(onClick = { Scout.start(ctx, name); step = Step.WALK }) { Text("Full scout") }
                    OutlinedButton(onClick = { Scout.start(ctx, name); step = Step.TAG }) { Text("Quick read (skip walk)") }
                }
                if (session != null) {
                    TextLink("Open the last scout: ${session!!.name}") { step = Step.SUMMARY }
                }
            }

            Step.WALK -> {
                val s = session
                Text(
                    "Lay a tape measure open to 1 meter on the floor first. Then walk slowly along the walls with the phone at chest height, " +
                        "pointing across the room. Do a second lap at eye level. Photos take themselves.",
                    color = Color(0xFFCCCCCC), fontSize = 14.sp,
                )
                Text(
                    "${s?.photos?.size ?: 0} photos" + (if (version >= 0) "" else "") +
                        if (walking && speed > 35f) "  ·  Slow down" else if (walking) "  ·  Good pace" else "",
                    color = if (walking && speed > 35f) Color(0xFFFF8500) else Color(0xFF7BE38C),
                    fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 6.dp),
                )
                Text("Aim for 120 or more. Exposure is locked for the whole walk.", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (!walking) Button(onClick = { meter.lockForScan(); walking = true }) { Text(if ((s?.photos?.size ?: 0) == 0) "Start walking" else "Resume") }
                    else OutlinedButton(onClick = { walking = false }) { Text("Pause") }
                    Button(onClick = {
                        walking = false; meter.unlockScan()
                        s?.scaleNote = "1 m tape measure on the floor"
                        s?.save(settings, cal)
                        step = Step.TAG
                    }) { Text("Done walking") }
                }
            }

            Step.TAG -> {
                val s = session
                Text("Point the circle at each light source and tap what it is: windows, lamps, ceiling lights, bright walls.",
                    color = Color(0xFFCCCCCC), fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    TagType.values().forEach { t ->
                        Button(onClick = { if (!busy) tagNow(t) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))) {
                            Text(t.label, color = Color.White)
                        }
                    }
                }
                if (s != null && version >= 0) TagList(s, settings)
                Button(onClick = { step = Step.SUBJECT }, modifier = Modifier.padding(top = 10.dp)) { Text("Next: subject spot") }
            }

            Step.SUBJECT -> {
                Text("Stand where the actor will be. Point the circle at a gray card or their face height, lit by the room only. Tap Mark.",
                    color = Color(0xFFCCCCCC), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { if (!busy) tagNow(null) }) { Text("Mark subject") }
                    OutlinedButton(onClick = { session?.save(settings, cal); step = Step.SUMMARY }) {
                        Text(if (session?.subjectEv != null) "See room read" else "Skip")
                    }
                }
                session?.subjectEv?.let {
                    Text("Subject marked: ${Expo.formatStops(Expo.stopsAt(it, settings))} stops at your PYXIS settings",
                        color = Color(0xFF7BE38C), modifier = Modifier.padding(top = 8.dp))
                }
            }

            Step.SUMMARY -> {
                val s = session
                if (s == null) {
                    step = Step.START
                } else {
                    if (version >= 0) RoomRead(s, settings, phone)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        Button(onClick = { s.save(settings, cal); Scout.share(ctx, s) }) { Text("Send to PC") }
                        OutlinedButton(onClick = { step = Step.TAG }) { Text("Add tags") }
                        OutlinedButton(onClick = { step = Step.START }) { Text("New scout") }
                    }
                    Text(
                        "Send to PC zips the photos and readings. Pick Google Drive or Nearby Share, then open it in Lighting Planner on the PC.",
                        color = Color(0xFF8A8A8A), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFFB46B), modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Text(text) }
}

@Composable
private fun TagList(s: ScoutSession, settings: CamSettings) {
    s.tags.forEach { t ->
        val st = Expo.stopsAt(t.ev, settings)
        Row(Modifier.padding(top = 6.dp)) {
            Text("${t.type.label}: ${Expo.formatStops(st)} stops" + (t.kelvin?.let { " · about ${it}K" } ?: ""),
                color = Color(Expo.zone(st).argb).copy(alpha = 1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** The instant, offline read of the room: every source against the subject, plus advice. */
@Composable
fun RoomRead(s: ScoutSession, settings: CamSettings, phone: PhoneControls) {
    val subj = s.subjectEv
    Section(s.name) {
        if (subj != null) {
            val st = Expo.stopsAt(subj, settings)
            val tFor = Expo.roundT(Expo.tStopFor(subj, 1.0, settings))
            Text("Subject in room light: ${Expo.formatStops(st)} stops at ISO ${settings.iso}, ${Expo.formatT(settings.tStop)}",
                color = Color.White, fontSize = 15.sp)
            Text("With room light alone, a face at +1 needs ${Expo.formatT(tFor)}.", color = Color(0xFFCCCCCC), fontSize = 14.sp)
        } else {
            Text("No subject spot marked. Advice below compares sources to middle gray.", color = Color(0xFFCCCCCC), fontSize = 14.sp)
        }
        s.tags.forEach { t ->
            val rel = t.ev - (subj ?: Expo.grayEv(settings))
            Text(
                "${t.type.label}: ${Expo.formatStops(rel)} vs subject" + (t.kelvin?.let { " · about ${it}K" } ?: ""),
                color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text("Photos for the PC scan: ${s.photos.size}", color = Color(0xFF8A8A8A), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Section("What to do") {
        advice(s, settings).forEach { a ->
            Text("• $a", color = Color(0xFFDDDDDD), fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}

fun advice(s: ScoutSession, settings: CamSettings): List<String> {
    val out = mutableListOf<String>()
    val subj = s.subjectEv
    val base = subj ?: Expo.grayEv(settings)
    if (subj != null) {
        val st = Expo.stopsAt(subj, settings)
        when {
            st < -3 -> out += "The room is dark where the actor stands (${Expo.formatStops(st)}). Your lights will shape everything: you have full control."
            st < 0 -> out += "Room light at the subject is ${Expo.formatStops(st)}. Use it as fill and add a key about 2 stops above it for a 4:1 look."
            else -> out += "The room is already bright at the subject (${Expo.formatStops(st)}). Expect to fight daylight: ND on the lens, and a key strong enough to beat the ambient."
        }
    }
    s.tags.filter { it.type == TagType.WINDOW || it.type == TagType.DOOR }.forEach { w ->
        val d = w.ev - base
        when {
            d >= 5 -> out += "${w.type.label} is ${Expo.formatStops(d)} over the subject: it will clip behind them. Frame it out, put 0.6 to 0.9 ND gel on it, or bring the subject up with the 600d."
            d >= 3 -> out += "${w.type.label} is ${Expo.formatStops(d)} over the subject: it reads as a bright, motivated background. Keep it out of the face's side of frame, or use it as a backlight."
            d >= 1 -> out += "${w.type.label} is a strong motivated source (${Expo.formatStops(d)}). Turn the actor about 45° to it and use it as the key; bounce the other side with the 5-in-1."
            else -> out += "${w.type.label} is soft here (${Expo.formatStops(d)}). Push your key through or beside it so the light still feels like it comes from the window."
        }
    }
    val withK = s.tags.filter { it.kelvin != null }
    if (withK.size >= 2) {
        val hi = withK.maxByOrNull { it.kelvin!! }!!
        val lo = withK.minByOrNull { it.kelvin!! }!!
        if (hi.kelvin!! - lo.kelvin!! > 1000) {
            val gel = Scout.gelFor(hi.kelvin, lo.kelvin)
            out += "Mixed color: ${hi.type.label.lowercase()} about ${hi.kelvin}K vs ${lo.type.label.lowercase()} about ${lo.kelvin}K. " +
                "Either set the PYXIS and your bi-color lights to ${hi.kelvin}K and swap or gel the practical, or put $gel on the ${hi.type.label.lowercase()} to match the warm source."
        } else {
            out += "Sources are close in color (${lo.kelvin}K to ${hi.kelvin}K). Set the PYXIS white balance to about ${((lo.kelvin + hi.kelvin) / 2.0 / 100).roundToInt() * 100}K."
        }
    }
    if (s.tags.any { it.type == TagType.WINDOW }) {
        out += "Daylight moves. Re-meter the window in the Meter tab every 20 to 30 minutes so your shots still match."
    }
    if (out.isEmpty()) out += "Tag at least one light source and the subject spot to get advice."
    return out
}
