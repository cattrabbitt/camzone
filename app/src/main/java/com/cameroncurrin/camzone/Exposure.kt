package com.cameroncurrin.camzone

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The PYXIS settings that decide where middle gray lands. */
data class CamSettings(
    val iso: Int = 400,
    val shutterAngle: Double = 180.0,
    val fps: Double = 24.0,
    val tStop: Double = 2.8,
    /** ND on the lens or matte box, in stops (0.3 = 1 stop). The camera can't report this. */
    val nd: Double = 0.0,
)

enum class Zone(val label: String, val argb: Long) {
    CLIPPED("Clipped", 0xFFEB1F1A),
    NEAR_CLIP("About to clip", 0xFFFF8500),
    BRIGHT("Bright", 0xFFFADB1F),
    GOOD("Good", 0xFF29B83D),
    GRAY("Middle gray", 0xFFB3FF9E),
    DARK("Dark", 0xFF1F61EB),
    CRUSHED("Crushed", 0xFF4D0F66),
}

object Expo {
    /** Starting values from published data for this sensor family. Confirm with the zebra check. */
    /** PYXIS clip point above middle gray. */
    const val PYXIS_CLIP = 6.0
    /** Phone clip point: the phone's picture with its 2.2 curve clips about 2.4 stops over middle gray. */
    const val PHONE_CLIP = 2.4
    /** True when no PYXIS is connected: zones describe the phone's own picture. */
    @Volatile var phoneMode = false
    val CLIP: Double get() = if (phoneMode) PHONE_CLIP else PYXIS_CLIP
    val WARN: Double get() = CLIP - 2.0 / 3.0
    /** Moves the green range for the chosen Look (0, −1 or −1.5), like the mood LUTs. */
    @Volatile var greenShift = 0.0
    /**
     * Rating: the PYXIS is exposed ⅔ stop brighter than true middle gray for clean shadows (matches the LUT v1.4).
     * The gray line, green, blue and purple are measured from this rated gray; clip warnings stay absolute.
     */
    const val RATING = 2.0 / 3.0
    val GOOD_LOW: Double get() = RATING - 1.0 / 3.0 + greenShift
    val GOOD_HIGH: Double get() = RATING + 4.0 / 3.0 + greenShift
    const val CRUSH = RATING - 4.0

    fun log2(x: Double) = ln(x) / ln(2.0)

    /** Exposure value at ISO 100 for an f-number (or T-stop), shutter time in seconds and ISO. */
    fun ev100(n: Double, tSec: Double, iso: Double) = log2(n * n / tSec) - log2(iso / 100.0)

    fun shutterSeconds(angle: Double, fps: Double) = angle / 360.0 / fps

    /** Scene EV that the PYXIS renders as middle gray with these settings. */
    fun grayEv(s: CamSettings) = ev100(s.tStop, shutterSeconds(s.shutterAngle, s.fps), s.iso.toDouble()) + s.nd

    /** Stops from middle gray, in the PYXIS image, for a point measured at scene EV [ev]. */
    fun stopsAt(ev: Double, s: CamSettings) = ev - grayEv(s)

    /** T-stop that puts a point at scene EV [ev] at [target] stops from middle gray. */
    fun tStopFor(ev: Double, target: Double, s: CamSettings): Double {
        val t = shutterSeconds(s.shutterAngle, s.fps)
        return sqrt(t * (s.iso / 100.0) * 2.0.pow(ev - target - s.nd))
    }

    /** Stop difference between two T-stops: positive means [to] lets in less light. */
    fun stopsBetween(from: Double, to: Double) = 2.0 * log2(to / from)

    fun zone(stops: Double): Zone = when {
        stops >= CLIP -> Zone.CLIPPED
        stops >= WARN -> Zone.NEAR_CLIP
        stops >= GOOD_HIGH -> Zone.BRIGHT
        abs(stops - RATING) < 1.0 / 6.0 -> Zone.GRAY
        stops >= GOOD_LOW -> Zone.GOOD
        stops >= CRUSH -> Zone.DARK
        else -> Zone.CRUSHED
    }

    /** Zone color with a subtle step at every whole stop, like the Map LUT. */
    fun shadedColor(stops: Double): Int {
        val z = zone(stops)
        val base = z.argb.toInt()
        if (z == Zone.CLIPPED || z == Zone.NEAR_CLIP || z == Zone.GRAY || z == Zone.CRUSHED) return base
        val frac = stops - floor(stops)
        val k = 0.8 + 0.3 * frac
        val r = (((base shr 16) and 0xFF) * k).coerceAtMost(255.0).toInt()
        val g = (((base shr 8) and 0xFF) * k).coerceAtMost(255.0).toInt()
        val b = ((base and 0xFF) * k).coerceAtMost(255.0).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * The color the CamZone Map LUT paints at [st] stops from middle gray: the same ranges, the dim gray base,
     * the stop-by-stop shading and the darker-the-deeper blue as tools/build_luts.py. Luminance only (the LUT
     * also warns when a single color channel clips, which the phone can't see).
     */
    fun lutColor(st: Double): Int {
        val y = 0.18 * 2.0.pow(st)
        val k = 0.794
        val luma = (y * k / (1 + y * k)).pow(1 / 2.4).coerceIn(0.0, 1.0)
        val frac = st - floor(st)
        val shade = 0.80 + 0.30 * frac
        fun rgb(r: Double, g: Double, b: Double): Int {
            val R = (r.coerceIn(0.0, 1.0) * 255).roundToInt(); val G = (g.coerceIn(0.0, 1.0) * 255).roundToInt(); val B = (b.coerceIn(0.0, 1.0) * 255).roundToInt()
            return (0xFF shl 24) or (R shl 16) or (G shl 8) or B
        }
        fun tex(r: Double, g: Double, b: Double): Int { val m = shade * (0.75 + 0.5 * luma); return rgb(r * m, g * m, b * m) }
        val lo = GOOD_LOW; val hi = GOOD_HIGH
        return when {
            st >= CLIP -> rgb(0.92, 0.12, 0.10)
            st >= WARN -> rgb(1.00, 0.52, 0.00)
            st < CRUSH -> rgb(0.30, 0.06, 0.40)
            abs(st - RATING) < 1.0 / 6.0 -> rgb(0.70, 1.00, 0.62)
            st >= hi -> tex(0.98, 0.86, 0.12)
            st >= lo -> tex(0.16, 0.72, 0.24)
            else -> {
                val bs = ((st - CRUSH) / maxOf(lo - CRUSH, 0.5)).coerceIn(0.0, 1.0)
                val m = (0.45 + 0.55 * bs) * shade
                rgb(0.12 * m, 0.38 * m, 0.92 * m)
            }
        }
    }

    /** "+1⅓", "-⅔", "0" */
    fun formatStops(stops: Double): String {
        val thirds = (stops * 3).roundToInt()
        if (thirds == 0) return "0"
        val sign = if (thirds > 0) "+" else "−"
        val a = abs(thirds)
        val whole = a / 3
        val frac = when (a % 3) { 1 -> "⅓"; 2 -> "⅔"; else -> "" }
        return sign + (if (whole > 0) whole.toString() else "") + frac
    }

    /** Rounds a T-stop to the nearest 1/3 stop. */
    fun roundT(t: Double): Double {
        val av = 2 * log2(t)
        val r = (av * 3).roundToInt() / 3.0
        return 2.0.pow(r / 2)
    }

    fun formatT(t: Double): String = if (t < 10) "T" + "%.1f".format(t) else "T" + t.roundToInt()

    /** Lighting ratio for a stop difference, e.g. 2 stops -> "4:1". */
    fun ratio(stops: Double): String {
        val r = 2.0.pow(abs(stops))
        return if (r < 10) "%.1f:1".format(r).replace(".0:", ":") else "${r.roundToInt()}:1"
    }

    /** ND strength names for whole and partial stops. */
    fun ndName(stops: Double): String {
        val d = (stops * 0.3 * 10).roundToInt() / 10.0
        return "ND %.1f".format(d)
    }
}
