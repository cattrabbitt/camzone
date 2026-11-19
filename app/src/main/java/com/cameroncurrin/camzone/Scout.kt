package com.cameroncurrin.camzone

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.roundToInt

enum class TagType(val label: String) {
    WINDOW("Window"), PRACTICAL("Practical lamp"), CEILING("Ceiling light"), DOOR("Door / opening"),
    SURFACE("Bright wall / bounce"), OTHER("Other"),
}

data class Orientation(val azimuth: Float, val pitch: Float, val roll: Float)

data class ScoutTag(
    val type: TagType,
    val ev: Double,          // reflected EV100 at the tagged spot (calibrated)
    val kelvin: Int?,        // estimate, from the tag photo
    val photo: String,       // file name inside photos/
    val orientation: Orientation?,
    val distM: Float = 2.5f, // rough distance from the subject spot
)

data class ScoutPhoto(val file: String, val orientation: Orientation?, val timeMs: Long)

/** One location scout: walkthrough photos, tagged light sources and the subject spot. */
class ScoutSession(val name: String, val dir: File) {
    val created: Long = System.currentTimeMillis()
    val photos = mutableListOf<ScoutPhoto>()
    val tags = mutableListOf<ScoutTag>()
    var subjectEv: Double? = null
    var subjectPhoto: String? = null
    var scaleNote: String = ""
    /** Compass bearing from the subject spot toward the camera position. Tags are measured from the subject spot too. */
    var cameraAz: Float? = null

    val photoDir: File get() = File(dir, "photos").apply { mkdirs() }

    fun nextPhotoFile(prefix: String): File {
        val n = photos.size + tags.size + 1
        return File(photoDir, "%s_%04d.jpg".format(prefix, n))
    }

    fun toJson(settings: CamSettings, calibration: Double): JSONObject {
        fun o(or: Orientation?) = or?.let { JSONObject().put("azimuth", it.azimuth).put("pitch", it.pitch).put("roll", it.roll) }
        return JSONObject()
            .put("format", "camzone-scout")
            .put("version", 1)
            .put("name", name)
            .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(created)))
            .put("pyxis", JSONObject()
                .put("iso", settings.iso).put("shutterAngle", settings.shutterAngle)
                .put("fps", settings.fps).put("tStop", settings.tStop).put("ndStops", settings.nd)
                .put("middleGrayEv100", Expo.grayEv(settings)))
            .put("phoneCalibrationStops", calibration)
            .put("scaleNote", scaleNote)
            .put("cameraAzimuthFromSubject", cameraAz ?: JSONObject.NULL)
            .put("subject", if (subjectEv == null) JSONObject.NULL else JSONObject()
                .put("ev100", subjectEv)
                .put("stopsFromPyxisGray", Expo.stopsAt(subjectEv!!, settings))
                .put("photo", subjectPhoto ?: JSONObject.NULL))
            .put("photos", JSONArray().apply {
                photos.forEach { p -> put(JSONObject().put("file", p.file).put("timeMs", p.timeMs).put("orientation", o(p.orientation) ?: JSONObject.NULL)) }
            })
            .put("lightSources", JSONArray().apply {
                tags.forEach { t ->
                    put(JSONObject()
                        .put("type", t.type.name.lowercase())
                        .put("ev100", t.ev)
                        .put("luminanceCdM2", Scout.luminance(t.ev))
                        .put("stopsFromPyxisGray", Expo.stopsAt(t.ev, settings))
                        .put("kelvin", t.kelvin ?: JSONObject.NULL)
                        .put("photo", t.photo)
                        .put("orientation", o(t.orientation) ?: JSONObject.NULL)
                        .put("distanceFromSubjectM", t.distM))
                }
            })
    }

    fun save(settings: CamSettings, calibration: Double) {
        File(dir, "scout.json").writeText(toJson(settings, calibration).toString(2))
    }
}

object Scout {
    val session = MutableStateFlow<ScoutSession?>(null)

    fun start(ctx: Context, name: String): ScoutSession {
        val safe = name.trim().ifEmpty { "Scout" }.replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '_')
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        val dir = File(ctx.getExternalFilesDir("scouts"), "${safe}_$stamp").apply { mkdirs() }
        return ScoutSession(name.trim().ifEmpty { "Scout" }, dir).also { session.value = it }
    }

    /** Reflected-meter luminance in cd/m² from EV100 (meter constant K = 12.5). */
    fun luminance(ev: Double) = 0.125 * 2.0.pow(ev)

    /**
     * Rough color temperature of the center of a photo. The phone's white balance is locked to a
     * preset, so the pixel colors keep the real differences between sources. Treat it as an estimate.
     */
    fun estimateKelvin(file: File, wb: Wb): Int? = try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: throw IllegalStateException()
        val w = bmp.width
        val h = bmp.height
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        for (y in (h * 0.4).toInt() until (h * 0.6).toInt()) for (x in (w * 0.4).toInt() until (w * 0.6).toInt()) {
            val c = bmp.getPixel(x, y)
            r += lin(((c shr 16) and 0xFF) / 255.0); g += lin(((c shr 8) and 0xFF) / 255.0); b += lin((c and 0xFF) / 255.0); n++
        }
        bmp.recycle()
        r /= n; g /= n; b /= n
        // undo the preset's correction: a neutral at the preset's Kelvin was rendered white
        val ref = wbKelvin(wb)
        val (rr, gg, bb) = Triple(r, g, b)
        val cct = mccamy(rr, gg, bb)
        // shift relative to the preset: the camera made `ref` neutral (= 6504K in sRGB terms)
        val mired = 1e6 / cct - 1e6 / 6504.0 + 1e6 / ref
        (1e6 / mired).roundToInt().coerceIn(1500, 12000)
    } catch (e: Exception) { null }

    private fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun mccamy(r: Double, g: Double, b: Double): Double {
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        val s = x + y + z
        if (s <= 0) return 6504.0
        val cx = x / s
        val cy = y / s
        val nn = (cx - 0.3320) / (0.1858 - cy)
        return 449 * nn.pow(3) + 3525 * nn.pow(2) + 6823.3 * nn + 5520.33
    }

    fun wbKelvin(wb: Wb) = when (wb) {
        Wb.TUNGSTEN -> 3200.0; Wb.WARM_FL -> 3000.0; Wb.FLUORESCENT -> 4000.0; Wb.DAYLIGHT -> 5600.0
        Wb.CLOUDY -> 6500.0; Wb.SHADE -> 7500.0; Wb.AUTO -> 5600.0
    }

    /** Zips the scout folder and opens Android's share sheet (Drive, Nearby Share, Files…). */
    fun share(ctx: Context, s: ScoutSession) {
        val zip = File(s.dir.parentFile, s.dir.name + ".zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            s.dir.walkTopDown().filter { it.isFile }.forEach { f ->
                out.putNextEntry(ZipEntry(s.dir.name + "/" + f.relativeTo(s.dir).path))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Scout: ${s.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Send scout to your PC").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // Rosco color-correction gels: mired shift (approximate published values)
    private val gels = listOf(
        "Full CTO" to 159, "½ CTO" to 81, "¼ CTO" to 42, "⅛ CTO" to 20,
        "Full CTB" to -131, "½ CTB" to -68, "¼ CTB" to -30, "⅛ CTB" to -12,
    )

    /** Gel that moves a source from [fromK] closest to [toK]. */
    fun gelFor(fromK: Int, toK: Int): String {
        val need = 1e6 / toK - 1e6 / fromK
        val best = gels.minByOrNull { abs(it.second - need) }!!
        return best.first
    }
}

/** Phone orientation from the rotation-vector sensor, plus how fast it's turning. */
class OrientationTracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val orientation = MutableStateFlow<Orientation?>(null)
    /** Degrees per second */
    val speed = MutableStateFlow(0f)
    private val q = FloatArray(4)
    private var lastQ: FloatArray? = null
    private var lastT = 0L
    var quaternion: FloatArray? = null
        private set

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val m = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(m, e.values)
        val o = FloatArray(3)
        SensorManager.getOrientation(m, o)
        // Heading of the back camera's view (device -Z in world east/north), which stays stable when the phone is held upright.
        val east = -m[2]; val north = -m[5]
        val heading = if (east * east + north * north > 0.04f) Math.toDegrees(kotlin.math.atan2(east, north).toDouble()).toFloat()
            else Math.toDegrees(o[0].toDouble()).toFloat()
        orientation.value = Orientation(heading, Math.toDegrees(o[1].toDouble()).toFloat(), Math.toDegrees(o[2].toDouble()).toFloat())
        SensorManager.getQuaternionFromVector(q, e.values)
        val cur = q.copyOf()
        val prev = lastQ
        if (prev != null && lastT > 0) {
            val dt = (e.timestamp - lastT) / 1e9f
            if (dt > 0) speed.value = speed.value * 0.8f + 0.2f * (angleBetween(prev, cur) / dt)
        }
        lastQ = cur
        lastT = e.timestamp
        quaternion = cur
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    companion object {
        /** Angle in degrees between two orientations. */
        fun angleBetween(a: FloatArray, b: FloatArray): Float {
            val dot = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceAtMost(1f)
            return Math.toDegrees(2 * acos(dot.toDouble())).toFloat()
        }
    }
}
