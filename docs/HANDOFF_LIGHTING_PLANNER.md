# Handoff: Cam Zone scouts → Lighting Planner (Gaussian splat room scans)

Paste this file into the Lighting Planner thread (and the Production Hub thread for section 4).
It describes what the Cam Zone phone app now produces and what the PC side needs to build.

Cam's priority: **the workflow when preparing to light a set must be smooth and quick to set up and work with.**

## 1. What the phone sends (`camzone-scout`, version 1)

A zip named `<Location>_<yyyy-MM-dd_HHmm>.zip` containing one folder:

```
<Location>_<date>/
  scout.json
  photos/
    walk_0001.jpg …   walkthrough photos, 2048x1536 target, 4:3
    tag_0045.jpg …    one photo per tagged light source, aimed at its center
    subject_0060.jpg  photo of the subject spot (gray card at face height)
```

Walkthrough photos are taken automatically every ~8° of turn while the phone is steady (<25°/s),
with **one locked exposure for the whole walk** (shutter 1/125 or faster, ISO as needed, white balance
locked to a preset, linear 2.2-gamma tone curve). Locked exposure is deliberate: splat training is
cleaner when brightness doesn't change between frames. JPEGs may carry an EXIF rotation tag; normalize
before COLMAP.

`scout.json`:

```json
{
  "format": "camzone-scout", "version": 1,
  "name": "Beach house",
  "created": "2026-11-22T09:14:00-0500",
  "pyxis": { "iso": 400, "shutterAngle": 180, "fps": 24, "tStop": 2.8, "ndStops": 0,
             "middleGrayEv100": 6.56 },
  "phoneCalibrationStops": 0.0,
  "scaleNote": "1 m tape measure on the floor",
  "subject": { "ev100": 7.1, "stopsFromPyxisGray": 0.54, "photo": "subject_0060.jpg" },
  "photos": [ { "file": "walk_0001.jpg", "timeMs": 1763…, "orientation": { "azimuth": 12.0, "pitch": -5.1, "roll": 1.2 } } ],
  "lightSources": [
    { "type": "window", "ev100": 12.4, "luminanceCdM2": 674.0, "stopsFromPyxisGray": 5.84,
      "kelvin": 5900, "photo": "tag_0045.jpg", "orientation": { … } }
  ]
}
```

- `ev100` is a reflected (spot) reading, calibrated to the PYXIS with a gray card.
- `luminanceCdM2 = 0.125 × 2^ev100` (reflected meter constant K = 12.5). For a window or lamp face this
  is the surface luminance to give that emitter in the plan.
- `type` is one of `window, practical, ceiling, door, surface, other`.
- `kelvin` is an **estimate** from the phone (white balance locked to a preset, McCamy CCT, corrected
  for the preset). Treat it as ±300 K. Null if it couldn't be read.
- `orientation` is the phone's azimuth/pitch/roll in degrees from Android's rotation-vector sensor
  (azimuth relative to magnetic north). Useful to sanity-check COLMAP poses; not required.

## 2. PC pipeline (Lighting Planner side)

Goal: drag the zip onto Lighting Planner (or let it arrive through the hub, section 4), and a few minutes
later the room is in the planner as a photoreal splat plus a simple mesh, with the windows and lamps
already placed as light sources.

1. **Unzip and normalize** the photos (apply EXIF rotation).
2. **Camera poses:** run the existing COLMAP capture route on `photos/walk_*.jpg` (plus `tag_*` and
   `subject_*`, which helps register them).
3. **Train the splat with Brush** (open source, Windows build, reads COLMAP datasets directly,
   NVIDIA GPU). Export `.ply`, convert to `.spz` for the viewer.
4. **Proxy mesh for light math:** keep the existing OpenMVS mesh route, or a coarse TSDF mesh from the
   same poses. The **splat is the picture; the mesh does the light math.** A splat has the room's
   existing light baked in and can't be physically relit, so never compute fixture lux from it.
5. **Real scale:** find the 1 m tape measure (manual: click both ends once) and scale the scene so
   inverse-square math is correct.
6. **Place the tagged sources:** each tag photo is registered by COLMAP, so its camera ray through the
   image center hits the mesh at the source. Create a window/lamp/ceiling env source there with
   `luminanceCdM2` and `kelvin` from `scout.json`. Show it for Cam to confirm (drag to fix), as the
   room-reading plan already intends.
7. **Subject spot:** same ray cast from `subject_*.jpg` gives the actor's mark and the measured ambient
   at the subject.
8. **View:** render the splat with Spark (World Labs' MIT-licensed three.js splat renderer; splats and
   meshes share one scene), lights/stands/subject on top. Toggle "splat on/off" to see the mesh-based
   light simulation alone.

## 3. What comes back to the phone (`camzone-layout`, version 1)

The PC writes `layout.json` (top-down, meters, origin = subject mark, +y = toward camera side, z up):

```json
{
  "format": "camzone-layout", "version": 1,
  "scout": "<Location>_<date>",
  "scaleConfidence": "tape",            // tape | estimated
  "room": { "outline": [[x, y], …], "ceilingHeight": 2.6 },
  "sources": [ { "type": "window", "center": [x, y, z], "width": 1.2, "height": 1.5,
                 "facing": [nx, ny], "kelvin": 5900, "stopsVsSubject": 5.3 } ],
  "subject": { "position": [0, 0, 1.6] },
  "suggestedSetups": [ { "name": "Window key, 4:1",
      "lights": [ { "fixture": "Aputure LS 600d", "position": [x, y, z], "aimAt": [0, 0, 1.6],
                    "modifier": "Light Dome III", "dim": 0.6, "kelvin": 5600, "gel": null } ],
      "notes": "…" } ],
  "previewPng": "layout_top.png",       // optional top-down render
  "splatUrl": "…"                       // optional, for viewing on the PC or web
}
```

The phone app will draw `room.outline`, sources and subject as a top-down map, and list
`suggestedSetups` with each light's position measured from the subject mark (e.g. "600d: 2.1 m camera-left,
45°, 2.3 m high, Light Dome III, 60%").

## 4. Automatic round trip over the internet (Production Hub)

Cam wants: process on the home PC over the internet, and get the room layout back on the phone
automatically. Scout data goes through the Production Hub (his choice), not a direct phone-to-PC link.

Hub endpoints to add (Cloudflare Worker + R2; zips can be 150–300 MB, so use presigned uploads):

| Method | Path | Who | Purpose |
| --- | --- | --- | --- |
| POST | `/api/scouts` | phone | Create a scout record, returns `{id, uploadUrl}` (presigned R2 PUT) |
| PUT | `uploadUrl` | phone | Upload the zip |
| POST | `/api/scouts/{id}/uploaded` | phone | Mark ready, status `queued` |
| GET | `/api/scouts?status=queued` | PC worker | Poll every 30 s for new scouts |
| GET | `/api/scouts/{id}/download` | PC worker | Presigned GET for the zip |
| POST | `/api/scouts/{id}/status` | PC worker | `processing` with a progress %, or `failed` with a message |
| POST | `/api/scouts/{id}/layout` | PC worker | Upload `layout.json` (+ `layout_top.png`), status `done` |
| GET | `/api/scouts/{id}` | phone | Status + layout when done (phone polls every 30 s, or a push later) |

- Auth: a per-device token created in the hub (admin only), pasted into the phone app's settings and
  the PC worker's config once. Never put the token in chat or in the repo.
- PC worker: a small background program on the home PC (starts with Windows) that polls the hub, runs
  section 2 unattended, and posts the layout. It needs the PC on and awake.
- Phone side (Cam Zone): "Send to PC" gets a second option, "Upload to hub", once the endpoints exist;
  then a "Layout" card that fills in when the PC is done. Until then the zip goes by Drive or Nearby Share.

## 5. Status

- Phone capture, tags, subject mark, offline room read and zip export: **built** in Cam Zone (repo
  `cattrabbitt/camzone`), untested on a real phone as of 2026-11-19.
- Everything in sections 2 to 4: **to build** in the Lighting Planner and Production Hub projects.
