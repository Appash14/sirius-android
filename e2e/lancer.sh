#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
apk=${1:?Usage : e2e/lancer.sh chemin.apk}
output=${E2E_OUTPUT:-$root/output}
python=${E2E_PYTHON:-python3}
mkdir -p "$output/videos" "$output/captures" "$output/logcat"
server_pid=
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

adb wait-for-device
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
adb emu avd hostmicon | tee "$output/logcat/microphone-hote.txt"
pactl list short sources > "$output/logcat/audio-sources.txt"
pactl list short sinks > "$output/logcat/audio-sorties.txt"
adb root
adb wait-for-device
adb shell id | tee "$output/logcat/emulateur-root.txt" | grep -q 'uid=0'
adb shell wm size 1080x2340
adb shell wm density 420
adb shell settings put system screen_off_timeout 1800000
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put global heads_up_notifications_enabled 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell getprop > "$output/logcat/emulateur-proprietes.txt"
adb shell wm size > "$output/logcat/emulateur-ecran.txt"
adb shell wm density >> "$output/logcat/emulateur-ecran.txt"
# Éviter la charge du premier boot Google sur le runner à deux CPU.
# Ces services ne participent pas au protocole voix, au Keystore ni au média.
for package in com.google.android.gms com.google.android.googlequicksearchbox \
  com.google.android.apps.wellbeing com.google.android.as com.google.android.as.oss \
  com.google.android.tts com.google.android.settings.intelligence \
  com.google.android.apps.messaging com.google.android.dialer; do
  if adb shell pm path "$package" | grep -q '^package:'; then
    adb shell pm disable-user --user 0 "$package" >> "$output/logcat/services-desactives.txt"
  fi
done
bash "$root/stabiliser.sh"

bash "$root/certificat.sh" "$root/certs"
hash=$(openssl x509 -in "$root/certs/ca.pem" -subject_hash_old -noout)
adb push "$root/certs/ca.pem" "/data/local/tmp/$hash.0"
adb push "$root/installer_ca.sh" /data/local/tmp/sirius-e2e-installer-ca.sh
adb shell sh /data/local/tmp/sirius-e2e-installer-ca.sh "/data/local/tmp/$hash.0"

"$python" -m e2e.faux_serveur.serveur --cert "$root/certs/server.pem" \
  --key "$root/certs/server.key" --events "$output/logcat/serveur-evenements.jsonl" \
  > "$output/logcat/serveur.log" 2>&1 &
server_pid=$!
for attempt in $(seq 1 30); do
  if curl --silent --fail http://127.0.0.1:4861/status > /dev/null; then break; fi
  kill -0 "$server_pid"
  sleep 1
done
curl --fail --silent --show-error --cacert "$root/certs/ca.pem" -u e2e:e2e \
  https://127.0.0.1:4860/voix/api/sante > "$output/logcat/serveur-sante.json"
adb install -r -d "$apk"
adb shell pm grant fr.tom.sirius android.permission.RECORD_AUDIO
adb shell pm grant fr.tom.sirius android.permission.POST_NOTIFICATIONS
adb shell appops set fr.tom.sirius SYSTEM_ALERT_WINDOW allow
adb shell dumpsys package fr.tom.sirius > "$output/logcat/application.txt"
adb logcat -c
"$python" "$root/piloter.py" --output "$output" --scenarios "$root/scenarios"
"$python" - "$output" "$root/scenarios" <<'PY'
import json
import subprocess
import sys
from pathlib import Path
output = Path(sys.argv[1])
result = json.loads((output / 'logcat/resultat.json').read_text())
assert result['success']
assert len(result['scenarios']) == len(list(Path(sys.argv[2]).glob('*.json')))
for scenario in result['scenarios']:
    assert scenario['hello_received'] > 0
    video = output / 'videos' / (scenario['name'] + '.mp4')
    assert video.stat().st_size > 1024
    probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_streams', '-of', 'json', str(video)]))
    assert probe['streams'] and float(probe['streams'][0]['duration']) > 0
    assert (probe['streams'][0]['width'], probe['streams'][0]['height']) == (1080, 2340)
assert result['phase'] == 2
assert all(s['assertions'] for s in result['scenarios'])
print('Phase 2 validée : assertions UI, microphone WAV, audio_ack, reconnexion, assistant et pilotage.')
PY
