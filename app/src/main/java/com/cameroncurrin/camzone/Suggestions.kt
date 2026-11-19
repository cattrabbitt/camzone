package com.cameroncurrin.camzone

import kotlin.math.abs
import kotlin.math.ceil

sealed class Action {
    data class SetIris(val t: Double) : Action()
    data class SetIso(val iso: Int) : Action()
    /** Done by hand: records the ND in the app so the advice updates. */
    data class AddNd(val stops: Double) : Action()
    /** Done by hand: clears the reading so Cam meters again. */
    object Remeter : Action()
    /** Phone mode: sets the phone's own ISO. */
    data class SetPhoneIso(val iso: Int) : Action()
}

/**
 * One piece of advice. [action] is what the Apply button sends; [manual] is what to do by hand
 * if there's no connection or the camera can't take the change.
 */
data class Suggestion(
    val id: String,
    val title: String,
    val detail: String,
    val action: Action? = null,
    val manual: String? = null,
    val good: Boolean = false,
)

object Advisor {

    fun build(
        settings: CamSettings,
        look: Look,
        face: Spot?,
        frame: ZoneFrame?,
        calibration: Double,
        widest: Double,
        narrowest: Double,
    ): List<Suggestion> {
        val out = mutableListOf<Suggestion>()

        if (face == null) {
            out += Suggestion("start", "Tap the face to meter it",
                "Switch to Spot, then tap your subject's face (or the gray card). Advice appears here.")
        } else {
            val now = Expo.stopsAt(face.ev, settings)
            val target = AppState.matchTarget.value ?: look.target
            val need = Expo.roundT(Expo.tStopFor(face.ev, target, settings))
            val delta = Expo.stopsBetween(settings.tStop, need) // + = too bright, close down
            val zone = Expo.zone(now)

            if (abs(now - target) < 0.2) {
                out += Suggestion("iris", "Face is on target", "${Expo.formatStops(now)} stops, ${zone.label.lowercase()}. Ready to roll.", good = true)
            } else if (delta > 0) {
                // Too bright
                val nd = ceil(delta - 0.17).coerceAtLeast(1.0)
                out += Suggestion("nd", "Face is ${Expo.formatStops(now - target)} too bright",
                    "To keep ${Expo.formatT(settings.tStop)} and your depth of field, add ${Expo.ndName(nd)} (${nd.toInt()} stop${if (nd > 1) "s" else ""}) or dim the key light.",
                    action = Action.AddNd(nd),
                    manual = "Add ${Expo.ndName(nd)} on the lens or matte box, then tap Done. If you dimmed the light instead, tap Re-meter.")
                if (need <= narrowest) {
                    out += Suggestion("iris", "Or close the iris to ${Expo.formatT(need)}",
                        "Quickest fix. Changes depth of field.",
                        action = Action.SetIris(need),
                        manual = "Turn the iris to ${Expo.formatT(need)} on the PYXIS (Iris on the touchscreen).")
                }
            } else {
                // Too dark
                val short = -delta
                if (need >= widest) {
                    out += Suggestion("light", "Face is ${Expo.formatStops(now - target)} too dark",
                        "Add ${Expo.formatStops(short).removePrefix("+")} stop of light to the key (moving it to about 70% of its distance adds 1 stop), or open the iris.",
                        action = Action.Remeter,
                        manual = "Brighten or move the key light closer, then tap Re-meter and tap the face again.")
                    out += Suggestion("iris", "Or open the iris to ${Expo.formatT(need)}",
                        "Quickest fix. Shallower depth of field.",
                        action = Action.SetIris(need),
                        manual = "Turn the iris to ${Expo.formatT(need)} on the PYXIS (Iris on the touchscreen).")
                } else {
                    val missing = Expo.stopsBetween(widest, need) * -1
                    out += Suggestion("light", "Not enough light for this lens",
                        "Even wide open at ${Expo.formatT(widest)} the face sits ${Expo.formatStops(-missing)} under. Add light, or switch to ISO 3200 for 3 more stops.",
                        action = Action.Remeter,
                        manual = "Add light to the face, then tap Re-meter and tap the face again.")
                    if (settings.iso < 1250 && missing >= 1.0) {
                        out += Suggestion("iso", "Or switch to ISO 3200",
                            "Uses the PYXIS's high native ISO. Less highlight room than ISO 400.",
                            action = Action.SetIso(3200),
                            manual = "Set ISO to 3200 on the PYXIS (ISO on the touchscreen).")
                    }
                }
            }
        }

        // Highlights anywhere in the phone's view
        if (frame != null) {
            var red = 0
            var orange = 0
            for (i in frame.ev.indices) {
                val s = frame.ev[i] + calibration - Expo.grayEv(settings)
                if (s >= Expo.CLIP) red++ else if (s >= Expo.WARN) orange++
            }
            val n = frame.ev.size.toDouble()
            val redPct = red / n * 100
            val orangePct = orange / n * 100
            if (redPct > 0.3) {
                out += Suggestion("clip", "Something is clipping (${"%.1f".format(redPct)}% of frame)",
                    "If it matters (window, sky, wardrobe), add ND gel, flag it, or dim it. Bare bulbs can stay red. Confirm on the PYXIS Map LUT.")
            } else if (orangePct > 1.0) {
                out += Suggestion("warn", "Highlights close to clipping",
                    "${"%.1f".format(orangePct)}% of the frame is orange. Check it on the PYXIS Map LUT before you roll.")
            }
        }
        return out
    }
}
