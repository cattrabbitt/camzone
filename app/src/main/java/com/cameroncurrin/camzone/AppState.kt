package com.cameroncurrin.camzone

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** One analyzed phone-camera frame: scene EV per grid cell, upright. */
class ZoneFrame(val width: Int, val height: Int, val ev: FloatArray, val phoneClipped: BooleanArray)

/** A metered point: scene EV (independent of PYXIS settings), and where it was tapped (0..1). */
data class Spot(val ev: Double, val x: Float, val y: Float)

enum class LinkType { NONE, BLUETOOTH, NETWORK }

data class LinkState(
    val type: LinkType = LinkType.NONE,
    val connected: Boolean = false,
    val name: String = "",
    val status: String = "Not connected",
)

enum class Look(val label: String, val target: Double) {
    NORMAL("Normal +1", 1.0),
    MOODY("Moody 0", 0.0),
    DARK("Dark −½", -0.5),
}

object AppState {
    val settings = MutableStateFlow(CamSettings())
    /** True when the settings above came from the camera itself. */
    val settingsFromCamera = MutableStateFlow(false)
    val calibration = MutableStateFlow(0.0)
    val look = MutableStateFlow(Look.NORMAL)
    val widestT = MutableStateFlow(1.8)
    val narrowestT = MutableStateFlow(22.0)
    val frame = MutableStateFlow<ZoneFrame?>(null)
    val face = MutableStateFlow<Spot?>(null)
    val spotB = MutableStateFlow<Spot?>(null)
    val link = MutableStateFlow(LinkState())
    /** Pending Apply actions and whether they failed, keyed by suggestion id. */
    val applyStatus = MutableStateFlow<Map<String, ApplyStatus>>(emptyMap())

    private var prefs: SharedPreferences? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("camzone", Context.MODE_PRIVATE)
        prefs = p
        calibration.value = p.getFloat("cal", 0f).toDouble()
        widestT.value = p.getFloat("widest", 1.8f).toDouble()
        narrowestT.value = p.getFloat("narrowest", 22f).toDouble()
        look.value = Look.values()[p.getInt("look", 0).coerceIn(0, Look.values().size - 1)]
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
            .putInt("look", look.value.ordinal)
            .putInt("iso", s.iso)
            .putFloat("angle", s.shutterAngle.toFloat())
            .putFloat("fps", s.fps.toFloat())
            .putFloat("t", s.tStop.toFloat())
            .putFloat("nd", s.nd.toFloat())
            .apply()
    }

    /** Called by the Bluetooth or network link when the camera reports a value. */
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
