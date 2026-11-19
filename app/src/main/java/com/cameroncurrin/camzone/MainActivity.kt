package com.cameroncurrin.camzone

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
    Scaffold(
        containerColor = Color(0xFF111111),
        topBar = { UpdateBanner() },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1B1B1B)) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Meter") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Place, null) }, label = { Text("Scout") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("PYXIS") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Info, null) }, label = { Text("Learn") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Color(0xFF111111))) {
            when (tab) {
                0 -> MeterScreen(meter) { linkFor(ble, rest) }
                1 -> ScoutScreen(meter)
                2 -> LinkScreen(ble, rest)
                else -> LearnScreen()
            }
        }
    }
}

fun linkFor(ble: PyxisBle, rest: PyxisRest): CameraLink? = when {
    ble.isConnected -> ble
    rest.isConnected -> rest
    else -> null
}
