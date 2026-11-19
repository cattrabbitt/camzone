package com.cameroncurrin.camzone

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Kit, project and mood pickers, the room map and the suggested setups. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanPanel(session: ScoutSession?, settings: CamSettings) {
    val kit by AppState.kit.collectAsState()
    val type by AppState.projectType.collectAsState()
    val mood by AppState.mood.collectAsState()
    val uri = LocalUriHandler.current
    var pick by remember { mutableIntStateOf(0) }

    Section("What did you bring?") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Gear.values().forEach { g ->
                Chip(g.label, g in kit) {
                    AppState.kit.value = if (g in kit) kit - g else kit + g
                    AppState.save(); pick = 0
                }
            }
        }
    }
    Section("Project and mood") {
        Text("Project", color = Color(0xFFAAAAAA), fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProjectType.values().forEach { p -> Chip(p.label, p == type) { AppState.projectType.value = p; AppState.save(); pick = 0 } }
        }
        Text("Mood", color = Color(0xFFAAAAAA), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mood.values().forEach { m -> Chip(m.label, m == mood) { AppState.mood.value = m; AppState.save(); pick = 0 } }
        }
    }

    val room = RoomFacts.from(session, settings)
    val setups = Planner.plan(kit, type, mood, room)
    if (setups.isEmpty()) return
    val sel = setups[pick.coerceIn(0, setups.size - 1)]

    Section("Suggested setups") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            setups.forEachIndexed { i, s -> Chip("${i + 1}. ${s.name}", s == sel) { pick = i } }
        }
        Text(sel.why, color = Color(0xFFDDDDDD), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        TopDownMap(Planner.mapItems(sel, session, settings), Modifier.padding(top = 8.dp))
        val note = when {
            session == null -> "No scout loaded: the map shows the lights only. Do a Quick read to add the room's windows and lamps."
            session.cameraAz == null -> "Mark the camera spot in the scout to place the room's sources on the map."
            else -> "Gray squares are the room's sources (stops vs the subject). Amber dots are your lights. Black bars are negative fill, white bars are bounce."
        }
        Text(note, color = Color(0xFF8A8A8A), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }

    Section("Where each light goes") {
        sel.lights.forEach { l ->
            Text("${l.role}: ${l.gear}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            Text(Planner.describe(l), color = Color(0xFFCCCCCC), fontSize = 14.sp)
        }
        Text("Target: ${sel.targets}.", color = Color(0xFF7BE38C), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        if (sel.missing.isNotEmpty()) {
            Text("Missing from your kit: ${sel.missing.joinToString(", ")}.", color = Color(0xFFFF8500), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    Section("Steps") {
        sel.steps.forEachIndexed { i, st ->
            Text("${i + 1}. $st", color = Color(0xFFDDDDDD), fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
        Text("Then meter the face in the Meter tab and let the suggestions fine-tune it.", color = Color(0xFF8A8A8A), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
    Section("Where this comes from") {
        sel.sources.forEach { src ->
            Text(src.title, color = Color(0xFF7FB2FF), fontSize = 13.sp, textDecoration = TextDecoration.Underline,
                modifier = Modifier.padding(vertical = 4.dp).clickable { runCatching { uri.openUri(src.url) } })
        }
    }
}

/** Top-down plan: camera at the bottom, subject in the middle, angle 0 = beside camera, + = camera-left. */
@Composable
fun TopDownMap(items: List<MapItem>, modifier: Modifier = Modifier, subjects: Int = 1) {
    Canvas(modifier.widthIn(max = 460.dp).fillMaxWidth().aspectRatio(1f).background(Color(0xFF151515), RoundedCornerShape(10.dp))) {
        val w = size.width
        val h = size.height
        val c = Offset(w / 2, h * 0.5f)
        val maxD = (items.maxOfOrNull { it.distM } ?: 3f).coerceAtLeast(3f)
        val scale = (min(w, h) / 2f - 40f) / maxD
        val txt = Paint().apply { color = android.graphics.Color.WHITE; textSize = w / 28f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val sub = Paint(txt).apply { color = android.graphics.Color.rgb(170, 170, 170); textSize = w / 34f }

        // distance rings every meter
        var r = 1f
        while (r <= maxD) {
            drawCircle(Color(0xFF2A2A2A), radius = r * scale, center = c, style = Stroke(2f))
            r += 1f
        }
        // camera
        val cam = Offset(c.x, (c.y + maxD * scale * 0.95f).coerceAtMost(h - 28f))
        drawRect(Color(0xFF7BE38C), topLeft = Offset(cam.x - 22f, cam.y - 12f), size = Size(44f, 26f))
        drawLine(Color(0xFF7BE38C), cam, c, strokeWidth = 2f, alpha = 0.4f)
        drawContext.canvas.nativeCanvas.drawText("Camera", cam.x, cam.y + 30f + txt.textSize * 0.4f, txt)
        // subject(s)
        val subjOffs = if (subjects == 2) listOf(-0.35f, 0.35f) else listOf(0f)
        subjOffs.forEach { dx -> drawCircle(Color.White, radius = 16f, center = Offset(c.x + dx * scale, c.y)) }
        drawContext.canvas.nativeCanvas.drawText(if (subjects == 2) "Subjects" else "Subject", c.x, c.y - 26f, txt)

        items.forEach { item -> drawItem(item, c, scale, txt, sub) }
    }
}

private fun DrawScope.drawItem(it: MapItem, c: Offset, scale: Float, txt: Paint, sub: Paint) {
    val a = Math.toRadians(it.angle.toDouble())
    val p = Offset((c.x - it.distM * scale * sin(a)).toFloat(), (c.y + it.distM * scale * cos(a)).toFloat())
    val col = Color(it.color.toInt())
    val toward = Offset(c.x - p.x, c.y - p.y)
    val len = kotlin.math.sqrt(toward.x * toward.x + toward.y * toward.y).coerceAtLeast(1f)
    val u = Offset(toward.x / len, toward.y / len)
    when (it.shape) {
        MapItem.Shape.LIGHT -> {
            drawLine(col.copy(alpha = 0.5f), p, Offset(p.x + u.x * 40f, p.y + u.y * 40f), strokeWidth = 6f)
            drawCircle(col, radius = 14f, center = p)
        }
        MapItem.Shape.SOURCE -> drawRect(col, topLeft = Offset(p.x - 12f, p.y - 12f), size = Size(24f, 24f))
        MapItem.Shape.NEG, MapItem.Shape.BOUNCE -> {
            val perp = Offset(-u.y, u.x)
            val a1 = Offset(p.x + perp.x * 26f, p.y + perp.y * 26f)
            val a2 = Offset(p.x - perp.x * 26f, p.y - perp.y * 26f)
            if (it.shape == MapItem.Shape.NEG) drawLine(Color(0xFF888888), a1, a2, strokeWidth = 14f)
            drawLine(col, a1, a2, strokeWidth = 10f)
        }
    }
    val below = p.y > c.y
    val ty = if (below) p.y + 36f else p.y - 22f
    drawContext.canvas.nativeCanvas.drawText(it.label, p.x.coerceIn(70f, size.width - 70f), ty, txt)
    it.sub?.let { s -> drawContext.canvas.nativeCanvas.drawText(s, p.x.coerceIn(70f, size.width - 70f), ty + sub.textSize * 1.2f, sub) }
}

/** Turn a lesson diagram into map items (lesson distances are relative, 1 = about 1.8 m). */
fun lessonItems(d: List<DLight>): List<MapItem> = d.map { l ->
    val (color, shape) = when (l.kind) {
        DLight.Kind.KEY -> 0xFFFFB000 to MapItem.Shape.LIGHT
        DLight.Kind.FILL -> 0xFFFFD98A to MapItem.Shape.LIGHT
        DLight.Kind.BACK -> 0xFF7FB2FF to MapItem.Shape.LIGHT
        DLight.Kind.PRACTICAL -> 0xFFFF9A3C to MapItem.Shape.LIGHT
        DLight.Kind.BOUNCE -> 0xFFDDDDDD to MapItem.Shape.BOUNCE
        DLight.Kind.NEG -> 0xFF222222 to MapItem.Shape.NEG
        DLight.Kind.WINDOW -> 0xFF9FD3FF to MapItem.Shape.SOURCE
    }
    MapItem(l.label, l.angle, l.dist * 1.8f, color, shape)
}
