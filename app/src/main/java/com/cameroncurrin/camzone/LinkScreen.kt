package com.cameroncurrin.camzone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LinkScreen(ble: PyxisBle, rest: PyxisRest) {
    val link by AppState.link.collectAsState()
    val found = remember { mutableStateListOf<PyxisBle.Found>() }
    val ips = remember { mutableStateListOf<String>() }
    var address by remember { mutableStateOf("") }
    var searchMsg by remember { mutableStateOf("") }

    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            found.clear()
            ble.scan { f -> found.add(f) }
        } else {
            AppState.link.value = AppState.link.value.copy(status = "Bluetooth permission is needed to find the PYXIS")
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Section("Status") {
            Text(link.status, color = if (link.connected) Color(0xFF7BE38C) else Color.White, fontSize = 16.sp)
            if (link.type != LinkType.NONE) {
                OutlinedButton(onClick = { ble.disconnect(); rest.disconnect() }, modifier = Modifier.padding(top = 8.dp)) { Text("Disconnect") }
            }
        }

        Section("What the camera is sending") {
            val log by AppState.cameraLog.collectAsState()
            Text("Change a setting on the PYXIS: a new line should appear here within a second. If nothing appears, tap Refresh.",
                color = Color(0xFF9A9A9A), fontSize = 12.sp)
            OutlinedButton(onClick = { ble.refresh() }, modifier = Modifier.padding(vertical = 6.dp)) { Text("Refresh from camera") }
            if (log.isEmpty()) Text("Nothing yet.", color = Color(0xFFCCCCCC), fontSize = 13.sp)
            log.forEach { Text(it, color = Color(0xFFCCCCCC), fontSize = 13.sp) }
        }
        Section("Bluetooth (easiest)") {
            Text(
                "On the PYXIS: Menu → Setup → turn Bluetooth on. Then tap Find PYXIS. The first time, " +
                    "the PYXIS shows a 6-digit code: type it into the box Android shows. Full steps are in the Guide tab.",
                color = Color(0xFFCCCCCC), fontSize = 14.sp,
            )
            Button(onClick = { askBt.launch(bluetoothPermissions()) }, modifier = Modifier.padding(top = 8.dp)) { Text("Find PYXIS") }
            found.forEach { f ->
                Text(
                    "Connect to ${f.name}",
                    color = Color(0xFF7BE38C), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { ble.connect(f) }.padding(vertical = 10.dp),
                )
            }
            if (ble.isConnected) {
                Text("Display LUT on the PYXIS (whichever LUT is loaded):", color = Color(0xFFCCCCCC), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ble.setCustomLut(true) }) { Text("LUT on") }
                    OutlinedButton(onClick = { ble.setCustomLut(false) }) { Text("LUT off") }
                }
            }
        }

        Section("USB-C cable") {
            Text(
                "Cable from the Pixel to the PYXIS rear USB-C port. On the Pixel: Settings → Network & internet → " +
                    "Hotspot & tethering → USB tethering on. On the PYXIS: Setup → Remote control on. Then tap Find camera, " +
                    "or type the IP address shown in the PYXIS network settings. The same works over Ethernet through a router.",
                color = Color(0xFFCCCCCC), fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    ips.clear(); searchMsg = "Searching…"
                    rest.find({ ip -> ips.add(ip) }) { n -> searchMsg = if (n == 0) "No camera found. Check the cable, USB tethering and Remote control." else "" }
                }) { Text("Find camera") }
            }
            if (searchMsg.isNotEmpty()) Text(searchMsg, color = Color(0xFFFFB46B), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            ips.forEach { ip ->
                Text("Connect to $ip", color = Color(0xFF7BE38C), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { rest.connect(ip) }.padding(vertical = 10.dp))
            }
            OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                label = { Text("Or type the camera's IP address") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedButton(onClick = { if (address.isNotBlank()) rest.connect(address) }, modifier = Modifier.padding(top = 6.dp)) { Text("Connect") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
