package com.cameroncurrin.camzone

import kotlin.math.abs
import kotlin.math.roundToInt

/** Gear Cam can bring to set. */
enum class Gear(val label: String, val note: String) {
    LS600D("Aputure LS 600d", "5600K, the big punch, roughly HMI-class output"),
    AMARAN200XS("amaran 200x S", "bi-color 2700–6500K, mid power"),
    PAVOTUBE("Nanlite PavoTube II 30C", "RGB tube, 2700–7500K, battery, about 60 fc at 1 m"),
    LIGHTDOME("Aputure Light Dome III", "big soft key modifier"),
    FRESNEL("Nanlite FL-20G Fresnel lens", "hard, focusable beam: sun, moon, hard horror light"),
    SPOTLIGHT("Spotlight attachment", "shaped beam, patterns"),
    REFLECTOR("5-in-1 reflector", "white/silver/gold bounce, black for negative fill, diffusion"),
    GELS("Rosco color-correction gel kit", "CTO/CTB/plus- and minus-green"),
    CSTANDS("C-stands and boom arms", "4 C-stands, 3 boom arms"),
    ND("ND filters for the lens", "to hold your stop in bright rooms"),
}

enum class ProjectType(val label: String) {
    NARRATIVE("Narrative / drama"), HORROR("Horror / thriller"), COMMERCIAL("Commercial / brand"),
    INTERVIEW("Interview / doc"), MUSIC("Music video"), WEDDING("Wedding / event"),
}

enum class Mood(val label: String, val face: Double, val ratioStops: Double) {
    NATURAL("Natural", 1.0, 1.5),
    BRIGHT("Soft & bright", 1.0, 1.0),
    MOODY("Moody", 0.5, 2.0),
    DARK("Dark / low-key", 0.0, 3.0),
    WARM("Warm & romantic", 1.0, 1.5),
    NIGHT("Night", -0.5, 2.5),
}

/** Something to draw on the top-down map. angle: 0 = beside camera, + = camera-left, ±180 = behind subject. */
data class MapItem(
    val label: String,
    val angle: Float,
    val distM: Float,
    val color: Long,
    val shape: Shape = Shape.LIGHT,
    val sub: String? = null,
) { enum class Shape { LIGHT, SOURCE, NEG, BOUNCE } }

data class PlannedLight(
    val role: String,
    val gear: String,
    val angle: Float,
    val distM: Float,
    val height: String,
    val setting: String,
    val shape: MapItem.Shape = MapItem.Shape.LIGHT,
)

data class Source(val title: String, val url: String)

data class PlannedSetup(
    val name: String,
    val why: String,
    val lights: List<PlannedLight>,
    val targets: String,
    val steps: List<String>,
    val sources: List<Source>,
    val missing: List<String>,
    val score: Int,
)

/** What the scout found, reduced to what the planner needs. */
data class RoomFacts(
    val windowAngle: Float?,     // camera-left positive, null if no window tagged
    val windowStops: Double?,    // window vs subject, stops
    val windowK: Int?,
    val practicalAngle: Float?,
    val practicalK: Int?,
    val subjectStops: Double?,   // room light at the subject vs PYXIS middle gray
) {
    companion object {
        val NONE = RoomFacts(null, null, null, null, null, null)

        fun from(s: ScoutSession?, settings: CamSettings): RoomFacts {
            if (s == null) return NONE
            val cam = s.cameraAz
            fun rel(t: ScoutTag): Float? {
                val az = t.orientation?.azimuth ?: return null
                if (cam == null) return null
                var a = az - cam
                while (a > 180) a -= 360
                while (a < -180) a += 360
                return a
            }
            val subj = s.subjectEv
            val win = s.tags.filter { it.type == TagType.WINDOW || it.type == TagType.DOOR }.maxByOrNull { it.ev }
            val prac = s.tags.firstOrNull { it.type == TagType.PRACTICAL }
            return RoomFacts(
                windowAngle = win?.let { rel(it) },
                windowStops = win?.let { it.ev - (subj ?: Expo.grayEv(settings)) },
                windowK = win?.kelvin,
                practicalAngle = prac?.let { rel(it) },
                practicalK = prac?.kelvin,
                subjectStops = subj?.let { Expo.stopsAt(it, settings) },
            )
        }
    }
}

object Planner {

    private fun has(kit: Set<Gear>, g: Gear) = g in kit

    internal fun softKey(kit: Set<Gear>): String? = when {
        has(kit, Gear.LS600D) && has(kit, Gear.LIGHTDOME) -> "600d + Light Dome III"
        has(kit, Gear.AMARAN200XS) && has(kit, Gear.LIGHTDOME) -> "amaran 200x S + Light Dome III"
        has(kit, Gear.LS600D) && has(kit, Gear.REFLECTOR) -> "600d bounced into the 5-in-1 white"
        has(kit, Gear.AMARAN200XS) -> "amaran 200x S"
        has(kit, Gear.PAVOTUBE) -> "PavoTube II 30C"
        else -> null
    }

    internal fun hardKey(kit: Set<Gear>): String? = when {
        has(kit, Gear.LS600D) && has(kit, Gear.FRESNEL) -> "600d + Fresnel lens"
        has(kit, Gear.LS600D) && has(kit, Gear.SPOTLIGHT) -> "600d + spotlight"
        has(kit, Gear.LS600D) -> "600d bare"
        has(kit, Gear.AMARAN200XS) -> "amaran 200x S bare"
        else -> null
    }

    internal fun rim(kit: Set<Gear>, notThis: String?): String? = when {
        has(kit, Gear.PAVOTUBE) -> "PavoTube II 30C"
        has(kit, Gear.AMARAN200XS) && notThis?.contains("200x") != true -> "amaran 200x S"
        else -> null
    }

    private fun shortSide(room: RoomFacts): Float =
        room.windowAngle?.let { if (abs(it) < 150) it.coerceIn(-75f, 75f) else null } ?: 40f

    fun plan(kit: Set<Gear>, type: ProjectType, mood: Mood, room: RoomFacts, wbDay: Boolean = true): List<PlannedSetup> {
        val out = mutableListOf<PlannedSetup>()
        val keySide = shortSide(room)
        val opp = -keySide.coerceIn(-90f, 90f)
        val dayK = room.windowK ?: 5600
        val faceTxt = "Face at ${Expo.formatStops(mood.face)} (Cam Zone Look), contrast about ${Expo.ratio(mood.ratioStops)} (${Expo.formatStops(mood.ratioStops).removePrefix("+")} stops)"

        // 1. Window key + negative fill
        run {
            val missing = mutableListOf<String>()
            val window = room.windowAngle != null
            val boost = softKey(kit)
            val lights = mutableListOf<PlannedLight>()
            if (window) {
                lights += PlannedLight("Key (window)", "The window", room.windowAngle!!, 2.5f, "as it is", "Turn the actor about 45° to it; don't let it sit behind them")
                if (boost != null) lights += PlannedLight("Window boost", boost, keySide, 1.8f, "a little above eye level, beside the window",
                    "$dayK K, start around 30%; it should feel like more window, not a new light")
            } else {
                if (boost == null) missing += "a key light"
                else lights += PlannedLight("Key (fake window)", boost, keySide, 1.6f, "a little above eye level", "${dayK}K, start around 50%")
            }
            if (has(kit, Gear.REFLECTOR)) lights += PlannedLight("Negative fill", "5-in-1, black side", opp, 0.8f, "face height, just out of frame", "closer = darker shadow side", MapItem.Shape.NEG)
            else missing += "something black for negative fill"
            val fit = (if (type in setOf(ProjectType.NARRATIVE, ProjectType.INTERVIEW, ProjectType.COMMERCIAL, ProjectType.WEDDING)) 2 else 1) +
                (if (mood in setOf(Mood.NATURAL, Mood.MOODY)) 3 else if (mood == Mood.BRIGHT || mood == Mood.WARM) 1 else 0) + (if (window) 2 else 0)
            out += PlannedSetup(
                "Window key with negative fill",
                "Use the room's window as the key and take light away on the other side. The fastest natural look on a small crew.",
                lights, faceTxt,
                listOf(
                    "Turn the actor so the window is to their side or a little in front, about 45°.",
                    "Shoot from the shadow side of the face (short side) for depth.",
                    "Put the black side of the 5-in-1 on the shadow side, close but out of frame. Read the contrast in Cam Zone.",
                    "If the window is too bright behind them, reframe or add ND gel; if it drifts, re-meter every 20 to 30 minutes.",
                ),
                listOf(
                    Source("Framecoach: lighting for indie films (subject at 45° to the window)", "https://theshumba.github.io/framecoach-blog/lighting-for-indie-films"),
                    Source("No Film School: documentary interview in 30 minutes (negative fill)", "https://nofilmschool.com/theo-gee-interview-lighting"),
                    Source("PremiumBeat: interiors with limited lighting", "https://www.premiumbeat.com/blog/shoot-interior-locations-limited-lighting"),
                ),
                missing, fit * 10 - missing.size * 15,
            )
        }

        // 2. One soft key, bounced or domed, plus neg fill (fast interview/commercial)
        run {
            val missing = mutableListOf<String>()
            val key = softKey(kit) ?: run { missing += "a key light"; null }
            val lights = mutableListOf<PlannedLight>()
            key?.let { lights += PlannedLight("Key", it, keySide, 1.5f, "about 30° above eye level, pointing down", "${dayK}K, start around 40%") }
            if (has(kit, Gear.REFLECTOR)) {
                if (mood == Mood.BRIGHT || mood == Mood.WARM) lights += PlannedLight("Fill (bounce)", "5-in-1, white side", opp, 1.0f, "face height or a little low", "move closer to soften shadows", MapItem.Shape.BOUNCE)
                else lights += PlannedLight("Negative fill", "5-in-1, black side", opp, 0.8f, "face height, out of frame", "shapes the shadow side", MapItem.Shape.NEG)
            }
            val fit = (if (type == ProjectType.INTERVIEW || type == ProjectType.COMMERCIAL || type == ProjectType.WEDDING) 4 else 1) +
                (if (mood == Mood.NATURAL || mood == Mood.BRIGHT) 2 else 1)
            out += PlannedSetup(
                "One soft key, bounce or negative fill",
                "One big soft source on the short side, then decide the shadows with the reflector. Corporate and doc shooters use it because it's fast and repeatable.",
                lights, faceTxt,
                listOf(
                    "Turn off the overhead room lights.",
                    "Put the key on the side the person is looking toward camera-away (short side), 30 to 45° off their eyeline.",
                    "Bright look: white bounce opposite. Moody look: black side opposite instead.",
                    "Leave as much distance as you can between the person and the background.",
                ),
                listOf(
                    Source("ProVideo Coalition: the simplest, fastest interview setup (one bounced light + neg fill)", "https://www.provideocoalition.com/the_simplest_fastest_interview_lighting_setup-ever/"),
                    Source("CineD: lighting an interview with one light", "https://www.cined.com/quick-tip-lighting-interview-with-one-light/"),
                    Source("PremiumBeat: cinematic one-light setups (reflector as fill)", "https://www.premiumbeat.com/blog/creating-cinematic-one-light-setups"),
                ),
                missing, fit * 10 - missing.size * 15,
            )
        }

        // 3. Soft key + rim + background (polished commercial / interview)
        run {
            val missing = mutableListOf<String>()
            val key = softKey(kit) ?: run { missing += "a key light"; null }
            val back = rim(kit, key)
            val lights = mutableListOf<PlannedLight>()
            key?.let { lights += PlannedLight("Key", it, keySide, 1.4f, "a little above eye level", "${dayK}K, start around 40%") }
            if (back != null) lights += PlannedLight("Rim / hair", back, if (keySide > 0) -150f else 150f, 1.2f, "high, behind, angled down at hair and shoulder", "same color as key, start around 30%")
            else missing += "a second light for the rim"
            if (has(kit, Gear.PAVOTUBE) && back != "PavoTube II 30C") lights += PlannedLight("Background accent", "PavoTube II 30C", 180f, 2.5f, "on the floor or hidden in set", "a warm or brand color, low")
            else if (has(kit, Gear.AMARAN200XS) && key?.contains("200x") != true && back?.contains("200x") != true)
                lights += PlannedLight("Background accent", "amaran 200x S", 175f, 2.8f, "aimed at the wall behind", "3200K slash for warmth")
            if (has(kit, Gear.REFLECTOR)) lights += PlannedLight("Shadow control", "5-in-1", opp, 0.9f, "face height", if (mood == Mood.BRIGHT) "white side for fill" else "black side for shape", if (mood == Mood.BRIGHT) MapItem.Shape.BOUNCE else MapItem.Shape.NEG)
            val fit = (if (type == ProjectType.COMMERCIAL || type == ProjectType.INTERVIEW) 5 else if (type == ProjectType.WEDDING || type == ProjectType.MUSIC) 3 else 1) +
                (if (mood == Mood.BRIGHT || mood == Mood.NATURAL || mood == Mood.WARM) 2 else 0)
            out += PlannedSetup(
                "Soft key, rim and background (commercial)",
                "The polished branded-video look: a big soft key, a rim to separate them, and a little light on the background for depth.",
                lights, faceTxt,
                listOf(
                    "Key first and get the face on target with Spot mode.",
                    "Add the rim and look for a thin bright edge on hair and shoulder, not a glow on the face.",
                    "Light the background last, low, so it never competes with the face.",
                    "If the key spills on the background, add the Light Dome's grid.",
                    "Inverse square law: to darken the background without touching the face, move the subject farther from the wall and the key closer to the face (then dim it down).",
                ),
                listOf(
                    Source("Karl Weinreich: cinematic interview lighting (600D + Light Dome III + grid)", "https://vigyata.ai/@KarlWeinreich/video/cinematic-interview-lighting-is-pretty-simple--tIsCAeRaahI"),
                    Source("Aputure: videography lighting (key, background depth, hair light)", "https://aputure.com/pages/videography-lighting"),
                    Source("CineD: key, fill, backlight and background", "https://www.cined.com/quick-tip-lighting-interview-with-one-light/"),
                ),
                missing, fit * 10 - missing.size * 15,
            )
        }

        // 4. Day interior: sun through the window
        run {
            val missing = mutableListOf<String>()
            val sun = hardKey(kit) ?: run { missing += "a strong hard light"; null }
            val lights = mutableListOf<PlannedLight>()
            val wa = room.windowAngle ?: 70f
            sun?.let { lights += PlannedLight("Sun", it, wa, 4.0f, "outside or far back, high, aimed through the window", if (mood == Mood.WARM) "5600K + ½ CTO for late-afternoon gold" else "5600K full") }
            if (has(kit, Gear.AMARAN200XS)) lights += PlannedLight("Soft key", "amaran 200x S" + if (has(kit, Gear.LIGHTDOME) && sun?.contains("Light Dome") != true) " + Light Dome III" else "", wa.coerceIn(-70f, 70f) * 0.8f, 1.5f, "near the window, eye level", "${dayK}K, lower than the sun beam")
            if (has(kit, Gear.REFLECTOR)) lights += PlannedLight("Bounce", "5-in-1, white", -wa.coerceIn(-90f, 90f), 1.2f, "catch the sun spill", "room-bounce feel", MapItem.Shape.BOUNCE)
            val fit = (if (type == ProjectType.NARRATIVE || type == ProjectType.COMMERCIAL) 4 else 2) +
                (if (mood == Mood.NATURAL || mood == Mood.WARM) 3 else 0) + (if (room.windowAngle != null) 1 else 0)
            out += PlannedSetup(
                "Day interior: sun through the window",
                "A hard light pushed through the window from far away for crisp sun streaks, with a softer key inside. Classic day-interior look.",
                lights, faceTxt,
                listOf(
                    "Put the hard light as far back and as high as you safely can. Inverse square law: from far away the light barely falls off across the room, so it reads like real sun.",
                    "Let the sun streak hit the floor or a wall in frame, not the face.",
                    "Key the face with the softer light from the same side, lower.",
                    "Keep the window from clipping in the Map LUT: orange is fine, red only for tiny highlights.",
                ),
                listOf(
                    Source("PremiumBeat / Cinematography Database: 3 ways to light a room (LED through windows for sun)", "https://www.premiumbeat.com/blog/3-ways-light-room-day"),
                    Source("Mediagear: simulating daylight (push from far away, Burum's Fresnels through windows)", "https://mediagear.ee/?p=358860"),
                    Source("Neil Oseman: Crossing Paths day interior (key through the window)", "https://neiloseman.com/crossing-paths-daylight-interior/"),
                ),
                missing, fit * 10 - missing.size * 15,
            )
        }

        // 5. Backlit by the window (bright beach-house rooms)
        run {
            val missing = mutableListOf<String>()
            val key = softKey(kit) ?: run { missing += "a key light"; null }
            val lights = mutableListOf<PlannedLight>()
            lights += PlannedLight("Back (window)", "The window, behind the actor", if (keySide > 0) -160f else 160f, 2.5f, "as it is", "rim on hair and shoulders; keep the window itself out of frame or soft")
            key?.let { lights += PlannedLight("Key", it, keySide.coerceIn(-50f, 50f), 1.5f, "eye level to slightly above", "${dayK}K, bright enough that the face isn't a silhouette") }
            if (has(kit, Gear.REFLECTOR)) lights += PlannedLight("Bounce", "5-in-1, silver or white", -keySide.coerceIn(-45f, 45f), 1.0f, "low, facing the window light back", "free fill from the window", MapItem.Shape.BOUNCE)
            val bright = (room.windowStops ?: 0.0) >= 3
            val fit = 2 + (if (bright) 4 else 1) + (if (mood == Mood.BRIGHT || mood == Mood.NATURAL || mood == Mood.WARM) 2 else 0)
            out += PlannedSetup(
                "Window behind, key in front",
                "When windows are very bright, use them as the backlight instead of fighting them, and key the face from inside. Works well in bright rooms like beach houses.",
                lights, faceTxt,
                listOf(
                    "Put the actor with the window behind them and to one side.",
                    "Shoot facing away from the window where you can, so it isn't in frame.",
                    "Key the face until it reads green; the window rim should sit in yellow or orange, not red.",
                    "Add ND on the lens if you can't hold your stop.",
                ),
                listOf(
                    Source("B&H: lighting interiors with daylight (sun as backlight, cards to push light)", "https://www.bhphotovideo.com/explora/node/89506"),
                    Source("PremiumBeat: shoot facing away from the window", "https://www.premiumbeat.com/blog/shoot-interior-locations-limited-lighting"),
                    Source("diyphotography: window key plus silver reflector", "https://www.diyphotography.net/?p=79039"),
                ),
                missing, fit * 10 - missing.size * 15,
            )
        }

        // 6. Night interior: warm practical key + cool moonlight
        run {
            val missing = mutableListOf<String>()
            val moon = (if (has(kit, Gear.LS600D)) (if (has(kit, Gear.FRESNEL)) "600d + Fresnel lens" else "600d") else if (has(kit, Gear.AMARAN200XS)) "amaran 200x S at 6500K" else null)
                ?: run { missing += "a light for moonlight"; null }
            val warm = if (has(kit, Gear.PAVOTUBE)) "PavoTube II 30C" else if (has(kit, Gear.AMARAN200XS) && moon?.contains("200x") != true) "amaran 200x S" else null
            val lights = mutableListOf<PlannedLight>()
            val pa = room.practicalAngle ?: keySide
            moon?.let { lights += PlannedLight("Moonlight", it, room.windowAngle ?: -140f, 3.5f, "high, through the window if there is one", "5600K with the PYXIS at 3200K = soft blue; add ½ CTO if it's too blue") }
            if (warm != null) lights += PlannedLight("Practical key", warm, pa, 1.0f, "near the lamp, hidden, face height", "3000–3200K, low; should look like the lamp is lighting them")
            else missing += "a second light for the practical key"
            val fit = (if (type == ProjectType.NARRATIVE || type == ProjectType.HORROR || type == ProjectType.MUSIC) 3 else 0) +
                (if (mood == Mood.NIGHT) 5 else if (mood == Mood.MOODY || mood == Mood.DARK) 2 else 0)
            out += PlannedSetup(
                "Night interior: warm practical, cool moonlight",
                "White balance for tungsten, let a daylight source through the window read as moonlight, and key faces warm from the lamp's direction.",
                lights, faceTxt,
                listOf(
                    "Set the PYXIS to 3200K. Turn on the practical lamps in frame (dim them).",
                    "Put the moonlight outside or far back, high, through the window or across the back of the room.",
                    "Key the face from the lamp's side with the warm light, low and close. Close light falls off fast (inverse square law), so the room stays dark around them.",
                    "Expose the face properly; let the room fall into blue in the Map LUT, not purple.",
                ),
                listOf(
                    Source("No Film School / Aputure: moonlight on a budget (window moonlight, warm practicals)", "https://nofilmschool.com/how-to-create-moonlight"),
                    Source("PremiumBeat: day-for-night interiors (½ CTO, 3200K base)", "https://www.premiumbeat.com/blog/lighting-day-for-night-interiors"),
                    Source("Wandering DP: night interior setups", "https://wanderingdp.com/?p=4758"),
                ),
                missing, fit * 10 - missing.size * 15,
            )
        }

        // 7. Horror: one hard source / underlight
        run {
            val missing = mutableListOf<String>()
            val hard = hardKey(kit) ?: run { missing += "a hard light"; null }
            val lights = mutableListOf<PlannedLight>()
            hard?.let { lights += PlannedLight("Hard key", it, 85f, 2.5f, "eye level or low; far away for a hard edge", "start around 40%; long shadows on the wall") }
            if (has(kit, Gear.PAVOTUBE)) lights += PlannedLight("Underlight (motivated)", "PavoTube II 30C", 10f, 0.7f, "on the floor or table, below the chin", "only when something motivates it: a phone, a flashlight, a candle")
            if (has(kit, Gear.REFLECTOR)) lights += PlannedLight("Negative fill", "5-in-1, black side", -80f, 0.8f, "shadow side", "keep the dark side dark", MapItem.Shape.NEG)
            val fit = (if (type == ProjectType.HORROR) 6 else if (type == ProjectType.MUSIC || type == ProjectType.NARRATIVE) 2 else 0) +
                (if (mood == Mood.DARK || mood == Mood.NIGHT) 3 else if (mood == Mood.MOODY) 1 else 0)
            out += PlannedSetup(
                "Horror: one hard source and underlight",
                "Show less. One hard light at the side for long shadows, an underlight only when the scene motivates it, and the rest left dark.",
                lights, faceTxt,
                listOf(
                    "Kill every room light. Start in the dark.",
                    "Place one hard light far to the side so the shadow on the wall stretches.",
                    "Use underlight only with a reason in frame (phone, flashlight).",
                    "Keep the eyes readable: the face around −½ to 0, background in blue and purple.",
                ),
                listOf(
                    Source("No Film School: 5 horror setups with one light", "https://nofilmschool.com/2017/10/5-horror-lighting-setups-you-can-do-one-light"),
                    Source("No Film School: horror on an indie budget (underlighting)", "https://nofilmschool.com/light-a-horror-scene-on-budget"),
                    Source("DIY Photography / Aputure: 5 horror lighting techniques", "https://www.diyphotography.net/?p=97987"),
                ),
                missing, fit * 10 - missing.size * 15,
            )
        }

        // 8. Tubes and practicals (music video / stylized)
        run {
            val missing = mutableListOf<String>()
            if (!has(kit, Gear.PAVOTUBE)) missing += "PavoTube II 30C"
            val lights = mutableListOf<PlannedLight>()
            if (has(kit, Gear.PAVOTUBE)) {
                lights += PlannedLight("Tube key", "PavoTube II 30C", keySide, 1.0f, "vertical on a stand or held, close to the face", "pick a color with intent; start around 50%")
                lights += PlannedLight("Tube rim / in frame", "PavoTube II 30C (second tube, or the same tube moved)", if (keySide > 0) -140f else 140f, 1.5f, "behind, can be in frame as a practical", "contrasting color")
            }
            if (has(kit, Gear.AMARAN200XS)) lights += PlannedLight("Base fill", "amaran 200x S bounced", 0f, 2.5f, "into the ceiling or a wall", "very low, just to lift the blacks", MapItem.Shape.BOUNCE)
            val fit = (if (type == ProjectType.MUSIC) 6 else if (type == ProjectType.COMMERCIAL || type == ProjectType.NARRATIVE) 2 else 0) +
                (if (mood == Mood.MOODY || mood == Mood.NIGHT || mood == Mood.DARK) 2 else 1)
            out += PlannedSetup(
                "Tubes and practicals",
                "Start from the practicals already in the room, then add tubes as key and rim. Tubes can sit in frame as part of the set.",
                lights, faceTxt,
                listOf(
                    "Turn on the room's practicals first and see what they give you.",
                    "Add a tube as key close to the face, then one as rim or in frame.",
                    "Two colors max for a clean look; let skin stay close to natural in the key.",
                ),
                listOf(
                    Source("No Film School: lighting almost a whole film with PavoTube II 30Cs", "https://nofilmschool.com/light-almost-whole-film-tubes"),
                    Source("4K Shooters / Make Better Content: tube light looks (key + rim/practical)", "https://4kshooters.net/2022/11/12/how-to-use-tube-lights-and-create-different-looks"),
                ),
                missing, fit * 10 - missing.size * 15,
            )
        }

        return out.sortedByDescending { it.score }.take(4)
    }

    /** Map items for a setup plus the room's tagged sources. */
    fun mapItems(setup: PlannedSetup?, s: ScoutSession?, settings: CamSettings): List<MapItem> {
        val items = mutableListOf<MapItem>()
        if (s != null && s.cameraAz != null) {
            val subj = s.subjectEv ?: Expo.grayEv(settings)
            s.tags.forEach { t ->
                val az = t.orientation?.azimuth ?: return@forEach
                var a = az - s.cameraAz!!
                while (a > 180) a -= 360
                while (a < -180) a += 360
                items += MapItem(t.type.label, a, t.distM, 0xFF9AA0A6, MapItem.Shape.SOURCE,
                    "${Expo.formatStops(t.ev - subj)}" + (t.kelvin?.let { " · ${it}K" } ?: ""))
            }
        }
        setup?.lights?.forEach { l ->
            val c = when (l.shape) { MapItem.Shape.NEG -> 0xFF222222; MapItem.Shape.BOUNCE -> 0xFFDDDDDD; else -> 0xFFFFB000 }
            items += MapItem(l.role, l.angle, l.distM, c, l.shape)
        }
        return items
    }

    fun describe(l: PlannedLight): String {
        val side = when {
            abs(l.angle) < 15 -> "beside camera"
            abs(l.angle) > 150 -> "behind the subject"
            l.angle > 0 -> "camera-left, ${l.angle.roundToInt()}°"
            else -> "camera-right, ${(-l.angle).roundToInt()}°"
        }
        val ft = (l.distM * 3.28f).roundToInt()
        return "$side, about ${"%.1f".format(l.distM)} m ($ft ft) from the subject, ${l.height}. ${l.setting}."
    }
}
