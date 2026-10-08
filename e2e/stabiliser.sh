#!/usr/bin/env bash
# boot_completed précède l'initialisation des services de l'image google_apis.
set -euo pipefail
adb shell wm size 1080x2340
adb shell wm density 420
adb shell settings put system screen_off_timeout 1800000
adb shell wm dismiss-keyguard
echo 'Attente de stabilisation des services Android après le premier boot'
sleep 45
adb shell input keyevent KEYCODE_HOME
