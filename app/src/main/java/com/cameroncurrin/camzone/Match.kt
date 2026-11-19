package com.cameroncurrin.camzone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** What we read from a reference still, in stops around middle gray (graded image, Rec.709-ish display). */
data class StillRead(
    val foundFace: Boolean,
    val faceStops: Double,
    val ratioStops: Double,          // bright side minus dark side of the face
    val keyCameraLeft: Boolean,      // brighter half is on the image's left = camera-left
    val keyHeight: String,           // "high", "eye level", "low"
    val hard: Boolean,
    val rim: Boolean,
    val bgStops: Double,             // background median
    val p5: Double, val p50: Double, val p95: Double,
    val clippedPct: Double,
    val highWarm: Double,            // R−B in highlights (+ warm, − cool)
    val shadowWarm: Double,
    val saturation: Double,          // 0..1 midtone saturation
    val aspect: Double,              // picture area, letterbox removed
    val faceHeightFrac: Double,      // face height / picture height
    val faceX: Double, val faceY: Double,
    val depth: Double,               // background sharpness vs face (low = shallow focus)
)

data class Recipe(
    val title: String,
    val summary: List<String>,
    val pyxis: List<String>,
    val targets: List<String>,
    val lights: List<PlannedLight>,
    val tips: List<String>,
    val faceTarget: Double,
    val ratioTarget: Double,
    val bgTarget: Double,
)

object Match {
    val refFile = MutableStateFlow<File?>(null)
    val image = MutableStateFlow<Bitmap?>(null)
    val ghost = MutableStateFlow(false)
    val read = MutableStateFlow<StillRead?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private fun file(ctx: Context) = File(ctx.filesDir, "match/ref.jpg")

    /** Loads the last reference still, if there is one. */
    suspend fun restore(ctx: Context) {
        val f = file(ctx)
        if (f.exists() && read.value == null) analyzeFile(ctx, f)
    }

    suspend fun pick(ctx: Context, uri: Uri) {
        busy.value = true
        error.value = null
        try {
            val f = file(ctx)
            withContext(Dispatchers.IO) {
                f.parentFile?.mkdirs()
                val bmp = decode(ctx, ImageDecoder.createSource(ctx.contentResolver, uri), 1280)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }
            analyzeFile(ctx, f)
        } catch (e: Exception) {
            error.value = "Couldn't open that image."
        } finally {
            busy.value = false
        }
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        refFile.value = null
        read.value = null
        image.value = null
        ghost.value = false
        AppState.matchTarget.value = null
    }

    private fun decode(ctx: Context, src: ImageDecoder.Source, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val sc = min(1.0, maxSide.toDouble() / max(w, h))
            d.setTargetSize((w * sc).roundToInt().coerceAtLeast(1), (h * sc).roundToInt().coerceAtLeast(1))
        }

    private suspend fun analyzeFile(ctx: Context, f: File) {
        busy.value = true
        try {
            val big = withContext(Dispatchers.IO) { decode(ctx, ImageDecoder.createSource(f), 1280) }
            val face = try {
                val det = FaceDetection.getClient(FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(0.05f).build())
                det.process(InputImage.fromBitmap(big, 0)).await().maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox
            } catch (e: Exception) { null }
            read.value = withContext(Dispatchers.Default) { analyze(big, face) }
            refFile.value = f
            image.value = big
        } catch (e: Exception) {
            error.value = "Couldn't read that image."
        } finally {
            busy.value = false
        }
    }

    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    private fun stops(l: Double) = Expo.log2(max(l, 1e-5) / 0.18)

    fun analyze(bmp: Bitmap, faceBox: Rect?): StillRead {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val L = DoubleArray(w * h)
        val code = DoubleArray(w * h)
        val lut = DoubleArray(256) { lin(it) }
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            L[i] = 0.2126 * lut[r] + 0.7152 * lut[g] + 0.0722 * lut[b]
            code[i] = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        }
        // Letterbox: dark, flat rows at the top and bottom
        fun rowDark(y: Int): Boolean { var s = 0.0; for (x in 0 until w) s += code[y * w + x]; return s / w < 0.035 }
        var top = 0; while (top < h / 3 && rowDark(top)) top++
        var bot = h - 1; while (bot > h * 2 / 3 && rowDark(bot)) bot--
        val ph = (bot - top + 1).coerceAtLeast(1)
        val aspect = w.toDouble() / ph

        val box = faceBox?.let { Rect(it.left.coerceIn(0, w - 1), it.top.coerceIn(0, h - 1), it.right.coerceIn(1, w), it.bottom.coerceIn(1, h)) }
            ?: Rect((w * 0.35).toInt(), top + (ph * 0.2).toInt(), (w * 0.65).toInt(), top + (ph * 0.6).toInt())
        // Inner face: skip hair and edges
        val iw = box.width(); val ih = box.height()
        val inner = Rect(box.left + iw / 6, box.top + ih / 5, box.right - iw / 6, box.bottom - ih / 10)

        fun meanLin(r: Rect): Double {
            var s = 0.0; var n = 0
            for (y in r.top until r.bottom) for (x in r.left until r.right) { s += L[y * w + x]; n++ }
            return if (n == 0) 0.18 else s / n
        }
        val midX = inner.centerX()
        val left = meanLin(Rect(inner.left, inner.top, midX, inner.bottom))
        val right = meanLin(Rect(midX, inner.top, inner.right, inner.bottom))
        val faceStops = stops(meanLin(inner))
        val ratio = abs(stops(left) - stops(right))
        val third = inner.height() / 3
        val upper = stops(meanLin(Rect(inner.left, inner.top, inner.right, inner.top + third)))
        val lower = stops(meanLin(Rect(inner.left, inner.bottom - third, inner.right, inner.bottom)))
        val keyHeight = when { upper - lower > 0.5 -> "high"; lower - upper > 0.4 -> "low"; else -> "eye level" }

        // Hardness: how quickly the face goes from bright side to dark side
        val cols = 12
        val prof = DoubleArray(cols) { i ->
            val x0 = inner.left + inner.width() * i / cols
            val x1 = (inner.left + inner.width() * (i + 1) / cols).coerceAtLeast(x0 + 1)
            stops(meanLin(Rect(x0, inner.top, x1, inner.bottom)))
        }
        val lo = prof.min(); val hi = prof.max()
        val span = hi - lo
        val transition = if (span < 0.3) cols else prof.count { it > lo + span * 0.2 && it < lo + span * 0.8 }
        val hard = ratio > 0.8 && transition <= 2

        // Rim: bright pixels in a band just outside the face
        val band = max(2, iw / 12)
        var rimHits = 0; var rimN = 0
        val faceLin = meanLin(inner)
        for (y in max(top, box.top - band) until min(bot, box.bottom)) for (x in max(0, box.left - band) until min(w, box.right + band)) {
            val inside = x >= box.left && x < box.right && y >= box.top
            if (inside) continue
            rimN++
            if (L[y * w + x] > faceLin * 2.5) rimHits++
        }
        val rim = rimN > 0 && rimHits.toDouble() / rimN > 0.06

        // Background: outside an enlarged face box, inside the picture area
        val ex = Rect(box.left - iw, box.top - ih / 2, box.right + iw, bot)
        val bg = ArrayList<Double>()
        val all = ArrayList<Double>(w * ph / 4)
        var clipped = 0
        for (y in top..bot step 2) for (x in 0 until w step 2) {
            val i = y * w + x
            val s = stops(L[i])
            all += s
            if (code[i] > 0.97) clipped++
            if (!(x in ex.left until ex.right && y in ex.top until ex.bottom)) bg += s
        }
        all.sort(); bg.sort()
        fun pct(a: List<Double>, q: Double) = if (a.isEmpty()) 0.0 else a[(a.size * q).toInt().coerceAtMost(a.size - 1)]

        // Color: warmth of highlights and shadows, midtone saturation
        val lumSorted = (top..bot step 3).flatMap { y -> (0 until w step 3).map { x -> y * w + x } }.sortedBy { L[it] }
        fun warm(idx: List<Int>): Double = if (idx.isEmpty()) 0.0 else idx.sumOf { val c = px[it]; (((c shr 16) and 255) - (c and 255)) / 255.0 } / idx.size
        val n = lumSorted.size
        val highWarm = warm(lumSorted.subList((n * 0.8).toInt(), n))
        val shadowWarm = warm(lumSorted.subList((n * 0.05).toInt(), (n * 0.3).toInt()))
        val mids = lumSorted.subList((n * 0.3).toInt(), (n * 0.8).toInt())
        val sat = if (mids.isEmpty()) 0.0 else mids.sumOf {
            val c = px[it]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
        } / mids.size

        // Depth of field: edge energy in the background vs the face
        fun sharp(r: Rect): Double {
            var s = 0.0; var c = 0
            for (y in max(r.top, 1) until min(r.bottom, h - 1) step 2) for (x in max(r.left, 1) until min(r.right, w - 1) step 2) {
                val i = y * w + x
                val lap = 4 * code[i] - code[i - 1] - code[i + 1] - code[i - w] - code[i + w]
                s += lap * lap; c++
            }
            return if (c == 0) 0.0 else s / c
        }
        val faceSharp = sharp(box)
        val bgSharp = listOf(Rect(0, top, w / 5, bot), Rect(w * 4 / 5, top, w, bot), Rect(0, top, w, top + ph / 6)).map { sharp(it) }.maxOrNull() ?: 0.0
        val depth = if (faceSharp <= 1e-9) 1.0 else (bgSharp / faceSharp).coerceIn(0.0, 3.0)

        return StillRead(
            foundFace = faceBox != null,
            faceStops = faceStops, ratioStops = ratio, keyCameraLeft = left > right, keyHeight = keyHeight, hard = hard, rim = rim,
            bgStops = pct(bg, 0.5), p5 = pct(all, 0.05), p50 = pct(all, 0.5), p95 = pct(all, 0.95),
            clippedPct = clipped * 100.0 / max(1, all.size), highWarm = highWarm, shadowWarm = shadowWarm, saturation = sat,
            aspect = aspect, faceHeightFrac = ih.toDouble() / ph, faceX = box.exactCenterX().toDouble() / w,
            faceY = (box.exactCenterY() - top).toDouble() / ph, depth = depth,
        )
    }

    private fun aspectName(a: Double) = when {
        a > 2.2 -> "2.39:1 (anamorphic widescreen)"
        a > 1.95 -> "2:1"
        a > 1.82 -> "1.85:1"
        a > 1.6 -> "16:9"
        a > 1.2 -> "4:3"
        a > 0.9 -> "square"
        else -> "vertical"
    }

    private fun shotName(f: Double) = when {
        f > 0.55 -> "extreme close-up"
        f > 0.33 -> "close-up"
        f > 0.18 -> "medium close-up"
        f > 0.09 -> "medium shot"
        f > 0.045 -> "medium wide"
        else -> "wide shot"
    }

    /** Turns the read into settings, targets, light placement and tips for Cam's kit. */
    fun recipe(r: StillRead, kit: Set<Gear>, s: CamSettings): Recipe {
        val lowKey = r.p50 < -1.5
        val night = lowKey && r.shadowWarm < -0.03
        val highKey = r.p50 > -0.2 && r.ratioStops < 1.0
        val faceT = r.faceStops.coerceIn(-2.0, 2.0)
        val ratioT = r.ratioStops.coerceIn(0.0, 4.0)
        val side = if (r.keyCameraLeft) 1f else -1f
        val keyAngle = side * when { ratioT > 2.5 -> 80f; ratioT > 1.2 -> 50f; else -> 25f }
        val mood = when { night -> "night"; lowKey -> "low-key"; highKey -> "high-key"; ratioT > 1.5 -> "moody"; else -> "natural" }
        val title = "${mood.replaceFirstChar { it.uppercase() }} ${shotName(r.faceHeightFrac)}" + if (!r.foundFace) " (no face found: read from the center)" else ""

        val warmKey = r.highWarm > 0.08
        val coolKey = r.highWarm < -0.03
        val tealOrange = r.highWarm > 0.05 && r.shadowWarm < -0.01

        // Settings
        val wantT = when { r.depth < 0.15 -> 1.8; r.depth < 0.35 -> 2.8; r.depth < 0.7 -> 4.0; else -> 5.6 }
        val t = Expo.roundT(max(wantT, AppState.widestT.value))
        val iso = if (lowKey || night) 3200 else 400
        val pyxis = listOf(
            "ISO $iso" + if (iso == 3200) " (second native ISO; cleaner shadows for a dark look)" else " (native ISO, most room above middle gray)",
            "Shutter 180°, 24 fps",
            "Iris ${Expo.formatT(t)}" + when { r.depth < 0.35 -> " for a soft background like the still"; r.depth < 0.7 -> " for some background detail"; else -> " or deeper: the background in the still is sharp" },
            "ND: add whatever holds ${Expo.formatT(t)} on location. Cam Zone's suggestions will say how much.",
            "White balance: " + when {
                night -> "3200K, with daylight sources reading blue as moonlight"
                tealOrange -> "4300 to 5600K, warm (½ CTO or 3200K) key on the face, cooler ambient"
                warmKey -> "5600K with a 3200 to 4000K key, or set the camera cooler (4300K) for an overall warm image"
                coolKey -> "3200 to 4300K with daylight sources, for a cooler image"
                else -> "match your key (5600K daylight or the bi-color setting you use)"
            },
            "Aspect: frame for ${aspectName(r.aspect)}" + if (r.aspect > 2.2) ": turn on the 2.39 guide in Tools" else "",
        )

        val targets = listOf(
            "Face at ${Expo.formatStops(faceT)} (Cam Zone face reading)",
            "Contrast ${Expo.ratio(ratioT)} (${"%.1f".format(ratioT)} stops), key from camera-${if (r.keyCameraLeft) "left" else "right"}",
            "Background at ${Expo.formatStops(r.bgStops)} (${Expo.formatStops(r.bgStops - r.faceStops)} vs the face)",
            "Brightest areas at ${Expo.formatStops(r.p95)}" + if (r.clippedPct > 2) ", with some clipping (${"%.0f".format(r.clippedPct)}%) like the still" else ", kept out of red",
        )

        val lights = mutableListOf<PlannedLight>()
        val keyGear = (if (r.hard) Planner.hardKey(kit) else Planner.softKey(kit)) ?: Planner.softKey(kit) ?: Planner.hardKey(kit) ?: "your brightest light"
        val keyColor = when { night && !warmKey -> "5600K (moonlight)"; warmKey || tealOrange -> "3200K or ½ CTO"; coolKey -> "5600K+"; else -> "match the room" }
        lights += PlannedLight("Key", keyGear, keyAngle, if (r.hard) 2.5f else 1.4f,
            when (r.keyHeight) { "high" -> "high, angled down about 30 to 45°"; "low" -> "low, below eye level (only if the scene motivates it)"; else -> "near eye level" },
            "${if (r.hard) "hard, bare or with the Fresnel" else "soft, as big and close as you can"}; $keyColor")
        when {
            ratioT > 1.8 -> if (Gear.REFLECTOR in kit) lights += PlannedLight("Negative fill", "5-in-1, black side", -keyAngle.coerceIn(-90f, 90f), 0.8f, "face height, just out of frame", "keeps the shadow side dark", MapItem.Shape.NEG)
            ratioT < 0.9 -> if (Gear.REFLECTOR in kit) lights += PlannedLight("Fill", "5-in-1, white side", -keyAngle.coerceIn(-60f, 60f), 1.0f, "face height or a little low", "close to lift the shadows", MapItem.Shape.BOUNCE)
            else -> if (Gear.REFLECTOR in kit) lights += PlannedLight("Fill", "5-in-1, white side, farther away", -keyAngle.coerceIn(-70f, 70f), 1.8f, "face height", "back it off until the contrast reads ${Expo.ratio(ratioT)}", MapItem.Shape.BOUNCE)
        }
        if (r.rim) {
            val rimGear = Planner.rim(kit, keyGear) ?: "a second light"
            lights += PlannedLight("Rim", rimGear, if (r.keyCameraLeft) -150f else 150f, 1.3f, "high, behind, aimed at hair and shoulder", if (tealOrange) "cool, 5600K+" else "same color as the key")
        }
        if (r.bgStops - r.faceStops > 0.7 && Gear.PAVOTUBE in kit) {
            lights += PlannedLight("Background", "PavoTube II 30C", 180f, 2.8f, "aimed at the wall or set behind", if (tealOrange) "cool teal-blue, low" else "lift the background to match")
        }

        val tips = mutableListOf<String>()
        tips += "Set Look to Match: Cam Zone's suggestions will aim the face at ${Expo.formatStops(faceT)} instead of the normal +1."
        if (r.hard) tips += "Hard light: crisp shadow edges. Use the 600d bare or with the Fresnel lens, far back. Don't diffuse it."
        else tips += "Soft light: wide, gradual shadow edge. Light Dome III close to the face; the closer it is, the softer."
        if (ratioT > 2) tips += "Most of the look is in the shadow side. Kill the overheads, close blinds on that side, and use the black side of the 5-in-1."
        if (r.bgStops - r.faceStops < -2.5) tips += "The background falls off a lot. Keep spill off the walls: flag the key, move the subject away from the background."
        if (r.bgStops - r.faceStops > 0.7) tips += "The background is brighter than the face: use a window or practicals behind, and expose for the face."
        if (tealOrange) tips += "Teal-orange: warm the face (½ CTO or 3200K), keep the ambient and backlight cooler. Finish the split in the grade."
        if (r.saturation < 0.18) tips += "The still is fairly desaturated. Keep wardrobe and set colors muted; pull saturation in the grade."
        else if (r.saturation > 0.45) tips += "Strong color: saturated wardrobe, practicals or RGB on the PavoTube carry this look."
        if (r.keyHeight == "low") tips += "Light from below reads as unnatural or scary. Motivate it with something in frame."
        if (r.faceX < 0.42 || r.faceX > 0.58) tips += "Subject sits off center (${if (r.faceX < 0.5) "left" else "right"}): give looking room on the other side."
        else tips += "Subject is near center: a deliberate, symmetric frame."
        tips += "Stills are graded, so treat the stops as a starting point. Match the contrast and face placement first, then fine-tune by eye with the Ghost overlay."

        val summary = listOf(
            "Shot: ${shotName(r.faceHeightFrac)}, ${aspectName(r.aspect)}",
            "Light: ${if (r.hard) "hard" else "soft"} key from camera-${if (r.keyCameraLeft) "left" else "right"}, ${r.keyHeight}" + if (r.rim) ", with a rim" else "",
            "Contrast: ${Expo.ratio(ratioT)}, overall ${mood}",
            "Color: " + when { tealOrange -> "warm highlights, cool shadows (teal-orange)"; warmKey -> "warm"; coolKey -> "cool"; else -> "neutral" } +
                ", " + when { r.saturation < 0.18 -> "muted"; r.saturation > 0.45 -> "saturated"; else -> "natural saturation" },
        )
        return Recipe(title, summary, pyxis, targets, lights, tips, faceT, ratioT, r.bgStops - r.faceStops)
    }
}
