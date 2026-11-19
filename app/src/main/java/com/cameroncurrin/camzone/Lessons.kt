package com.cameroncurrin.camzone

/** A light in a top-down diagram. Angle is around the subject: 0 = beside the camera, 90 = camera-left side, 180 = behind. */
data class DLight(val label: String, val angle: Float, val dist: Float = 1f, val kind: Kind = Kind.KEY) {
    enum class Kind { KEY, FILL, BACK, BOUNCE, NEG, PRACTICAL, WINDOW }
}

data class Lesson(
    val id: String,
    val title: String,
    val oneLine: String,
    val body: List<String>,
    val tryIt: List<String> = emptyList(),
    val gear: String? = null,
    val diagram: List<DLight>? = null,
    val subjects: Int = 1,
)

object Lessons {

    val basics = listOf(
        Lesson(
            "exposure", "Exposure",
            "How bright the picture is. You control it with light, iris, shutter, ISO and ND.",
            listOf(
                "Exposure is how much light reaches the sensor. Too little and the picture is dark and noisy. Too much and the bright parts turn pure white and lose all detail. That's called clipping, and you can't get it back.",
                "Five things change exposure: the light on the scene, the iris (T-stop), the shutter, ISO, and ND filters. On a film set, the shutter is usually locked at 180° for natural motion blur, so you mostly use light, iris and ND.",
                "Good exposure isn't one brightness. It's putting the important thing, usually the face, where you want it, and keeping anything you care about out of clipping.",
                "With the PYXIS shooting BRAW, ISO doesn't add light. It only decides how much room you keep above and below middle gray. Light and iris are what really change exposure.",
            ),
            tryIt = listOf(
                "Put the Map LUT on. Open and close the iris and watch the face move through blue, green and yellow.",
                "Find something that goes orange or red, like a window or lamp. That's the part about to lose detail.",
            ),
        ),
        Lesson(
            "stops", "Stops",
            "One stop = twice the light, or half. Everything in exposure is counted in stops.",
            listOf(
                "A stop is a doubling or halving of light. +1 stop is twice as bright, −1 stop is half as bright, +2 stops is four times as bright.",
                "Iris: the full stops are T1.4, 2, 2.8, 4, 5.6, 8, 11, 16, 22. Each step to the right lets in half the light. T2.8 to T4 is one stop darker.",
                "ND filters: 0.3 = 1 stop, 0.6 = 2 stops, 0.9 = 3 stops, 1.2 = 4 stops.",
                "Distance: move a light twice as far away and you lose 2 stops. Move it about 1.4 times as far and you lose 1 stop. That's the inverse square law.",
                "Dimmers: an LED at 50% is about 1 stop less than at 100%. At 25% it's about 2 stops less.",
                "Contrast in stops: if the bright side of a face is 2 stops over the dark side, that's a 4:1 ratio. 1 stop = 2:1 (soft), 2 stops = 4:1 (dramatic), 3 stops = 8:1 (noir).",
                "Cam Zone counts everything in stops from middle gray, so +1 means one stop brighter than a gray card.",
            ),
            tryIt = listOf(
                "Meter a face in Spot mode. Close the iris one stop on the PYXIS (T2.8 to T4) and watch the reading drop by 1.",
                "Use Contrast mode on a face lit from the side. Read the ratio, then add the 5-in-1 reflector on the dark side and read it again.",
            ),
        ),
        Lesson(
            "zebras", "Zebras",
            "Stripes that appear on anything brighter than a level you choose.",
            listOf(
                "Zebras are moving stripes the camera draws over parts of the picture brighter than a level you set. The stripes never get recorded.",
                "Set them at 100% (or Cam Zone's clip level) and they mean: this part is losing detail. If it's a window you care about or someone's face, fix it. If it's a bare bulb, that's usually fine.",
                "Set them lower, around 70 to 80%, and some shooters use them to spot skin that's getting too hot. Cam Zone's zebras can be set at +1⅓ stops for that.",
                "Zebras only warn about the bright end. For the whole picture, use the Map LUT or the zones in Cam Zone.",
            ),
            tryIt = listOf(
                "PYXIS: turn on zebras at 100%, point at a lamp. Then open the iris until the lampshade gets stripes too.",
                "In Cam Zone: Tools row > Zebras, and try each level.",
            ),
        ),
        Lesson(
            "candles", "Foot-candles, lux and candles",
            "How lighting crews measure how much light hits something.",
            listOf(
                "A foot-candle is the light from one candle on a surface one foot away. Lux is the metric version: 1 foot-candle is about 10.8 lux.",
                "These measure light falling on the subject (incident light), the way a gaffer's meter does with the white dome. A spot reading like Cam Zone's measures light bouncing off something instead.",
                "Rough real-world numbers: a living room at night 5 to 20 fc, an office 30 to 50 fc, overcast daylight outside 1,000+ fc, direct sun around 10,000 fc.",
                "What your PYXIS needs: at ISO 400, 180° shutter, 24 fps, a face at middle gray needs about 25 foot-candles (about 270 lux) at T2.8. At T4 it needs twice that, about 50 fc. At ISO 3200 it needs one eighth as much.",
                "That's why a beach house is hard: daylight through windows is hundreds of foot-candles, so your lights have to work hard to compete, or you use ND.",
            ),
            tryIt = listOf(
                "At home at night, meter a face lit only by a lamp. Then turn on the 600d and see how many stops it adds.",
            ),
        ),
        Lesson(
            "inverse", "The inverse square law",
            "Twice as far from the light = a quarter of the light = 2 stops darker.",
            listOf(
                "Light spreads out as it travels. Every time you double the distance between a light and your subject, the light on them drops to one quarter: 2 stops darker. Halve the distance and it's 4 times brighter: 2 stops more.",
                "The one series to memorize: 1, 1.4, 2, 2.8, 4, 5.6. Each step farther is 1 stop less light. It's the same series as T-stops, so you already know it. Light at 1 m → move it to 1.4 m = −1 stop. To 2 m = −2 stops.",
                "Falloff is what makes it useful. A light close to the face falls off fast: the face is bright, the background behind them goes dark. That's moody, contrasty, and great for low-key and night scenes.",
                "A light far away falls off slowly: the face and the background get nearly the same light. That's even and natural, like sun through a window. To light a whole room evenly, back the light up and turn it up.",
                "Example: key 1 m from the face, wall 2 m behind them (3 m from the light). The wall gets about 3 stops less than the face. Move the key back to 3 m and the wall (5 m away) is only about 1½ stops under.",
                "Soft light follows the same law once you're farther away than the size of the source. Close in, a big soft source like the Light Dome falls off fast, which is why it wraps the face and leaves the background dark.",
                "Use it instead of the dimmer when you want to change the look, not just the brightness: distance changes the falloff, the dimmer doesn't.",
            ),
            tryIt = listOf(
                "Drill 1, the series: put the amaran 200x S 1 m from a gray card. Meter it in Spot mode. Move the light to 1.4 m, 2 m and 2.8 m and meter each time. You should see about −1, −2 and −3. Do this once and the numbers stick.",
                "Drill 2, falloff: actor 1 m in front of a wall, key 1 m from the actor. Tap the face, then the wall, and note the difference. Now move the key to 3 m, dim it up until the face matches, and tap the wall again. See how much closer the wall gets.",
                "Drill 3, guess first: before you meter any setup, guess the stop change from the distance. Then check in Cam Zone. A week of guessing on every setup and you'll do it by eye.",
                "Use the calculator below on set: how far to move a light for the change you want.",
            ),
            diagram = listOf(DLight("Key 1 m: fast falloff", 40f, 0.55f), DLight("Same light at 2 m: −2 stops", 40f, 1.1f, DLight.Kind.FILL)),
        ),
        Lesson(
            "framing", "Blocking and framing grids",
            "Blocking is where the actors stand and move. Framing grids help you place them in the shot.",
            listOf(
                "Blocking: decide where each actor starts, where they move, and where they stop, before you light. Put tape marks on the floor so they hit the same spot every take, and light those marks, not the whole room.",
                "Block for the light, not against it: turn the actor so the key falls on the side of the face away from camera (short side). If they cross the room, check the face at each mark with Spot mode.",
                "Stage in depth: put people and objects at different distances from the camera (foreground, subject, background). It makes a small room look bigger and gives the light layers to separate.",
                "Rule of thirds grid: two lines across and two down. Put the eyes on the top line, near a crossing point, and leave more space on the side the person faces (looking room).",
                "Center framing: put the subject dead center for symmetry, power or confrontation. It works when it's a choice, not an accident.",
                "Headroom and edges: keep a little space above the head in mediums; in tight close-ups it's fine to crop the top of the head, never the chin. Watch the frame edges for clutter and bright spots that pull the eye.",
                "Aspect guides: if you'll deliver in 2.39 or 1.85, frame for that guide, not the full sensor, or you'll lose heads and hands later.",
            ),
            tryIt = listOf(
                "Tools button on the feed > Grid. Block an actor on two marks and frame the eyes on the top line at both.",
                "Turn on the 2.39 guide and re-frame the same shot. Notice how much more room you need on the sides.",
            ),
        ),
    )

    val techniques = listOf(
        Lesson(
            "threepoint", "Three-point lighting",
            "Key, fill and backlight: the classic starting setup.",
            listOf(
                "Key: the main light, usually 30 to 45° off the camera and a bit above eye level.",
                "Fill: a softer, weaker light on the other side that lifts the shadows. How much weaker sets the mood.",
                "Back (rim): behind the subject, opposite the key, to separate them from the background with a line of light on the hair and shoulders.",
                "It's a starting point, not a rule. Most film lighting removes one of the three on purpose.",
            ),
            tryIt = listOf("Light a face key-only, then add fill and read the contrast, then add the back light and watch the edge appear."),
            gear = "600d + Light Dome III as key, amaran 200x S bounced or the 5-in-1 as fill, PavoTube II 30C as back.",
            diagram = listOf(DLight("Key", 40f), DLight("Fill", -40f, 0.9f, DLight.Kind.FILL), DLight("Back", 155f, 0.9f, DLight.Kind.BACK)),
        ),
        Lesson(
            "rembrandt", "Rembrandt",
            "A small triangle of light on the shadow cheek. Classic, moody, painterly.",
            listOf(
                "Put the key about 45° to the side and 45° above the face, pointing down at it.",
                "Look for a small triangle of light under the eye on the dark side of the face. If the triangle closes up, swing the light a little toward the camera. If it gets too big, swing it away.",
                "Keep the fill low or use none for a dramatic look, around 3:1 to 4:1.",
            ),
            gear = "600d + Light Dome III high on a C-stand at 45°. No fill, or the 5-in-1 far away.",
            diagram = listOf(DLight("Key 45° high", 45f)),
        ),
        Lesson(
            "loop", "Loop",
            "A small shadow from the nose that loops down toward the cheek. Flattering for almost everyone.",
            listOf(
                "Key 30 to 45° to the side and a little above eye level.",
                "The nose shadow should point down and slightly to the side, without touching the cheek shadow.",
                "Add soft fill on the other side for a natural, friendly look around 2:1.",
            ),
            gear = "600d + Light Dome III, 5-in-1 white side as fill.",
            diagram = listOf(DLight("Key 30° high", 30f), DLight("Bounce", -50f, 0.8f, DLight.Kind.BOUNCE)),
        ),
        Lesson(
            "butterfly", "Butterfly (Paramount)",
            "Key straight in front and high: a small butterfly-shaped shadow under the nose. Glamour and beauty.",
            listOf(
                "Put the key right above the camera, high, pointing down at the face.",
                "Look for a small shadow under the nose shaped like a butterfly, and shadows under the cheekbones.",
                "Add a reflector under the chin, pointing up, to fill the eye sockets. That's called clamshell lighting.",
            ),
            gear = "600d + Light Dome III high over the lens; 5-in-1 silver or white below the face.",
            diagram = listOf(DLight("Key high", 0f), DLight("Reflector low", 0f, 0.55f, DLight.Kind.BOUNCE)),
        ),
        Lesson(
            "split", "Split",
            "Key at 90°: half the face lit, half in shadow. Mystery, conflict, two sides of a character.",
            listOf(
                "Put the key straight out to the side at 90°, at eye level.",
                "The nose should split the face down the middle: one half lit, the other dark.",
                "Use no fill, or very little, for the strongest effect. Good for horror and thrillers.",
            ),
            gear = "600d bare or with the Fresnel lens for a harder edge.",
            diagram = listOf(DLight("Key 90°", 90f)),
        ),
        Lesson(
            "shortbroad", "Short vs broad lighting",
            "When a face is turned, light the side away from camera (short) or the side toward camera (broad).",
            listOf(
                "Turn the subject slightly away from camera. The side of the face you see more of is the broad side.",
                "Short lighting: key on the far side, so the side toward camera falls into shadow. Slimming, moody, cinematic. Most drama is lit this way.",
                "Broad lighting: key on the near side. Brighter, flatter, more open. Common in TV and corporate.",
            ),
            tryIt = listOf("Have someone look a little off-camera. Move the key to the far side of their eyeline and compare."),
            diagram = listOf(DLight("Short key", -50f), DLight("Eyeline →", 30f, 0.6f, DLight.Kind.NEG)),
        ),
        Lesson(
            "window", "Motivated window light",
            "Make your light look like it comes from a real source in the room, like a window.",
            listOf(
                "Motivated light means every light seems to come from something in the scene: a window, a lamp, a TV.",
                "Turn the actor so the window is to their side or a little in front of them, then add your key from the same direction, through or beside the window, so it looks like daylight.",
                "Match the color: set the key to the window's color temperature (Cam Zone's Scout tab estimates it).",
                "At a beach house: big bright windows. Keep them out of the face's side of frame or knock them down with ND gel, and use the window as the key direction.",
            ),
            gear = "600d through the Light Dome III or a diffusion frame, placed where the window is.",
            diagram = listOf(DLight("Window", 60f, 1.1f, DLight.Kind.WINDOW), DLight("Key (as window)", 55f, 0.8f), DLight("Neg fill", -60f, 0.7f, DLight.Kind.NEG)),
        ),
        Lesson(
            "book", "Book light",
            "Bounce a light into a white surface, then through diffusion. Very soft, very even, a big cinematic look.",
            listOf(
                "Point the light away from the subject into a white bounce, like the white side of the 5-in-1 or a foam board.",
                "Put diffusion between the bounce and the subject. The light passes through two layers of softness, like a page in a book.",
                "It's very soft and wraps around the face, but it costs a lot of light, roughly 2 to 3 stops. Use your strongest light.",
            ),
            gear = "600d bounced into the 5-in-1 white side, through a diffusion frame.",
            diagram = listOf(DLight("600d → bounce", 70f, 1.15f, DLight.Kind.BOUNCE), DLight("Diffusion", 45f, 0.7f)),
        ),
        Lesson(
            "crosskey", "Cross key (two people)",
            "Two people facing each other: each one's key is the other one's backlight.",
            listOf(
                "Place one light behind each person, aimed at the other person's face.",
                "Each light becomes the key for the person it faces and a rim light for the person it's behind. Two lights do the work of four.",
                "Shoot each person from the side the light falls on, so you see the shadow side of the face (short lighting).",
            ),
            gear = "600d and amaran 200x S, each with a soft modifier.",
            diagram = listOf(DLight("Light A", 140f, 1f), DLight("Light B", -140f, 1f, DLight.Kind.FILL)),
            subjects = 2,
        ),
        Lesson(
            "rim", "Backlight, rim and silhouette",
            "Light from behind outlines the subject. With no front light, they become a silhouette.",
            listOf(
                "A backlight behind and above the subject draws a bright edge on hair and shoulders and pulls them off the background.",
                "A rim light is lower and more to the side, tracing the edge of the face and body.",
                "Silhouette: expose for the bright background (a window, a sunset) and add no front light. The subject goes dark and shape is all you see.",
            ),
            gear = "PavoTube II 30C behind, or the window itself for a silhouette.",
            diagram = listOf(DLight("Back", 165f, 1f, DLight.Kind.BACK), DLight("Rim", -130f, 0.9f, DLight.Kind.BACK)),
        ),
        Lesson(
            "negfill", "Negative fill",
            "Take light away instead of adding it. Black on the shadow side adds shape.",
            listOf(
                "Rooms bounce light everywhere, which flattens faces. Put something black (the 5-in-1 black side, a flag) on the shadow side of the face, close but out of frame.",
                "The shadow side gets darker and the face gets more shape, without moving a single light.",
                "Quick fix for flat, boring locations, and for daylight interiors at a beach house where white walls bounce light everywhere.",
            ),
            gear = "5-in-1 black side, or a flag on a C-stand.",
            diagram = listOf(DLight("Key", 45f), DLight("Black", -60f, 0.6f, DLight.Kind.NEG)),
        ),
        Lesson(
            "lowkey", "High-key and low-key",
            "Bright and even, or dark and moody: the overall feel of a scene.",
            listOf(
                "High-key: lots of light, low contrast, bright backgrounds, few shadows. Comedies, ads, happy scenes. Faces around +1 to +1⅓, fill strong, 2:1 or less.",
                "Low-key: mostly dark, strong contrast, deep shadows. Horror, thrillers, drama. Faces around 0 to −½, little or no fill, 4:1 to 8:1, backgrounds falling into blue.",
                "Expose the face properly in low-key. Make the darkness with contrast, not by underexposing everything, or the footage gets noisy.",
            ),
            tryIt = listOf("Set Look to Normal, then Dark, in the Meter tab. Light a face for each and compare the contrast readings."),
        ),
        Lesson(
            "practicals", "Practicals",
            "Lamps and lights that show up in the shot. They make a scene feel real.",
            listOf(
                "A practical is any light that's visible in frame: a table lamp, string lights, a TV, a neon sign.",
                "They're often too bright on camera. Dim them, use a lower-watt bulb, or wrap the inside of the shade.",
                "Let the bulb go red in Cam Zone if it's tiny, but keep the lampshade in yellow or orange so it still has texture.",
                "Then light the actor so it looks like the practical is doing the work: your key from the lamp's direction, warm like the lamp.",
            ),
            gear = "PavoTube II 30C hidden near the lamp, set to the lamp's color.",
            diagram = listOf(DLight("Lamp (practical)", 70f, 0.9f, DLight.Kind.PRACTICAL), DLight("Key from lamp side", 60f, 0.65f)),
        ),
    )
}
