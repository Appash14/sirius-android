"""Lightweight static checks; no local Android toolchain required."""
import pathlib
import subprocess
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parent.parent
paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
errors = []
for name in filter(None, paths):
    path = root / name
    try:
        content = path.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        continue
    if any(chr(cp) in content for cp in (0x2013, 0x2014)):
        errors.append(f'{name}: caractère interdit')
    if name.endswith('.xml'):
        ET.fromstring(content)
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
permissions = {node.attrib[ns + 'name'] for node in manifest.findall('uses-permission')}
expected = {'RECORD_AUDIO', 'MODIFY_AUDIO_SETTINGS', 'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_MICROPHONE', 'POST_NOTIFICATIONS', 'INTERNET', 'RECEIVE_BOOT_COMPLETED', 'WAKE_LOCK'}
assert permissions == {'android.permission.' + name for name in expected}
app = manifest.find('application')
assert app.attrib[ns + 'allowBackup'] == 'false'
assert app.attrib[ns + 'usesCleartextTraffic'] == 'false'
voice = ET.parse(root / 'app/src/main/res/xml/voice_interaction.xml').getroot()
assert voice.attrib[ns + 'supportsLaunchVoiceAssistFromKeyguard'] == 'true'
assert voice.attrib[ns + 'sessionService'] == 'fr.tom.sirius.SiriusSessionService'
assert voice.attrib[ns + 'recognitionService'] == 'fr.tom.sirius.SiriusRecognitionService'
accessibility = ET.parse(root / 'app/src/main/res/xml/accessibility_service.xml').getroot()
for capability in ('canRetrieveWindowContent', 'canPerformGestures', 'canTakeScreenshot'):
    assert accessibility.attrib[ns + capability] == 'true'
assert 'flagRequestFilterKeyEvents' not in accessibility.attrib[ns + 'accessibilityFlags']
for name, permission in [('SiriusAccessibilityService', 'BIND_ACCESSIBILITY_SERVICE'), ('SiriusNotificationService', 'BIND_NOTIFICATION_LISTENER_SERVICE')]:
    service = next(node for node in app.findall('service') if node.attrib[ns + 'name'] == '.' + name)
    assert service.attrib[ns + 'permission'] == 'android.permission.' + permission
confirmation = next(node for node in app.findall('activity') if node.attrib[ns + 'name'] == '.ActionConfirmationActivity')
assert confirmation.attrib[ns + 'exported'] == 'false'
# Arrêt d'urgence et service micro du pilotage : jamais joignables par une autre application.
for tag, name in [('service', '.PilotSessionService'), ('receiver', '.PilotStopReceiver')]:
    node = next(node for node in app.findall(tag) if node.attrib[ns + 'name'] == name)
    assert node.attrib[ns + 'exported'] == 'false', name
assert next(node for node in app.findall('service') if node.attrib[ns + 'name'] == '.PilotSessionService').attrib[ns + 'foregroundServiceType'] == 'microphone'
assert not errors, '\n'.join(errors)
print('Sources UTF-8, XML, permissions et sauvegarde : OK')
