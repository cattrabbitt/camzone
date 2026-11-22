package com.cameroncurrin.camzone

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** One analyzed phone-camera frame: scene EV per grid cell, upright. */
/** [exposureEv]: the scene EV the phone rendered as middle gray for this frame (its own ISO, shutter and aperture). */
class ZoneFrame(val width: Int, val height: Int, val ev: FloatArray, val phoneClipped: BooleanArray, val exposureEv: Double = 0.0)

/** A metered point: scene EV (independent of PYXIS settings), and where it was tapped (0..1). */
data class Spot(val ev: Double, val x: Float, val y: Float)

enum class LinkType { NONE, BLUETOOTH, NETWORK }

data class LinkState(
    val type: LinkType = LinkType.NONE,
    val connected: Boolean = false,
    val name: String = "",
    val status: String = "Not connected",
)

/** [shift] moves the green "on target" range, matching the Map, Map Moody and Map LowKey LUTs. */
enum class Look(val label: String, val target: Double, val shift: Double) {
    NORMAL("Normal +1", 1.0, 0.0),
    MOODY("Moody 0", 0.0, -1.0),
    DARK("Dark −½", -0.5, -1.5),
}

object AppState {
    val settings = MutableStateFlow(CamSettings())
    /** True when the settings above came from the camera itself. */
    val settingsFromCamera = MutableStateFlow(false)
    val calibration = MutableStateFlow(0.0)
    val look = MutableStateFlow(Look.NORMAL)
    val widestT = MutableStateFlow(1.8)
    val narrowestT = MutableStateFlow(22.0)
    val lenses = MutableStateFlow(LensKit.defaults)
    /** Which lens is on (-1 = not chosen). */
    val lensIndex = MutableStateFlow(-1)
    val frame = MutableStateFlow<ZoneFrame?>(null)
    val face = MutableStateFlow<Spot?>(null)
    val spotB = MutableStateFlow<Spot?>(null)
    val link = MutableStateFlow(LinkState())
    /** Pending Apply actions and whether they failed, keyed by suggestion id. */
    val applyStatus = MutableStateFlow<Map<String, ApplyStatus>>(emptyMap())
    val phone = MutableStateFlow(PhoneControls())
    val tools = MutableStateFlow(Tools())
    /** Camera feed full screen: menus and strips hidden. */
    val fullFeed = MutableStateFlow(false)
    /** Face target from a matched still; overrides the Look while set. */
    val matchTarget = MutableStateFlow<Double?>(null)
    val kit = MutableStateFlow(Gear.values().toSet())
    val projectType = MutableStateFlow(ProjectType.NARRATIVE)
    val mood = MutableStateFlow(Mood.NATURAL)

    private var prefs: SharedPreferences? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("camzone", Context.MODE_PRIVATE)
        prefs = p
        calibration.value = p.getFloat("cal", 0f).toDouble()
        widestT.value = p.getFloat("widest", 1.8f).toDouble()
        narrowestT.value = p.getFloat("narrowest", 22f).toDouble()
        lenses.value = LensKit.load(p.getString("lenses", null))
        lensIndex.value = p.getInt("lens", -1)
        look.value = Look.values()[p.getInt("look", 0).coerceIn(0, Look.values().size - 1)]
        p.getString("kit", null)?.let { k ->
            kit.value = k.split(',').mapNotNull { n -> Gear.values().firstOrNull { it.name == n } }.toSet()
        }
        projectType.value = ProjectType.values()[p.getInt("ptype", 0).coerceIn(0, ProjectType.values().size - 1)]
        mood.value = Mood.values()[p.getInt("mood", 0).coerceIn(0, Mood.values().size - 1)]
        settings.value = CamSettings(
            iso = p.getInt("iso", 400),
            shutterAngle = p.getFloat("angle", 180f).toDouble(),
            fps = p.getFloat("fps", 24f).toDouble(),
            tStop = p.getFloat("t", 2.8f).toDouble(),
            nd = p.getFloat("nd", 0f).toDouble(),
        )
    }

    fun save() {
        val p = prefs ?: return
        val s = settings.value
        p.edit()
            .putFloat("cal", calibration.value.toFloat())
            .putFloat("widest", widestT.value.toFloat())
            .putFloat("narrowest", narrowestT.value.toFloat())
            .putString("lenses", LensKit.save(lenses.value))
            .putInt("lens", lensIndex.value)
            .putInt("look", look.value.ordinal)
            .putInt("iso", s.iso)
            .putFloat("angle", s.shutterAngle.toFloat())
            .putFloat("fps", s.fps.toFloat())
            .putFloat("t", s.tStop.toFloat())
            .putFloat("nd", s.nd.toFloat())
            .putString("kit", kit.value.joinToString(",") { it.name })
            .putInt("ptype", projectType.value.ordinal)
            .putInt("mood", mood.value.ordinal)
            .apply()
    }

    /** Called by the Bluetooth or network link when the camera reports a value. */
    /** Last messages from the camera, newest first, for checking the connection. */
    val cameraLog = MutableStateFlow<List<String>>(emptyList())
    fun logCamera(line: String) { cameraLog.value = (listOf(line) + cameraLog.value).take(25) }
    /** Set when the camera stops reporting a usable iris (manual lens, lens off). */
    val lensNote = MutableStateFlow<String?>(null)

    fun fromCamera(iso: Int? = null, angle: Double? = null, fps: Double? = null, t: Double? = null) {
        settings.update { s ->
            s.copy(
                iso = iso ?: s.iso,
                shutterAngle = angle ?: s.shutterAngle,
                fps = fps ?: s.fps,
                tStop = t ?: s.tStop,
            )
        }
        settingsFromCamera.value = true
        save()
    }
}

enum class ApplyStatus { SENDING, FAILED }

enum class Wb(val label: String, val mode: Int) {
    AUTO("Auto", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO),
    TUNGSTEN("3200K", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    WARM_FL("3000K fl.", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT),
    FLUORESCENT("4000K", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    DAYLIGHT("5600K", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("6500K", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("7500K", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_SHADE),
}

enum class Guide(val label: String, val ratio: Float) {
    OFF("Off", 0f), R239("2.39", 2.39f), R185("1.85", 1.85f), R169("16:9", 16f / 9f),
}

/** The phone camera's own settings. In Match PYXIS, ISO and shutter follow the PYXIS. */
data class PhoneControls(
    val matchPyxis: Boolean = true,
    val iso: Int = 100,
    val shutterAngle: Double = 180.0,
    val fps: Double = 24.0,
    val wb: Wb = Wb.DAYLIGHT,
    val autoFocus: Boolean = true,
    /** 0 = infinity, 1 = closest focus */
    val focus: Float = 0f,
    val lensId: String? = null,
)

data class Tools(
    val zebra: Boolean = false,
    val zebraAt: Double = Expo.WARN,
    val peaking: Boolean = false,
    val guide: Guide = Guide.OFF,
    val histogram: Boolean = true,
    val grid: Boolean = false,
)
