# Cam Zone

Exposure and contrast system for the Blackmagic PYXIS 6K: two monitoring LUTs plus an Android app.

## What's here

| Path | What it is |
| --- | --- |
| `luts/CamZone_Map_Gen5.cube` | Full color scale in stops from middle gray. For lighting and the final check. On the PYXIS, and in Resolve on clips decoded as Blackmagic Film Gen 5. |
| `luts/CamZone_Guard_Gen5.cube` | Normal picture with orange, red and dark purple warnings. For rolling. |
| `luts/CamZone_Map_DWG.cube`, `luts/CamZone_Guard_DWG.cube` | The same two LUTs for Resolve timelines in DaVinci Wide Gamut / Intermediate. |
| `luts/Install CamZone LUTs in Resolve.bat` | Copies all four into Resolve's LUT folder, in a `CamZone` subfolder. |
| `luts/CamZone_Legend.png` | The color code with fixes. |
| `app/` | Android app: zone view, spot and contrast meter, PYXIS control over Bluetooth or USB-C. |
| `tools/` | Python scripts that generate the LUTs and previews. |

The `_Gen5` LUTs expect **Blackmagic Film Gen 5** (PYXIS Dynamic range: Film). The `_DWG` LUTs expect **DaVinci Wide Gamut / DaVinci Intermediate**.

### In DaVinci Resolve

1. Run `Install CamZone LUTs in Resolve.bat` (it asks Windows for permission once), then in Resolve go to Project Settings > Color Management > Lookup Tables > Update Lists, or restart Resolve.
2. Best way to use them: Project Settings > Color Management > Lookup Tables > **Color viewer lookup table** > CamZone. That shows the zones on the Color page viewer only and never gets rendered.
3. Pick the version that matches what the LUT sees: `_DWG` on a DaVinci Wide Gamut color-managed timeline, `_Gen5` when BRAW clips are decoded as Blackmagic Design Film Gen 5. If you put one on a node instead, disable it before export.

## Color code (stops from middle gray)

| Color | Range | Meaning |
| --- | --- | --- |
| Red | +6 and up | Clipped, in any color channel |
| Orange | +5⅓ to +6 | About to clip |
| Yellow | +1⅓ to +5⅓ | Bright |
| Green | −½ to +1⅓ | Good: faces belong here |
| Light green | 0 ± ⅙ | Middle gray |
| Blue | −5 to −½ | Dark |
| Dark purple | below −5 | Crushed |

The clip point (+6 at ISO 400 and 3200) is a starting value from published data for this sensor family. Confirm it with the zebra check in the app's Guide tab.

## Getting the app

Every push to `main` builds the APK on GitHub. Open the repo's **Releases → latest** on the Pixel, download `CamZone.apk`, and allow installs from your browser once.

## Changing the LUTs

```
cd tools
python3 preview.py   # writes both .cube files and a preview image
python3 legend.py
```
Edit `CLIP_STOPS` in `tools/build_luts.py` to move the clip warning.
