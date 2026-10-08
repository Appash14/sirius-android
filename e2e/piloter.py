"""Configure l'application par UI et capture chaque étape des scénarios."""

import argparse
import json
import os
import re
import signal
import subprocess
import time
import traceback
import hashlib
import xml.etree.ElementTree as ET
import urllib.request
from pathlib import Path

import uiautomator2 as u2

PACKAGE = "fr.tom.sirius"
ADMIN = os.environ.get("E2E_ADMIN_URL", "http://127.0.0.1:4861")


def adb(*args, **kwargs):
    return subprocess.run(["adb", *args], check=True, timeout=45, **kwargs)


def control(path, data=None):
    payload = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(ADMIN + path, data=payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


def wait_for(predicate, description, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(.5)
    raise RuntimeError("Délai dépassé : " + description)


class Capture:
    def __init__(self, output, device):
        self.output = output
        self.device = device
        self.recording = None
        self.name = None
        self.device_pid = None
        self.systemui_recoveries = 0
        self.parts = []
        self.recorded_at = 0.0
        for directory in ("videos", "captures", "logcat"):
            (output / directory).mkdir(parents=True, exist_ok=True)

    def clear_system_dialog(self):
        if self.device(textContains="System UI isn't responding").exists:
            self.systemui_recoveries += 1
            self.shot(f"systemui-anr-{self.systemui_recoveries:02d}")
            if self.systemui_recoveries > 3:
                raise RuntimeError("System UI reste instable après trois relances")
            with (self.output / "logcat" / "systemui-recovery.txt").open("a") as stream:
                stream.write("Boîte ANR System UI fermée.\n")
            self.device(text="Close app").click(timeout=5)
            time.sleep(2)

    def rotate(self):
        if self.recording is not None and time.monotonic() - self.recorded_at > 120:
            self.stop_part()
            self.start_part()

    def shot(self, label, snapshot=None):
        self.rotate()
        print("Capture : " + label, flush=True)
        with (self.output / "captures" / (label + ".png")).open("wb") as stream:
            adb("exec-out", "screencap", "-p", stdout=stream)
        (self.output / "captures" / (label + ".xml")).write_text(snapshot if snapshot is not None else self.device.dump_hierarchy(), encoding="utf-8")

    def start(self, name):
        self.name = name
        self.parts = []
        self.start_part()

    def start_part(self):
        self.recorded_at = time.monotonic()
        self.device_pid = None
        self.device_file = f"/sdcard/e2e-{self.name}-{len(self.parts)}.mp4"
        # exec remplace le shell, le PID reste celui du screenrecord à arrêter.
        self.recording = subprocess.Popen([
            "adb", "shell", f"echo $$; exec screenrecord --size 540x1170 --time-limit 170 --bit-rate 1500000 {self.device_file}",
        ], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.device_pid = self.recording.stdout.readline().strip()
        if not self.device_pid.isdigit():
            raise RuntimeError("screenrecord n'a pas démarré : " + self.device_pid)
        time.sleep(1)
        if self.recording.poll() is not None:
            raise RuntimeError("screenrecord s'est arrêté : " + self.recording.stdout.read())

    def stop_part(self):
        if self.recording is None:
            return
        recording, self.recording = self.recording, None
        if self.device_pid and recording.poll() is None:
            adb("shell", "kill", "-2", self.device_pid)
        recording.wait(timeout=15)
        target = self.output / "videos" / f"{self.name}-part-{len(self.parts)}.mp4"
        adb("pull", self.device_file, str(target))
        self.parts.append(target)
        if target.stat().st_size < 1024:
            raise RuntimeError("Vidéo vide : " + str(target))
    def stop(self):
        if self.recording is None and not self.parts:
            return
        self.stop_part()
        target = self.output / "videos" / (self.name + ".mp4")
        manifest = self.output / "logcat" / (self.name + "-parts.txt")
        manifest.write_text("".join(f"file '{part}'\n" for part in self.parts))
        subprocess.run(['ffmpeg', '-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', str(manifest),
                        '-c', 'copy', str(target)], check=True, timeout=60)
        self.parts = []
        subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,duration", "-of", "json", str(target)],
            check=True, timeout=15, stdout=(self.output / "logcat" / (self.name + "-video.json")).open("w"))


def configure(device, capture):
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    wait_for(lambda: device.app_current().get("package") == PACKAGE, "ouverture de Sirius")

    def settings_visible():
        # Le premier boot google_apis peut laisser une ANR de System UI.
        # Conserver la preuve et relancer uniquement ce composant système.
        capture.clear_system_dialog()
        return device(text="Réglages").exists or device(description="Réglages").exists

    wait_for(settings_visible, "interface Sirius disponible", 60)
    capture.shot("00-ouverture")
    # Si l'installation locale contient déjà une configuration, ouvrir Réglages.
    if not device(text="Réglages").exists:
        device(description="Réglages").click(timeout=10)
    wait_for(lambda: device(text="Réglages").exists, "écran Réglages")
    # Les champs Connexion sont en bas de la colonne Compose défilante.
    for _ in range(12):
        capture.clear_system_dialog()
        if device(className="android.widget.EditText").count >= 3:
            break
        device.swipe(.5, .78, .5, .28, duration=.3)
        time.sleep(.3)
    fields = device(className="android.widget.EditText")
    if fields.count != 3:
        capture.shot("erreur-reglages")
        raise RuntimeError(f"Trois champs de connexion attendus, obtenus : {fields.count}")
    for index, value in enumerate(("https://10.0.2.2:4860/voix/", "e2e", "e2e")):
        fields[index].set_text(value)
        time.sleep(.3)
    capture.shot("01-reglages")
    for _ in range(8):
        capture.clear_system_dialog()
        if device(text="Enregistrer la connexion").exists:
            break
        device.swipe(.5, .78, .5, .28, duration=.3)
        time.sleep(.3)
    capture.shot("01-bouton-enregistrer")
    device(text="Enregistrer la connexion").click(timeout=10)
    wait_for(lambda: not device(className="android.widget.EditText").exists, "enregistrement des réglages")
    capture.shot("02-configuration-enregistree")
    # Seul l'identifiant public de routage est lu, pas le blob chiffré des accès.
    result = adb("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/sirius.xml", capture_output=True)
    import xml.etree.ElementTree as ET
    client = ET.fromstring(result.stdout).find("string[@name='client']").text
    if not client:
        raise RuntimeError("Identifiant client absent")
    return client


def xml_nodes(device, snapshot=None):
    return ET.fromstring(snapshot if snapshot is not None else device.dump_hierarchy()).findall('.//node')


def tagged(nodes, tag):
    return [node for node in nodes if node.get('resource-id', '').split('/')[-1] == tag]


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))


def bubble_rows(nodes):
    rows = []
    for tag in ('tom-bubble', 'sirius-bubble'):
        for node in tagged(nodes, tag):
            text = node.get('text') or '\n'.join(n.get('text') for n in node.iter('node') if n.get('text'))
            if text:
                rows.append((text, bounds(node)))
    return rows


def assert_text(device, text, count=1, snapshot=None):
    rows = bubble_rows(xml_nodes(device, snapshot))
    matches = [row for row in rows if row[0] == text]
    assert len(matches) == count, f'{count} bulle(s) attendue(s) pour {text[:55]!r}, obtenues : {len(matches)}'
    return matches


def thread_bounds(device, overlay=False, full=True, snapshot=None):
    nodes = xml_nodes(device, snapshot)
    threads = tagged(nodes, 'hud-thread')
    assert len(threads) == 1, 'Fil absent ou dupliqué'
    rect = bounds(threads[0])
    pill = bounds(tagged(nodes, 'hud-pill')[0])
    density = 420 / 160
    # Le fil principal débute sous le logo ; l'assistant sous la barre d'état.
    if full:
        assert rect[1] < (65 if overlay else 175) * density, f'Trou au-dessus du fil : {rect}'
        assert rect[3] - rect[1] > (2340 * (.72 if overlay else .62)), f'Fil trop court : {rect}'
    else:
        assert rect[3] > rect[1], f'Fil compact vide : {rect}'
    assert rect[3] <= pill[1], 'Les bulles chevauchent la pilule'
    return rect


def swipe_thread(device, direction, rect):
    left, top, right, bottom = rect
    x = (left + right) // 2
    high = top + (bottom-top) * .12
    low = top + (bottom-top) * .88
    device.swipe(x, high if direction == 'older' else low,
                 x, low if direction == 'older' else high, duration=.2)
    time.sleep(.25)


def explore_history(device, capture, name, expected, overlay=False):
    snapshot = device.dump_hierarchy()
    rect = thread_bounds(device, overlay, full=not overlay or len(expected) >= 20, snapshot=snapshot)
    rows = bubble_rows(xml_nodes(device, snapshot))
    assert rows, 'Fil vide'
    overflowing = len(rows) < len(expected) or len(expected) >= 20
    if len(expected) >= 20:
        assert len(rows) >= 6, f'Seulement {len(rows)} bulles visibles après dix échanges'
        assert min(row[1][1] for row in rows) <= rect[1] + 40, 'Grand vide entre le haut du fil et la première bulle'
    seen = set()
    last = None
    oldest = False
    for index in range(35):
        snapshot = device.dump_hierarchy()
        rows = bubble_rows(xml_nodes(device, snapshot))
        texts = [row[0] for row in rows]
        assert len(texts) == len(set(texts)), 'Bulles dupliquées dans le viewport'
        assert all(text in expected for text in texts), f'Bulle inattendue : {texts}'
        seen.update(texts)
        capture.shot(f'{name}-historique-{index:02d}', snapshot)
        # A giant bubble keeps the same clipped accessibility bounds while its lines scroll.
        # Compare the actual middle of the viewport, excluding the animated pill and border.
        from PIL import Image
        with Image.open(capture.output / 'captures' / f'{name}-historique-{index:02d}.png') as frame:
            viewport = frame.crop((rect[0] + 180, rect[1] + 60, rect[2] - 180, rect[3] - 100))
            signature = hashlib.sha256(viewport.tobytes()).hexdigest()
        if signature == last:
            oldest = True
            break
        last = signature
        swipe_thread(device, 'older', rect)
    assert oldest, 'Le défilement n’a pas atteint le début du fil en 35 gestes'
    assert seen == set(expected), f'Historique incomplet : {len(seen)}/{len(expected)}, manquants : {[t[:45] for t in expected if t not in seen]}'
    first = assert_text(device, expected[0], snapshot=snapshot)[0][1]
    assert first[1] >= rect[1], f'Première bulle coupée en haut : {first}'
    if overflowing:
        assert first[1] < rect[1] + 65, f'Première bulle pas accessible en haut : {first}'
    # Chaque très longue bulle est parcourue jusqu'à ses deux extrémités.
    # Compose contrôle également chaque ligne réellement composée et sa dernière lettre.
    last = None
    newest = False
    for index in range(35):
        snapshot = device.dump_hierarchy()
        capture.shot(f'{name}-retour-{index:02d}', snapshot)
        with Image.open(capture.output / 'captures' / f'{name}-retour-{index:02d}.png') as frame:
            signature = hashlib.sha256(frame.crop((rect[0] + 180, rect[1] + 60, rect[2] - 180, rect[3] - 100)).tobytes()).hexdigest()
        if signature == last:
            newest = True
            break
        last = signature
        swipe_thread(device, 'newer', rect)
    assert newest, 'Le défilement n’a pas atteint la fin du fil en 35 gestes'
    assert_text(device, expected[-1], snapshot=snapshot)
    capture.shot(name + '-historique-verifie', snapshot)
    return {'expected_bubbles': len(expected), 'visited_bubbles': len(seen), 'thread_bounds': rect}


def set_assistant(hold, log):
    def holders():
        output = adb('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.ASSISTANT', capture_output=True).stdout.decode()
        return set(re.split(r'[;\s]+', output.strip()))
    if (PACKAGE in holders()) == hold:
        return
    action = 'add-role-holder' if hold else 'remove-role-holder'
    result = subprocess.run(['adb', 'shell', 'cmd', 'role', action, 'android.app.role.ASSISTANT', PACKAGE],
                            capture_output=True, text=True, timeout=45)
    with log.open('a') as stream:
        stream.write(f'{action}: {result.returncode} {result.stdout} {result.stderr}\n')
    # googlequicksearchbox is disabled on the isolated AVD. Removing the role succeeds,
    # then Android may report a failure assigning its fallback; the actual holders decide.
    wait_for(lambda: (PACKAGE in holders()) == hold, 'attribution réelle du rôle assistant', 10)


def launch_conversation(device, overlay=False):
    if overlay:
        adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.VoiceEntryActivity', '-a', PACKAGE + '.PARLER')
        wait_for(lambda: device(resourceId='hud-pill').exists, 'fenêtre assistant')
    else:
        adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        device(description='Parler à Sirius').click(timeout=20)
        wait_for(lambda: device(resourceId='hud-pill').exists, 'conversation principale')


def enable_pilot(device):
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
    device(description='Réglages').click(timeout=20)
    for _ in range(10):
        if device(text='Ouvrir le pilotage').exists:
            break
        device.swipe(.5, .8, .5, .3, duration=.2)
    device(text='Ouvrir le pilotage').click(timeout=10)
    wait_for(lambda: device(text='Sirius peut piloter le téléphone').exists, 'page pilotage')
    toggle = device(checkable=True)
    assert toggle.count == 1, 'Interrupteur pilotage absent ou ambigu'
    if not toggle.info['checked']:
        toggle.click(timeout=10)
    wait_for(lambda: device(checkable=True, checked=True).exists, 'pilotage activé')
    wait_for(lambda: device(text='Autorisations, arrêt d\'urgence et journal des actions.').exists or
             device(text='Sirius peut piloter le téléphone').exists, 'pilotage')
    device.press('back')
    adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', PACKAGE + '/.SiriusAccessibilityService')
    adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
    time.sleep(2)


def check_veil(capture, baseline):
    target = capture.output / 'captures' / 'surimpression-voile.png'
    with target.open('wb') as stream:
        adb('exec-out', 'screencap', '-p', stdout=stream)
    from PIL import Image
    plain = Image.open(baseline).convert('RGB')
    veil = Image.open(target).convert('RGB')
    # Même application Settings, même défilement, loin du texte et de la pilule.
    ratios = []
    for fraction in (.18, .45, .995):
        y = int(veil.height * fraction)
        samples = [(x, y+dy) for x in range(1, 9) for dy in (-2, -1, 0, 1, 2)]
        original = sum(sum(plain.getpixel(p)) for p in samples)
        changed = sum(sum(veil.getpixel(p)) for p in samples)
        assert original > 2000, 'Fond trop sombre pour mesurer le voile'
        ratios.append(changed / original)
    assert .68 < ratios[0] < .81 and .64 < ratios[1] < .79, f'Voile absent : {ratios}'
    assert .57 < ratios[2] < .72, f'Bande opaque en bas : {ratios}'
    assert abs(ratios[2] - ratios[1]) < .15, f'Discontinuité du voile : {ratios}'
    return ratios


def run_scenario(path, args, device, capture, client):
    output = capture.output
    scenario = json.loads(path.read_text())
    name = scenario['name']
    assert re.fullmatch(r'[a-z0-9_-]+', name)
    adb('shell', 'am', 'force-stop', PACKAGE)
    before = control('/status')['clients'].get(client, {}).get('hello_received', 0)
    control('/scenario', {'file': path.name, 'client': client})
    overlay = scenario.get('overlay', False)
    set_assistant(False, output / 'logcat/assistant-role.txt')
    if scenario.get('pilot'):
        enable_pilot(device)
    baseline = None
    if overlay:
        set_assistant(True, output / 'logcat/assistant-role.txt')
        # App claire standard, indépendante de Sirius. Aucun faux overlay de test.
        adb('shell', 'cmd', 'uimode', 'night', 'no')
        adb('shell', 'am', 'start', '-W', '-a', 'android.settings.SETTINGS')
        time.sleep(1)
        capture.shot('surimpression-application-dessous')
        baseline = output / 'captures/surimpression-application-dessous.png'
    capture.start(name)
    launch_conversation(device, overlay)
    early_player = None
    if scenario.get('early_speech'):
        early_player = subprocess.Popen(['paplay', '--device=sirius_micro',
            str(Path(args.scenarios).parent / 'audio' / scenario['steps'][0]['wav'])])

    def state():
        capture.rotate()
        return control('/status')['clients'].get(client, {})

    wait_for(lambda: state().get('hello_received', 0) > before and state().get('ready'), 'hello traité')
    def microphone_stream():
        outputs = json.loads(subprocess.check_output(['pactl', '-f', 'json', 'list', 'source-outputs']))
        sources = json.loads(subprocess.check_output(['pactl', '-f', 'json', 'list', 'sources']))
        monitor = next(x['index'] for x in sources if x['name'] == 'sirius_micro.monitor')
        return [x for x in outputs if x['source'] == monitor]
    streams = wait_for(microphone_stream, 'entrée QEMU sur le vrai microphone Pulse', 10)
    (output / 'logcat' / (name + '-microphone-stream.json')).write_text(json.dumps(streams, indent=2))
    if early_player is None:
        capture.shot(name + '-00-connecte')
    assertions = []
    if overlay:
        # Le voile est réellement dessiné dans VoiceInteractionSession.
        sessions = adb('shell', 'dumpsys', 'voiceinteraction', capture_output=True).stdout.decode()
        assert 'fr.tom.sirius' in sessions and 'mSession' in sessions
        (output / 'logcat/assistant-session.txt').write_text(sessions)
        assertions.append({'veil_ratios': check_veil(capture, baseline)})
    expected = []
    for index, step in enumerate(scenario['steps'], start=1):
        assert state().get('ready'), 'La conversation a expiré ; aucune réouverture silencieuse autorisée'
        previous = state()['hello_received']
        ack_before = state()['audio_ack']
        if not (scenario.get('early_speech') and index == 1):
            control('/step', {})
        if step['type'] == 'disconnect':
            time.sleep(.5)
            if step.get('restart'):
                wait_for(lambda: device(text='Je retrouve ton serveur').exists, 'message de reconnexion visible', 3)
                assertions.append({'reconnecting_notice_visible': True})
            for text in expected:
                assert_text(device, text)
            capture.shot(f'{name}-{index:02d}-coupure')
            wait_for(lambda: state().get('hello_received', 0) > previous and state().get('ready'), 'reconnexion automatique', 40)
            for text in expected:
                assert_text(device, text)
            assert device.app_current()['package'] == PACKAGE, 'Accueil Android apparu durant la coupure'
            capture.shot(f'{name}-{index:02d}-reconnecte')
            assertions.append({'history_after_reconnect': len(expected), 'automatic_reconnect': True})
        elif step['type'] == 'action':
            media = None
            before_media_chunks = len(state()['chunks'])
            if step.get('media_during_action'):
                media = subprocess.Popen(['paplay', '--device=sirius_micro', str(Path(args.scenarios).parent / 'audio' / 'partiels.wav')])
            result = wait_for(lambda: state().get('actions', {}).get(step['id']), 'résultat action', 25)
            if media:
                media.terminate(); media.wait(timeout=5)
                time.sleep(1)
                assert len(state()['chunks']) == before_media_chunks, 'Audio des stories transcrit pendant une action'
                assertions.append({'audio_during_action_not_transcribed': True})
            assert result['ok'] is step['ok'], result
            if step['ok']:
                wait_for(lambda: device.app_current()['package'] == step['package'], 'action exécutée dans Android')
            else:
                assert result['data']['raison'] == step['reason'], result
                assert device.app_current()['package'] == 'com.android.settings', 'Action interdite a changé l’application'
            assertions.append(result)
            capture.shot(f'{name}-{index:02d}-action')
        else:
            chunks_before = 0 if early_player else len(state()['chunks'])
            player = early_player or subprocess.Popen(['paplay', '--device=sirius_micro', str(Path(args.scenarios).parent / 'audio' / step.get('wav', 'court.wav'))])
            try:
                for partial in step.get('partials', []):
                    def partial_frame():
                        frame = device.dump_hierarchy()
                        return frame if any(row[0] == partial for row in bubble_rows(xml_nodes(device, frame))) else None
                    snapshot = wait_for(partial_frame, 'partiel visible avant final', 20)
                    assert player.poll() is None, 'Partiel arrivé après la fin de la voix'
                    assert not any(m['text'] == step['tom'] for m in state()['history']), 'Final arrivé avant la vérification du partiel'
                    partial_rect = assert_text(device, partial, snapshot=snapshot)[0][1]
                    assert partial_rect[3] <= thread_bounds(device, overlay, full=not overlay, snapshot=snapshot)[3] + 2, 'Partiel hors du bas du fil'
                    capture.shot(f'{name}-{index:02d}-partiel-{len(assertions)}', snapshot)
                    assertions.append({'partial_before_final': partial, 'one_bubble': True})
                player.wait(timeout=45)
                assert player.returncode == 0
            finally:
                if player.poll() is None:
                    player.terminate(); player.wait(timeout=5)
            if step.get('delay_s'):
                started = time.monotonic()
                while time.monotonic() - started < step['delay_s'] - 8:
                    assert state().get('ready'), 'Vocal fermé pendant la réflexion avec relance puis ignored'
                    assert device(resourceId='hud-pill').exists, 'Conversation disparue pendant la réflexion'
                    time.sleep(1)
                capture.shot(name + '-attente-70s')
                assertions.append({'open_during_70s_with_relance_and_ignored': True})
            def response_frame():
                frame = device.dump_hierarchy()
                return frame if any(row[0] == step['sirius'] for row in bubble_rows(xml_nodes(device, frame))) else None
            snapshot = wait_for(response_frame, 'réponse rendue', step.get('delay_s', 0) + 35)
            latest_bounds = assert_text(device, step['sirius'], snapshot=snapshot)[0][1]
            rect = thread_bounds(device, overlay, full=not overlay or len(expected) + 2 >= 20, snapshot=snapshot)
            assert latest_bounds[3] <= rect[3] + 2 and latest_bounds[3] > rect[1], 'Dernière bulle ne suit pas le bas'
            assertions.append({'latest_visible_without_scroll': True})
            expected.extend([step['tom'], step['sirius']])
            chunks = state()['chunks'][chunks_before:]
            assert chunks and any(c.get('rms', 0) > 450 and c['bytes'] > 1000 for c in chunks), 'WAV non reçu du vrai microphone'
            assert len({c['enonce'] for c in chunks}) == 1, 'Une voix fragmentée en plusieurs énoncés'
            assert len([c for c in chunks if c['final']]) == 1, 'Final micro absent ou dupliqué'
            if len(step['sirius']) < 1000 and len(expected) < 10:
                assert_text(device, step['tom'], snapshot=snapshot)
            capture.shot(f'{name}-{index:02d}-echange', snapshot)
            if index == len(scenario['steps']) and not step.get('barge_in'):
                assertions.append(explore_history(device, capture, name, expected, overlay))
            if step.get('barge_in'):
                # Allow MediaPlayer to start, then inject actual host microphone speech.
                time.sleep(1)
                before_interrupt = len(state()['interrupts'])
                audio_root = Path(args.scenarios).parent / 'audio'
                subprocess.run(['paplay', '--device=sirius_micro', str(audio_root / 'bref.wav')], check=True)
                time.sleep(1)
                assert len(state()['interrupts']) == before_interrupt, '0,2 s de parole a coupé Sirius'
                long_voice = subprocess.Popen(['paplay', '--device=sirius_micro', str(audio_root / 'interruption.wav')])
                begun = time.time()
                wait_for(lambda: len(state()['interrupts']) > before_interrupt, 'coupure humaine', 3)
                latency = state()['interrupts'][-1] - begun
                long_voice.wait(timeout=5)
                assert latency < .8, f'Interruption trop lente : {latency:.3f}s'
                assertions.append({'brief_200ms_did_not_interrupt': True, 'interruption_latency_s': latency})
                capture.shot(name + '-interrompu')
            else:
                wait_for(lambda: state()['audio_ack'] > ack_before, 'audio lu et accusé par MediaPlayer', step.get('audio_ack_timeout_s', 80))
            assertions.append({'microphone_chunks': len(chunks), 'audio_ack': state()['audio_ack']})
    status = control('/status')
    assert status['cursor'] == len(scenario['steps'])
    result = {'name': name, 'steps': status['cursor'], 'assertions': assertions, **state()}
    capture.stop()
    return result


def run(args):
    output = Path(args.output).resolve()
    device = u2.connect(os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
    device.jsonrpc.setConfigurator({'waitForIdleTimeout': 0, 'waitForSelectorTimeout': 0, 'uiAutomationFlags': 1})
    capture = Capture(output, device)
    (output / 'logcat' / 'uiautomator-config.json').write_text(json.dumps(device.jsonrpc.getConfigurator(), indent=2))
    log_stream = (output / 'logcat' / 'android.log').open('wb')
    logcat = subprocess.Popen(['adb', 'logcat', '-v', 'threadtime'], stdout=log_stream, stderr=subprocess.STDOUT)
    summary = {'phase': 2, 'scenarios': [], 'apk_source': os.environ.get('E2E_APK_RUN_ID')}
    try:
        capture.start('configuration')
        client = configure(device, capture)
        capture.stop()
        summary['client'] = client
        failures = []
        for path in sorted(Path(args.scenarios).glob('*.json')):
            try:
                result = run_scenario(path, args, device, capture, client)
                summary['scenarios'].append(result)
            except Exception as error:
                name = json.loads(path.read_text())['name']
                traceback.print_exc()
                failures.append(name)
                summary['scenarios'].append({'name': name, 'success': False, 'error': str(error)})
                capture.shot(name + '-erreur')
                for service in ('audio', 'notification'):
                    report = adb('shell', 'dumpsys', service, capture_output=True).stdout
                    (output / 'logcat' / (name + '-erreur-' + service + '.txt')).write_bytes(report)
                capture.stop()
        if failures:
            raise AssertionError('Scénarios échoués : ' + ', '.join(failures))
        assert len(summary['scenarios']) == len(list(Path(args.scenarios).glob('*.json')))
        summary['success'] = True
    except BaseException as error:
        summary['success'] = False
        summary['error'] = str(error)
        try:
            capture.shot('erreur-finale')
            for service in ('audio', 'notification'):
                report = adb('shell', 'dumpsys', service, capture_output=True).stdout
                (output / 'logcat' / ('erreur-' + service + '.txt')).write_bytes(report)
        except Exception as capture_error:
            print('Capture finale impossible : ' + str(capture_error), flush=True)
        raise
    finally:
        try:
            capture.stop()
        finally:
            summary['systemui_recoveries'] = capture.systemui_recoveries
            logcat.send_signal(signal.SIGTERM)
            logcat.wait(timeout=10)
            log_stream.close()
            (output / 'logcat/resultat.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
            try:
                (output / 'logcat/serveur-etat.json').write_text(json.dumps(control('/status'), indent=2) + '\n')
            except Exception:
                pass


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--scenarios', required=True)
    run(parser.parse_args())
