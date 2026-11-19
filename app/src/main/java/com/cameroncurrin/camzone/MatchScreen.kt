package com.cameroncurrin.camzone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Pick a film still; get the settings, targets, light placement and tips to recreate it with your kit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MatchScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val img by Match.image.collectAsState()
    val read by Match.read.collectAsState()
    val busy by Match.busy.collectAsState()
    val err by Match.error.collectAsState()
    val kit by AppState.kit.collectAsState()
    val settings by AppState.settings.collectAsState()
    val target by AppState.matchTarget.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { Match.pick(ctx, uri); Match.read.value?.let { AppState.matchTarget.value = null } }
    }
    LaunchedEffect(Unit) { Match.restore(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Match a still", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("Pick a frame from a film or commercial you want to look like. Cam Zone reads its light, contrast, color and framing, and tells you how to get there with your kit.",
            color = Color(0xFFCCCCCC), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(if (img == null) "Pick a still" else "Pick another")
            }
            if (img != null) OutlinedButton(onClick = { Match.clear(ctx) }) { Text("Clear") }
        }
        if (busy) Text("Reading the still…", color = Color(0xFF7BE38C), modifier = Modifier.padding(top = 8.dp))
        err?.let { Text(it, color = Color(0xFFFF8500), modifier = Modifier.padding(top = 8.dp)) }

        val b = img
        if (b != null) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 10.dp))
        }
        val r = read
        if (r != null && b != null) {
            val rc = Match.recipe(r, kit, settings)
            Section(rc.title) {
                rc.summary.forEach { Text(it, color = Color(0xFFDDDDDD), fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (target == null) Button(onClick = { AppState.matchTarget.value = rc.faceTarget }) { Text("Use as my target") }
                    else OutlinedButton(onClick = { AppState.matchTarget.value = null }) { Text("Stop matching") }
                }
                if (target != null) Text("Meter suggestions now aim the face at ${Expo.formatStops(rc.faceTarget)}. On the Meter, open Match on the feed to compare and use the Ghost overlay.",
                    color = Color(0xFF7BE38C), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Section("PYXIS settings") { rc.pyxis.forEach { Bullet(it) } }
            Section("Targets to hit in Cam Zone") { rc.targets.forEach { Bullet(it) } }
            Section("Lighting") {
                TopDownMap(rc.lights.map { l ->
                    val c = when (l.shape) { MapItem.Shape.NEG -> 0xFF222222; MapItem.Shape.BOUNCE -> 0xFFDDDDDD; else -> 0xFFFFB000 }
                    MapItem(l.role, l.angle, l.distM, c, l.shape)
                })
                rc.lights.forEach { l ->
                    Text("${l.role}: ${l.gear}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(Planner.describe(l), color = Color(0xFFCCCCCC), fontSize = 14.sp)
                }
            }
            Section("Tips") { rc.tips.forEach { Bullet(it) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Bullet(t: String) {
    Text("• $t", color = Color(0xFFDDDDDD), fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
}
