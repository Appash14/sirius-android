"""Mesure une transition sur les PTS réels, sans fabriquer des images intermédiaires.

events.json : [{"name":"froid", "direction":"entree", "start":1.0,
               "end":2.5, "pill":[100,2100,980,2230]}].
Les bornes entourent une transition et ses deux poses stables, hors parole.
"""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageStat


def measures(samples, direction, moving=True):
    assert len(samples) >= 2, "Transition sans images"
    first, last = samples[0], samples[-1]
    amplitude = last['veil'] - first['veil']
    assert abs(amplitude) > .05, "Aucune amplitude de voile mesurable"
    progress = [(s['veil'] - first['veil']) / amplitude for s in samples]
    tolerance = .01 / abs(amplitude)
    changed = next(i for i, p in enumerate(progress) if p > tolerance)
    stable = next((i for i in range(changed, len(samples)) if all(
        abs(progress[j] - 1) <= tolerance and (samples[j]['top'] is None or last['top'] is None or
        abs(samples[j]['top'] - last['top']) <= 2) for j in range(i, len(samples)))), len(samples) - 1)
    active = samples[max(0, changed - 1):stable + 1]
    increments = [b - a for a, b in zip(progress[max(0, changed - 1):stable], progress[changed:stable + 1])]
    tops = [s['top'] for s in active if s['top'] is not None]
    expected_sign = -1 if direction == 'entree' else 1
    report = {
        'direction': direction,
        'departure_pts': samples[changed]['pts'], 'arrival_pts': samples[stable]['pts'],
        'duration_ms': (samples[stable]['pts'] - samples[changed]['pts']) * 1000,
        'intermediate_frames': len({round(p, 3) for p in progress[changed:stable] if .01 < p < .99}),
        'veil_monotonic': all(delta >= -.01 / abs(amplitude) for delta in increments),
        'pill_monotonic': all((b - a) * expected_sign >= -2 for a, b in zip(tops, tops[1:])),
        'max_jump_fraction': max((abs(delta) for delta in increments), default=0),
        'max_pts_gap_ms': max(((b['pts'] - a['pts']) * 1000 for a, b in zip(active, active[1:])), default=0),
        'first_pill_luminance_ratio': samples[changed]['pill'] / max(.001, first['pill']),
        'first_veil_fraction': progress[changed],
        'final_veil_ratios': last['ratios'], 'samples': samples,
        'motion': moving,
    }
    checks = {
        'veil_monotonic': report['veil_monotonic'], 'pill_monotonic': report['pill_monotonic'],
    }
    ratios = report['final_veil_ratios']
    checks['final_veil'] = (.68 < ratios[0] < .81 and .64 < ratios[1] < .79 and
        .57 < ratios[2] < .72 and abs(ratios[2] - ratios[1]) < .15) if direction == 'entree' else all(
        abs(ratio - 1) <= .02 for ratio in ratios)
    if moving:
        lower, upper, intermediate = (140, 480, 6) if direction == 'entree' else (120, 380, 4)
        checks.update(duration=lower <= report['duration_ms'] <= upper,
            intermediate_frames=report['intermediate_frames'] >= intermediate,
            max_jump=report['max_jump_fraction'] <= .5,
            max_pts_gap=report['max_pts_gap_ms'] <= 100)
        if direction == 'entree':
            checks.update(first_pill=report['first_pill_luminance_ratio'] >= .85,
                first_veil=report['first_veil_fraction'] < .4)
    else:
        checks['one_frame'] = report['intermediate_frames'] <= 1
    report['checks'] = checks
    report['success'] = all(checks.values())
    return report


def mean(image, box):
    return ImageStat.Stat(image.crop(tuple(map(int, box)))).mean[0] / 255


def analyse(video, events, output, sheets):
    metadata = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
        '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(video)]))
    pts = [float(frame['best_effort_timestamp_time']) for frame in metadata['frames']]
    results = []
    sheets.mkdir(parents=True, exist_ok=True)
    for event in events:
        start, end = event['start'], event['end']
        assert 0 <= start < end and end - start <= 5, "Fenêtre limitée à cinq secondes"
        selected = [time for time in pts if start <= time <= end]
        with tempfile.TemporaryDirectory(prefix='sirius-frames-') as temporary:
            subprocess.run(['ffmpeg', '-v', 'error', '-i', str(video), '-vf',
                f"select=between(t\\,{start}\\,{end})", '-fps_mode', 'passthrough',
                str(Path(temporary) / '%06d.png')], check=True)
            paths = sorted(Path(temporary).glob('*.png'))
            assert len(paths) == len(selected), "PTS et images décodées ne correspondent pas"
            assert paths, "Aucune image dans la fenêtre"
            with Image.open(paths[0] if event['direction'] == 'entree' else paths[-1]) as image:
                reference = image.convert('L')
            width, height = reference.size
            assert (width, height) == (1080, 2340), "Vidéo native 1080x2340 exigée"
            bands = [(2, height * f - 2, 9, height * f + 3) for f in (.18, .45, .995)]
            baseline = [mean(reference, box) for box in bands]
            pill = event['pill']
            samples = []
            for time, path in zip(selected, paths):
                with Image.open(path) as image:
                    frame = image.convert('L')
                ratios = [mean(frame, box) / max(.001, original) for box, original in zip(bands, baseline)]
                # Compare each row against the background after removing the continuous veil.
                # Position is sampled only when the pill is sufficiently visible to distinguish it.
                top = None
                for y in range(max(0, pill[1] - 8), min(height, pill[3] + 32)):
                    box = (pill[0] + 20, y, pill[2] - 20, y + 1)
                    if mean(reference, box) * ratios[1] - mean(frame, box) > .12:
                        top = y; break
                samples.append({'pts': time, 'veil': sum(ratios) / 3, 'ratios': ratios,
                    'pill': mean(frame, pill), 'top': top})
            result = measures(samples, event['direction'], event.get('motion', True))
            result['name'] = event['name']
            results.append(result)
            begin = min(range(len(selected)), key=lambda i: abs(selected[i] - result['departure_pts']))
            finish = min(range(len(selected)), key=lambda i: abs(selected[i] - result['arrival_pts']))
            sheet = Image.new('RGB', (4 * 270, 2 * 585))
            for slot in range(8):
                index = round(begin + (finish - begin) * slot / 7)
                with Image.open(paths[index]) as image:
                    sheet.paste(image.convert('RGB').resize((270, 585)), ((slot % 4) * 270, (slot // 4) * 585))
            sheet.save(sheets / f"{event['name']}-{event['direction']}.png")
    report = {'success': all(r['success'] for r in results), 'video': str(video), 'transitions': results}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('video', type=Path)
    parser.add_argument('events', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--sheets', required=True, type=Path)
    args = parser.parse_args()
    report = analyse(args.video, json.loads(args.events.read_text()), args.output, args.sheets)
    print(json.dumps({key: value for key, value in report.items() if key != 'transitions'}))
    raise SystemExit(0 if report['success'] else 1)
