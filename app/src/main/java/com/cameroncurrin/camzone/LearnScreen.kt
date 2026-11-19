package com.cameroncurrin.camzone

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
