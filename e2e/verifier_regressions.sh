#!/usr/bin/env bash
set -euo pipefail
# Contrôle négatif : mêmes assertions, sources de l'appli avant correction.
checkpoint=$(mktemp -d)
# AssistantServices uses the new timing callbacks of VoiceEngine. Restore both sides for the old build.
# The four negative assertions below stay exactly the same.
files=(VoiceEngine.kt AudioFrames.kt AssistantServices.kt)
restore() {
  for file in "${files[@]}"; do
    cp "$checkpoint/$file" "app/src/main/java/fr/tom/sirius/$file"
  done
}
trap restore EXIT
for file in "${files[@]}"; do
  cp "app/src/main/java/fr/tom/sirius/$file" "$checkpoint/$file"
  git show "244d7e3:app/src/main/java/fr/tom/sirius/$file" > "app/src/main/java/fr/tom/sirius/$file"
done
if ./gradlew --no-daemon :app:testDebugUnitTest \
    --tests '*HudLifecycleTest.closingKeepsHistoryButRejectedWakeClearsTheThreadSilently' \
    --tests '*AudioFramesTest.helloDuringFirstSpeechEnablesPartialsBeforeSilenceWithoutLosingSamples' \
    --tests '*ConversationContinuityTest.networkOutageDuringIdleListeningCannotExpireTheConversation' \
    --tests '*ConversationContinuityTest.legacyServerFinalWithDistinctIdMustMergeThePartialUsingThePostQuestion' \
    > "$checkpoint/negative.log" 2>&1; then
  cat "$checkpoint/negative.log"
  echo 'Contrôle négatif invalide : les anciens bugs passent les assertions' >&2
  exit 1
fi
mkdir -p app/build/outputs/regressions
# Erreurs de compilation éventuelles, visibles dans le journal du run.
grep -E '^e: |error:|FAILED' "$checkpoint/negative.log" | head -40 || true
cp "$checkpoint/negative.log" app/build/outputs/regressions/
python3 - <<'PY'
import json
from pathlib import Path
from xml.etree import ElementTree as ET
expected = {
    'closingKeepsHistoryButRejectedWakeClearsTheThreadSilently',
    'helloDuringFirstSpeechEnablesPartialsBeforeSilenceWithoutLosingSamples',
    'networkOutageDuringIdleListeningCannotExpireTheConversation',
    'legacyServerFinalWithDistinctIdMustMergeThePartialUsingThePostQuestion',
}
failures = {}
for path in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    for test in ET.parse(path).findall('testcase'):
        failure = test.find('failure')
        if test.attrib['name'] in expected and failure is not None:
            failures[test.attrib['name']] = failure.attrib.get('message', '')
assert set(failures) == expected, failures
assert all('AssertionError' in reason for reason in failures.values()), failures
Path('app/build/outputs/regressions/negative.json').write_text(json.dumps(failures, indent=2)+'\n')
print('Les quatre régressions échouent par assertion sur les sources 244d7e3.')
PY

# Sirius 2.11.2 : prouver la course de fermeture et le faux ok sur le dispatcher 2.11.1.
# Remettre d'abord les sources actuelles du contrôle précédent pour isoler ces deux défauts.
restore
files+=(PilotController.kt)
cp app/src/main/java/fr/tom/sirius/PilotController.kt "$checkpoint/PilotController.kt"
git show '0b6b1c4:app/src/main/java/fr/tom/sirius/PilotController.kt' > app/src/main/java/fr/tom/sirius/PilotController.kt
if ./gradlew --no-daemon :app:testDebugUnitTest \
    --tests '*PilotOpeningTest.delayedAssistantHideMustFinishBeforeLaunchingSoItCannotRestoreHomeOverTheApp' \
    --tests '*PilotOpeningTest.appThatOpensThenReturnsHomeCannotReportSuccess' \
    > "$checkpoint/opening-negative.log" 2>&1; then
  cat "$checkpoint/opening-negative.log"
  echo 'Contrôle négatif invalide : les défauts ouvrir passent les assertions' >&2
  exit 1
fi
cp "$checkpoint/opening-negative.log" app/build/outputs/regressions/
python3 - <<'PY'
import json
from pathlib import Path
from xml.etree import ElementTree as ET
expected = {
    'delayedAssistantHideMustFinishBeforeLaunchingSoItCannotRestoreHomeOverTheApp',
    'appThatOpensThenReturnsHomeCannotReportSuccess',
}
failures = {}
for path in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    for test in ET.parse(path).findall('testcase'):
        failure = test.find('failure')
        if test.attrib['name'] in expected and failure is not None:
            failures[test.attrib['name']] = failure.attrib.get('message', '')
assert set(failures) == expected, failures
assert all('AssertionError' in reason for reason in failures.values()), failures
Path('app/build/outputs/regressions/opening-negative.json').write_text(json.dumps(failures, indent=2)+'\n')
print('Les deux régressions ouvrir échouent par assertion sur le dispatcher 2.11.1.')
PY
