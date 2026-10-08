#!/usr/bin/env bash
set -euo pipefail
# -no-window selects a QEMU binary whose Pulse API is stubbed (NULL/-1).
# The normal Qt binary, hidden on Xvfb, retains actual host audio.
mkdir -p "${E2E_OUTPUT:-e2e/output}/logcat"
nohup Xvfb "${DISPLAY:-:99}" -screen 0 1080x2340x24 > "${E2E_OUTPUT:-e2e/output}/logcat/xvfb.log" 2>&1 &
for attempt in $(seq 1 30); do
  if xdpyinfo -display "${DISPLAY:-:99}" >/dev/null 2>&1; then break; fi
  sleep 0.2
done
xdpyinfo -display "${DISPLAY:-:99}" > "${E2E_OUTPUT:-e2e/output}/logcat/xvfb-display.txt"
pactl info > "${E2E_OUTPUT:-e2e/output}/logcat/pulse-before-emulator.txt"
python3 - <<'PY'
import os
from pathlib import Path
avd_root = Path(os.environ.get('ANDROID_AVD_HOME', str(Path.home() / '.android/avd')))
config = avd_root / 'sirius-e2e.avd/config.ini'
values = dict(line.split('=', 1) for line in config.read_text().splitlines() if '=' in line)
values.update({'hw.lcd.width': '1080', 'hw.lcd.height': '2340', 'hw.lcd.density': '420', 'hw.keyboard': 'yes'})
config.write_text('\n'.join(key + '=' + value for key, value in values.items()) + '\n')
PY
