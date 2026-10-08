#!/usr/bin/env bash
set -euo pipefail
# La sortie de l'émulateur ne doit pas revenir dans son micro.
pulseaudio --start --exit-idle-time=-1
pactl load-module module-null-sink sink_name=sirius_micro rate=16000 channels=1
pactl load-module module-null-sink sink_name=sirius_sortie
pactl set-default-source sirius_micro.monitor
pactl set-default-sink sirius_sortie
# Both libpulse and QEMU's legacy Pulse backend receive the actual Unix socket.
pulse_server=$(LC_ALL=C pactl info | sed -n 's/^Server String: //p')
test -S "$pulse_server"
if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "PULSE_SERVER=unix:$pulse_server" >> "$GITHUB_ENV"
  echo "QEMU_PA_SERVER=unix:$pulse_server" >> "$GITHUB_ENV"
fi
pactl info
pactl list short sources
espeak-ng -v fr -s 180 -a 180 -w e2e/audio/court-brut.wav "Bonjour Sirius, je te parle et tu peux me répondre."
espeak-ng -v fr -s 155 -a 180 -w e2e/audio/partiels-brut.wav "Bonjour Sirius je regarde les mots apparaître pendant que je parle et je continue tranquillement pour vérifier la transcription en direct dans ma bulle sans attendre la fin de cette longue phrase."
ffmpeg -y -v error -i e2e/audio/court-brut.wav -ar 16000 -ac 1 -af 'volume=2,apad=pad_dur=1.2' e2e/audio/court.wav
ffmpeg -y -v error -i e2e/audio/partiels-brut.wav -ar 16000 -ac 1 -af 'volume=2,apad=pad_dur=1.2' e2e/audio/partiels.wav
# Réponse vocale déterministe, plutôt que le bip de la phase 1.
espeak-ng -v fr -s 190 -w e2e/audio/reponse.wav "Je reçois ta voix et je réponds par le canal audio."
ffmpeg -y -v error -i e2e/audio/reponse.wav -ac 1 -ar 24000 e2e/audio/reponse.mp3
# Maintient la vraie lecture pendant l'exploration des deux très grandes bulles.
ffmpeg -y -v error -stream_loop -1 -i e2e/audio/reponse.wav -t 65 -ac 1 -ar 24000 e2e/audio/longue.mp3
ffmpeg -y -v error -stream_loop -1 -i e2e/audio/reponse.wav -t 180 -ac 1 -ar 24000 e2e/audio/exploration.mp3

# Same actual voice input cropped to 0.2 s and 1 s for the playback interruption test.
ffmpeg -y -v error -i e2e/audio/court.wav -ss 0.1 -t 0.2 e2e/audio/bref.wav
ffmpeg -y -v error -i e2e/audio/court.wav -ss 0.1 -t 1 e2e/audio/interruption.wav
