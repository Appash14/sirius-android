"""Vérification indépendante des artefacts téléchargés, sans confiance dans un run vert."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

from PIL import Image


def verify(directory):
    result = json.loads((directory / 'e2e-logcat/resultat.json').read_text())
    assert result['phase'] == 2 and result['success']
    scenarios = {scenario['name']: scenario for scenario in result['scenarios']}
    expected_names = {json.loads(path.read_text())['name'] for path in (Path(__file__).parent / 'scenarios').glob('*.json')}
    assert set(scenarios) == expected_names
    assert {'conversation_simple', 'texte_long', 'coupure_reconnexion', 'dix_echanges',
            'transcript_direct', 'surimpression', 'pilotage'} <= set(scenarios)
    for name in ('dix_echanges', 'surimpression'):
        assertion = next(a for a in scenarios[name]['assertions'] if 'visited_bubbles' in a)
        assert assertion['expected_bubbles'] == assertion['visited_bubbles'] == 20
    reconnect = next(a for a in scenarios['coupure_reconnexion']['assertions'] if 'automatic_reconnect' in a)
    assert reconnect['automatic_reconnect'] and reconnect['history_after_reconnect'] == 2
    partials = [a for a in scenarios['transcript_direct']['assertions'] if 'partial_before_final' in a]
    assert len(partials) == 2 and all(a['one_bubble'] for a in partials)
    pilot = scenarios['pilotage']['actions']
    assert pilot['ouvrir-reglages']['ok']
    assert not pilot['banque-interdite']['ok']
    assert pilot['banque-interdite']['data']['raison'] == 'application_bloquee'
    for scenario in scenarios.values():
        if scenario['name'] != 'pilotage':
            assert scenario['audio_ack'] > 0
            assert any(c.get('rms', 0) > 450 for c in scenario['chunks'])
    apk_sources = json.loads((directory / 'e2e-logcat/apk-source.json').read_text())
    assert len(apk_sources) == 1
    signature = (directory / 'e2e-logcat/apk-signature.txt').read_text()
    assert '6c685472cdbb4e9592cc1c36b37737bfdc945079cdb4a43676b9256cbb5a16c6' in signature
    apks = list((directory / 'e2e-apk-audio').rglob('sirius-debug.apk'))
    assert len(apks) == 1
    apk = apks[0]
    checksum = apk.with_name('sirius-debug.apk.sha256').read_text().split()[0]
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == checksum
    videos = list((directory / 'e2e-videos').glob('*.mp4'))
    assert {video.stem for video in videos} >= expected_names | {'configuration'}
    durations = {}
    for video in videos:
        info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
            '-show_streams', '-of', 'json', str(video)]))['streams'][0]
        assert (info['width'], info['height']) == (1080, 2340)
        duration = float(info['duration'])
        assert duration > 0
        decoded = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(video), '-f', 'null', '-'], capture_output=True, timeout=180)
        assert decoded.returncode == 0 and not decoded.stderr, decoded.stderr.decode()
        durations[video.name] = duration
    captures = list((directory / 'e2e-captures').glob('*.png'))
    assert captures
    for capture in captures:
        with Image.open(capture) as picture:
            assert picture.size == (1080, 2340)
            picture.verify()
        assert capture.with_suffix('.xml').is_file() or capture.name == 'surimpression-voile.png'
    report = {'success': True, 'apk_sha256': checksum, 'apk_commit': apk_sources[0]['headSha'],
              'apk_run': apk_sources[0]['databaseId'], 'videos': durations, 'captures': len(captures),
              'scenarios': len(scenarios), 'signature': '6c685472cdbb4e9592cc1c36b37737bfdc945079cdb4a43676b9256cbb5a16c6'}
    (directory / 'verification-locale.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    verify(parser.parse_args().directory)
