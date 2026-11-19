package com.cameroncurrin.camzone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val steps = listOf(
    "Connect with Bluetooth" to listOf(
        "On the PYXIS, press Menu to open the dashboard, go to the Setup tab and tap the Bluetooth switch to turn it on.",
        "On the Pixel, make sure Bluetooth is on. You don't pair from Android's Bluetooth settings; pair from this app.",
        "In Cam Zone, open the PYXIS tab and tap Find PYXIS. Allow the Nearby devices permission if asked.",
        "Tap your camera in the list. Blackmagic cameras show as a camera letter followed by a hardware ID.",
        "The first time, the PYXIS screen shows a 6-digit code and Android asks for it. Type it in and tap Pair.",
        "The status turns green and the settings at the top of the Meter tab now come from the camera.",
        "Stay within about 30 feet. If pairing fails: turn Bluetooth off and on on the PYXIS, open Android Settings > Connected devices, forget the camera, and pair again from the app.",
    ),
    "Connect with a USB-C cable" to listOf(
        "Plug a USB-C data cable from the Pixel into the PYXIS rear USB-C port. (If your PYXIS Monitor uses that port, use Bluetooth instead.)",
        "On the PYXIS, open the Setup tab, set a remote password if asked, and turn Remote control on with Control and monitor selected.",
        "On the Pixel, open Settings > Network & internet > Hotspot & tethering and turn on USB tethering.",
        "In Cam Zone, open the PYXIS tab and tap Find camera. Tap the address it finds. Or type the IP address shown in the PYXIS network settings (Setup > network settings).",
        "This route uses Blackmagic's network control, which the camera also offers over Ethernet. If it won't connect, use Bluetooth: it's the tested route.",
    ),
    "Load the LUTs on the PYXIS" to listOf(
        "Copy CamZone_Map_Gen5.cube and CamZone_Guard_Gen5.cube to the root of a CFexpress card or USB-C drive, or into a folder named 3DLUTs.",
        "On the PYXIS, press Menu, open the LUTs tab and tap the manage icon at the bottom.",
        "Tap Import LUT, then import. Pick each Cam Zone file and tap import.",
        "Select a LUT and tap load. In the monitor settings you can choose which outputs show it (LCD, SDI or USB-C).",
        "Set Dynamic range to Film (Blackmagic Film Gen 5). The LUTs expect the log image.",
        "Map: for lighting and the final check before a take. Guard: your normal picture with warnings only, for rolling.",
    ),
    "Calibrate the phone (once per lens setup)" to listOf(
        "Light the gray card the way you'll light the scene.",
        "With the Map LUT on the PYXIS, set the iris so the card shows light green: middle gray.",
        "Check the settings strip in the Meter tab matches the camera (connect, or tap Edit).",
        "Meter tab > Calibrate with gray card. Fill the center of the phone's view with the card and tap Calibrate.",
    ),
    "Set exposure on a shot" to listOf(
        "Lock ISO (400, or 3200 when it's truly dark), shutter 180° and frame rate.",
        "Pick the look: Normal +1, Moody 0 or Dark −½.",
        "Spot mode: tap the face. The advice cards tell you what to change.",
        "Tap Apply to send the change to the PYXIS. If it can't, the card shows the manual step; tap I did it when it's done. The card disappears once you're on target.",
        "Contrast mode: tap the key side, then the fill side, to read the ratio.",
        "Check the PYXIS Map LUT: face green, nothing you care about in orange or red. Switch to Guard and roll.",
    ),
    "Scout a location before you light (about 3 minutes)" to listOf(
        "Scout tab > type the location > Full scout. Use Quick read if there's no time to walk.",
        "Lay a tape measure open to 1 meter on the floor so the PC scan knows the real size.",
        "Tap Start walking. Walk slowly along the walls, phone at chest height pointing across the room, then a second lap at eye level. Photos take themselves; slow down if it says so. Aim for 120 or more.",
        "Tag the light sources: point the circle at each window, lamp, ceiling light or bright wall and tap its name. Each tag records brightness and an estimated color temperature.",
        "Mark the subject: stand where the actor will be, aim at a gray card at face height, tap Mark subject.",
        "Read the room: every source against the subject, plus what to do (where to put the actor, ND on windows, gels for mixed color).",
        "Send to PC zips everything for the Lighting Planner. Color temperature is an estimate from the phone; confirm on the PYXIS.",
    ),
    "Phone camera and monitoring tools" to listOf(
        "The phone camera always runs in manual: no auto exposure, so the colors only change when the light or your PYXIS settings change.",
        "Match PYXIS (default): the phone's ISO and shutter follow the PYXIS, a few stops darker so its smaller sensor keeps the highlights. Manual: set ISO, shutter angle and frame rate yourself.",
        "White balance, focus (auto or manual) and lens choice are under Phone camera on the Meter tab.",
        "Zebras: stripes over anything above the level you pick (clip, +5⅓, +3, or +1⅓ for hot skin). Peaking: cyan edges where focus is sharp.",
        "Guides shade the frame to 2.39, 1.85 or 16:9. Grid shows thirds. The histogram is in stops, colored like the LUT.",
        "In Spot and Contrast modes the picture turns gray and shows brightness the way the PYXIS would record it.",
    ),
    "Check the clip warning (2 minutes)" to listOf(
        "Turn on 100% zebras on the PYXIS with no LUT and point it at a bulb.",
        "Turn the zebras off and load the Map LUT. Red should sit where the zebras were, with orange just before it.",
        "If they don't line up, note roughly how far off it is so the LUTs can be adjusted.",
    ),
)

@Composable
fun GuideScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Section("App updates") {
            UpdateCheckRow()
            Text("The app checks GitHub each time it opens. When a new build is there, a green bar shows at the top: tap Update, then Install. Your settings stay.",
                color = Color(0xFFAAAAAA), fontSize = 13.sp)
        }
        Section("Color code") {
            Zone.values().forEach { z ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.size(20.dp).background(Color(z.argb), RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(legend(z), color = Color.White, fontSize = 14.sp)
                }
            }
            Text("Fix order: ND first, then light, then iris. ISO 3200 only when the scene is truly dark.",
                color = Color(0xFFCCCCCC), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        steps.forEach { (title, list) ->
            Section(title) {
                list.forEachIndexed { i, s ->
                    Text("${i + 1}. $s", color = Color(0xFFDDDDDD), fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun legend(z: Zone) = when (z) {
    Zone.CLIPPED -> "Red: clipped (+6 and up)"
    Zone.NEAR_CLIP -> "Orange: about to clip (+5⅓ to +6)"
    Zone.BRIGHT -> "Yellow: bright (+1⅓ to +5⅓)"
    Zone.GOOD -> "Green: good, faces belong here (−½ to +1⅓)"
    Zone.GRAY -> "Light green: middle gray (0)"
    Zone.DARK -> "Blue: dark (−5 to −½)"
    Zone.CRUSHED -> "Dark purple: crushed (below −5)"
}
