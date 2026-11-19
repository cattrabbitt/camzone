package com.cameroncurrin.camzone

import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.log2
import kotlin.math.abs
import androidx.compose.material3.Slider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Learn tab: beginner lessons, lighting techniques with diagrams, and the app guide. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LearnScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(Modifier.padding(start = 12.dp, top = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.cc_logo), "Cameron Currin logo",
                modifier = Modifier.size(44.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text("Cam Zone", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Cameron Currin", color = Color(0xFF9A9A9A), fontSize = 12.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            listOf("Basics", "Lighting techniques", "App guide").forEachIndexed { i, l -> Chip(l, tab == i) { tab = i } }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> LessonList(
                    "Start here. Each card takes about a minute. Tap one to open it.",
                    Lessons.basics,
                )
                1 -> LessonList(
                    "Real setups you can build with your kit. Each one has a top-down map: camera at the bottom, subject in the middle.",
                    Lessons.techniques,
                )
                else -> GuideScreen()
            }
        }
    }
}

@Composable
private fun LessonList(intro: String, lessons: List<Lesson>) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(intro, color = Color(0xFFAAAAAA), fontSize = 14.sp)
        lessons.forEach { LessonCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LessonCard(l: Lesson) {
    var open by rememberSaveable(l.id) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { open = !open },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(l.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(l.oneLine, color = Color(0xFF7BE38C), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            if (open) {
                l.body.forEach { p ->
                    Text(p, color = Color(0xFFDDDDDD), fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
                }
                l.diagram?.let { d ->
                    TopDownMap(lessonItems(d), Modifier.padding(top = 10.dp), subjects = l.subjects)
                }
                if (l.id == "inverse") InverseSquareCalc()
                l.gear?.let { g ->
                    Text("With your kit", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(g, color = Color(0xFFCCCCCC), fontSize = 14.sp)
                }
                if (l.tryIt.isNotEmpty()) {
                    Text("Try it", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                    l.tryIt.forEach { t -> Text("• $t", color = Color(0xFFCCCCCC), fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)) }
                }
                Text("Tap to close", color = Color(0xFF666666), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                Text("Tap to open", color = Color(0xFF666666), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}


/** On-set helper: how far to move a light for a stop change, and the stop change between two distances. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InverseSquareCalc() {
    var d by rememberSaveable { mutableStateOf(1.5f) }
    var change by rememberSaveable { mutableStateOf(-1.0) }
    val newD = d * 2.0.pow(-change / 2.0)
    Column(Modifier.padding(top = 12.dp).background(Color(0xFF242424), RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text("Distance calculator", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text("Light is now ${"%.1f".format(d)} m (${(d * 3.28f).roundToInt()} ft) from the subject", color = Color(0xFFCCCCCC), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Slider(d, { d = (it * 10).roundToInt() / 10f }, valueRange = 0.3f..8f)
        Text("I want the subject", color = Color(0xFFCCCCCC), fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-2.0, -1.0, -2.0 / 3, -1.0 / 3, 1.0 / 3, 2.0 / 3, 1.0, 2.0).forEach { c ->
                Chip(Expo.formatStops(c), abs(change - c) < 0.01) { change = c }
            }
        }
        Text(
            "Move the light to ${"%.2f".format(newD)} m (${(newD * 3.28).roundToInt()} ft): ${if (newD > d) "back" else "in"} ${"%.2f".format(abs(newD - d))} m.",
            color = Color(0xFF7BE38C), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp),
        )
        val wall = d + 1.0
        Text(
            "Falloff check: with the light at ${"%.1f".format(d)} m, something 1 m behind the subject is ${"%.1f".format(2 * log2(wall / d))} stops darker. " +
                "At ${"%.1f".format(newD)} m it would be ${"%.1f".format(2 * log2((newD + 1) / newD))} stops darker.",
            color = Color(0xFFAAAAAA), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
