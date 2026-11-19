package com.cameroncurrin.camzone

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private lateinit var meter: PhoneMeter
    private lateinit var ble: PyxisBle
    private lateinit var rest: PyxisRest

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.load(this)
        meter = PhoneMeter(this)
        ble = PyxisBle(this)
        rest = PyxisRest(lifecycleScope)
        askCamera.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF29B83D),
                secondary = Color(0xFFFF8500),
                background = Color(0xFF111111),
                surface = Color(0xFF1B1B1B),
            )) {
                App(meter, ble, rest)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        meter.updateRotation()
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.disconnect()
        rest.disconnect()
    }
}

/** Bluetooth permissions differ by Android version. */
fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun App(meter: PhoneMeter, ble: PyxisBle, rest: PyxisRest) {
    var tab by remember { mutableIntStateOf(0) }
    var navShown by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    // The menu hides itself a few seconds after you last used it
    LaunchedEffect(navShown, poke) {
        if (navShown) { delay(3500); navShown = false }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        UpdateBanner()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> MeterScreen(meter) { linkFor(ble, rest) }
                1 -> ScoutScreen(meter)
                2 -> LinkScreen(ble, rest)
                else -> LearnScreen()
            }
            if (!navShown) {
                // Swipe up (or tap) on the handle to bring the menu back
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, drag -> if (drag < -6f) navShown = true }
                        }
                        .pointerInput(Unit) { detectTapGestures { navShown = true } },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(64.dp).height(5.dp).background(Color(0xAAFFFFFF), RoundedCornerShape(3.dp)))
                }
            }
        }
        AnimatedVisibility(
            visible = navShown,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            NavigationBar(containerColor = Color(0xFF1B1B1B)) {
                val items = listOf(
                    Triple("Meter", Icons.Filled.Home, 0), Triple("Scout", Icons.Filled.Place, 1),
                    Triple("PYXIS", Icons.Filled.Settings, 2), Triple("Learn", Icons.Filled.Info, 3),
                )
                items.forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; poke++ },
                        icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
    }
}

fun linkFor(ble: PyxisBle, rest: PyxisRest): CameraLink? = when {
    ble.isConnected -> ble
    rest.isConnected -> rest
    else -> null
}
