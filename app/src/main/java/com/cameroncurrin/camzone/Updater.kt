package com.cameroncurrin.camzone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks the GitHub "latest" release for a newer build, downloads it and hands it to Android's installer. */
object Updater {
    private const val BASE = "https://github.com/cattrabbitt/camzone/releases/download/latest/"

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        data class Available(val build: Int) : State()
        data class Downloading(val pct: Int) : State()
        data class Ready(val file: File) : State()
        data class Failed(val why: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    var dismissed = MutableStateFlow(false)

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Cache-Control", "no-cache")
        }

    suspend fun check() = withContext(Dispatchers.IO) {
        state.value = State.Checking
        state.value = try {
            val txt = open(BASE + "version.txt").inputStream.bufferedReader().use { it.readText() }.trim()
            val remote = txt.toInt()
            if (remote > BuildConfig.VERSION_CODE) State.Available(remote) else State.UpToDate
        } catch (e: Exception) {
            State.Failed("Couldn't reach GitHub. Check your connection.")
        }
    }

    suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.getExternalFilesDir(null), "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "CamZone.apk")
            val c = open(BASE + "CamZone.apk")
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        got += n
                        if (total > 0) state.value = State.Downloading((got * 100 / total).toInt())
                    }
                }
            }
            state.value = State.Ready(out)
        } catch (e: Exception) {
            state.value = State.Failed("Download failed. Try again.")
        }
    }

    fun install(ctx: Context, file: File) {
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) {
            // First time only: Android asks to allow installs from Cam Zone
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Banner shown at the top of the app when a newer build is on GitHub. */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    val dismissed by Updater.dismissed.collectAsState()
    LaunchedEffect(Unit) { if (Updater.state.value == Updater.State.Idle) Updater.check() }
    if (dismissed) return
    val (text, action, onAct) = when (val s = st) {
        is Updater.State.Available -> Triple("Update available (build ${s.build}, you have ${BuildConfig.VERSION_CODE})", "Update") {
            scope.launch { Updater.download(ctx) }; Unit
        }
        is Updater.State.Downloading -> Triple("Downloading update… ${s.pct}%", null) { }
        is Updater.State.Ready -> Triple("Update downloaded. Tap Install, then Update on Android's screen.", "Install") {
            Updater.install(ctx, s.file)
        }
        is Updater.State.Failed -> if (s.why.startsWith("Download")) Triple(s.why, "Retry") { scope.launch { Updater.download(ctx) }; Unit } else return
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1E3B24)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) Button(onClick = onAct) { Text(action) }
        TextButton(onClick = { Updater.dismissed.value = true }) { Text("Later", color = Color(0xFFAAAAAA)) }
    }
}

/** Version line plus a manual check, for the App guide. */
@Composable
fun UpdateCheckRow() {
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("Cam Zone build ${BuildConfig.VERSION_CODE}", color = Color.White, fontWeight = FontWeight.SemiBold)
        val line = when (val s = st) {
            Updater.State.Checking -> "Checking…"
            Updater.State.UpToDate -> "You have the newest build."
            is Updater.State.Available -> "Build ${s.build} is ready. Use the green bar at the top."
            is Updater.State.Failed -> s.why
            else -> ""
        }
        if (line.isNotEmpty()) Text(line, color = Color(0xFFCCCCCC), fontSize = 13.sp)
        Button(onClick = { Updater.dismissed.value = false; scope.launch { Updater.check() } }, modifier = Modifier.padding(top = 4.dp)) {
            Text("Check for updates")
        }
    }
}
