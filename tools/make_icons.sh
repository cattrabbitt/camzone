#!/usr/bin/env bash
# Pulls Cameron Currin's logo from cameroncurrin.com and builds the app icon and in-app logo.
# Runs in GitHub Actions. If the site can't be reached, the build keeps the placeholders.
set -u
RES=app/src/main/res/drawable-nodpi
mkdir -p /tmp/logo
if curl -fsSL https://cameroncurrin.com/assets/img/cc-mark.svg -o /tmp/logo/mark.svg; then
  # Icon foreground: 432 px canvas, logo inside the 66% safe zone so circle masks don't cut it
  rsvg-convert -w 260 -h 260 --keep-aspect-ratio /tmp/logo/mark.svg -o /tmp/logo/mark.png &&
  convert -size 432x432 xc:none /tmp/logo/mark.png -gravity center -composite "$RES/ic_launcher_fg.png" &&
  echo "icon: built from cc-mark.svg"
else
  echo "icon: couldn't download cc-mark.svg, keeping placeholder"
fi
if curl -fsSL https://cameroncurrin.com/assets/img/cc-badge.png -o /tmp/logo/badge.png; then
  convert /tmp/logo/badge.png -resize 512x512 "$RES/cc_logo.png" && echo "logo: built from cc-badge.png"
elif [ -f /tmp/logo/mark.svg ]; then
  rsvg-convert -w 512 -h 512 --keep-aspect-ratio /tmp/logo/mark.svg -o "$RES/cc_logo.png" && echo "logo: built from cc-mark.svg"
fi
