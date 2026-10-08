# Android bout en bout, phase 2, Sirius 2.4.3

Chaque push sur `main` lance `android.yml` et **Bout en bout**. Le banc attend
l'APK signé du SHA exact testé, contrôle son SHA256 et le certificat fixe
Sirius (`6c685472cdbb4e9592cc1c36b37737bfdc945079cdb4a43676b9256cbb5a16c6`).
Il ne recompile ni ne resigne un autre APK. `versionCode` reste le compteur
croissant de `android.yml` (1000 + GITHUB_RUN_NUMBER).

```bash
gh workflow run e2e.yml --ref main
gh run list --workflow e2e.yml --branch main
gh run watch <id> --exit-status
gh run download <id> --dir artifacts/run-<id>
```

Si aucun runner n'est attribué : `gh run rerun <id>`. Un lancement manuel
attend aussi un `android.yml` réussi au même commit.

## Scénarios et assertions

Les neuf scénarios sont exécutés même si l'un échoue. Une assertion échouée
maintient le workflow en échec. L'appli est fermée uniquement entre les
scénarios ; aucune réouverture cachée entre deux échanges ou à la reconnexion.

| Scénario | Preuve attendue |
| --- | --- |
| conversation_simple | Deux échanges par WAV, dernières bulles visibles sans geste, audio_ack après MediaPlayer |
| texte_long | Textes complets de Tom et Sirius, parcours aux deux extrémités, aucune ellipse dans Compose |
| coupure_reconnexion | WebSocket fermé proprement, HTTPS absent 5 s, fil conservé et reconnexion automatique |
| dix_echanges | 20 bulles accessibles, au moins 6 visibles, première bulle près du haut, aucun doublon |
| transcript_direct | Voix avant hello retardé, deux partiels visibles avant final, ancien identifiant serveur fusionné via le POST |
| surimpression | Vraie VoiceInteractionSession sur Settings, 10 échanges, voile et pixels sous la navigation |
| pilotage | Settings ouvert, toucher(x,y) et défiler exécutés, appli bancaire refusée, aucun bout micro pendant une action |
| reflexion_70s | Mode vocal ouvert avec une relance puis ignored, réponse et audio_ack après 70 s |
| coupure_humaine | WAV de 0,2 s sans interruption, WAV de 1 s avec interrupt reçu en moins de 0,8 s |

Les XML et PNG attendent le rendu. Le fil doit occuper la hauteur sous le logo,
les textes doivent être uniques et tous accessibles. Une petite conversation
reste ancrée près de la pilule. Le fil débordant doit atteindre le haut.
Les extrémités sont détectées par les pixels du centre du fil : les bornes XML
d'une bulle plus haute que l'écran restent tronquées pendant son défilement.
Les tests Compose vérifient chaque ligne des longs textes, l'ajout d'une bulle,
sa croissance et le bouton `Nouveau message ↓` lorsque le lecteur est remonté.

`android.yml` contrôle aussi quatre régressions sur les sources de `244d7e3` :
maintien du fil, fenêtre pendant coupure, partiels après hello et fusion du
final portant un identifiant distinct. Les quatre doivent échouer par
AssertionError sur l'ancien moteur et passer sur le moteur corrigé.

## Environnement et audio

AVD API 34 google_apis x86_64, root, KVM, 1080 x 2340, 420 dpi. CA TLS
éphémère installée dans Conscrypt. Configuration via l'UI pour garder Android
Keystore. Serveur HTTPS `https://10.0.2.2:4860/voix/`, accès `e2e` / `e2e`,
administration locale `http://127.0.0.1:4861`. Aucun accès de production.

PulseAudio a deux sinks : les WAV vont dans `sirius_micro`, dont le monitor
est le micro hôte, et Android joue ses réponses dans `sirius_sortie`.
Le binaire Qt caché tourne sur Xvfb avec `-qt-hide-window -allow-host-audio`.
`adb emu avd hostmicon`, `QEMU_AUDIO_*_DRV=pa`, `QEMU_PA_SERVER/SOURCE/SINK`
et `PULSE_SERVER` désignent le socket et la source explicites. Avant chaque
scénario, le banc exige une source-output QEMU reliée au monitor.

`-no-window` sélectionnait le binaire headless dont les
[fonctions Pulse retournent NULL](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-master-dev/audio/paaudio-headless-impl.c).
Options officielles : [commande de l'émulateur](https://developer.android.com/studio/run/emulator-commandline)
et [micro hôte](https://developer.android.com/studio/run/emulator-extended-controls).

`espeak-ng` produit des WAV français. `paplay` les envoie au vrai AudioRecord
Android. Le serveur mesure format PCM16 mono 16 kHz, énergie et continuité des
bouts. Il renvoie une réponse MP3 et exige son accusé après lecture. Les longues
lectures durent 65 s, ou 180 s pour parcourir les deux très grandes bulles.
L'enregistrement vidéo tourne en segments assemblés sans réencodage.

Les apps Google dépendantes de Play Services sont désactivées avec celui-ci
sur l'AVD isolé, pour éviter leurs notifications répétées. Les attentes de rendu
sont explicites ; les délais idle/selector du
[configurateur UiAutomator](https://github.com/openatx/uiautomator2#settings)
sont zéro. Une notification demandant CAN_DUCK baisse désormais le volume
Sirius sans détruire MediaPlayer ni perdre audio_ack, puis le restaure sur GAIN.

## Contrat serveur et corrections applicatives

`serveur.py`, sa copie `serveur.py.avant-doublon` et `PROTOCOLE.md` ont été lus.
Les traces `e2e/contrat/serveur-actuel.json` et `serveur-avant-doublon.json`
proviennent de vrais sockets locaux avec transcription simulée, sans clé.
Elles portent le SHA256 de chaque source. Les tests Python comparent ces
trames avec le faux serveur. Whisper ne produit pas fin_de_tour ; les
identifiants du POST, de la question et de l'appareil sont distincts.

```bash
voix/venv/bin/python e2e/contrat/capturer.py chemin/serveur.py sortie.json
VOIX_FLUX=0 voix/venv/bin/python -m unittest discover -s voix/tests -p test_partiels.py
```

La fusion utilise aussi `waitingQuestions`, même si le POST arrive après le
final et reply_end. Une relance ne solde pas la question, ignored n'efface pas
l'attente antérieure, et la limite est 5 min. Reconnexion toutes les secondes
pendant les 30 premières secondes. Le fil suit les insertions et le texte qui
grandit, et garde la position si Tom lit plus haut.

Le détecteur mesure la voix après AEC pendant la lecture (paramètres 520 ms,
900 RMS). Pendant le pilotage, la transcription est suspendue durant les
actions, les 2 s suivantes et la lecture média du téléphone. Le reconnaisseur
local d'arrêt conserve son vocabulaire complet et n'accepte que les commandes
courtes ; une question contenant « arrêté » ne coupe plus le pilotage.

Les touchers n'ont plus de confirmation. Les tests du vrai service Robolectric
prouvent le geste dans une appli ordinaire et son refus en banque ou écran
verrouillé. Bouton ARRÊTER, arrêt vocal, blocage bancaire, journal et gestes
horizontaux seulement avec explicite:true sont conservés.

Si le pilotage est activé et l'accessibilité absente, l'ouverture de l'appli
montre une bannière et un bouton vers le réglage de Sirius. Le composant est
stable depuis sa création ; les traces SiriusAccessibility aident à rechercher
un crash sur S25. L'animation 2.4.2 d'Orion est conservée.

## Artefacts et limites

Sous `artifacts/run-<id>/` :

- `sirius-debug.apk/` : APK signé et SHA256 du build Android.
- `e2e-videos/` : configuration, segments et une MP4 par scénario, sans piste son.
- `e2e-captures/` : PNG et arbres UI, y compris les défilements.
- `e2e-logcat/` : resultats, logcat, événements, provenance APK et connexion Pulse.
- `e2e-apk-audio/` : APK testé, empreinte, WAV et MP3 utilisés.

Les sept tests Python du banc et les vingt tests partiels du vrai serveur
ont passé en local. Le serveur de production n'est pas lancé en CI : le STT/TTS
est simulé et aucune qualité de reconnaissance n'est mesurée. L'entrée WAV,
la capture Android, les uploads, la lecture MP3 et les gestes sont réels.
La qualité acoustique de l'AEC et la cause Samsung de la perte d'accessibilité
restent à vérifier sur S25. Le reboot du banc perd les données volatiles mais
conserve l'historique. L'instrumentation complète de la phase 3 n'est pas incluse.

Les vidéos sont encodées en 540x1170 pour laisser le micro temps réel fonctionner sur le runner ; les captures et les mesures de bornes restent en 1080x2340. Les contrôles de texte et de bornes utilisent le même snapshot XML. UiAutomation conserve le service d’accessibilité de Sirius (`uiAutomationFlags=1`).

## Phase 3, livraison applicative Sirius 2.5

Tom a donné priorité aux fonctions et autorisé la livraison dès que android.yml est vert. Le banc reste en phase 2 : ses neuf fichiers de scénarios, dont les sept d'origine, et ses seuils sont conservés. La branche e2e-fix n'est pas fusionnée faute de run vert. Les essais physiques KEYCODE_ASSIST, réveil Vosk, leurre et réveil rejeté ne sont pas encore automatisés dans main. Aucun résultat de phase 3 sur émulateur n'est revendiqué.

L'APK instrumente le vrai VoiceInteractionSession : `adb logcat -s SiriusAnim:I` montre service prêt, détection, showSession, création du contenu, onShow, premier dessin, début de conversation, fin d'entrée/sortie et hide. Les FrameMetrics sont collectées sur un HandlerThread pendant les animations seulement, avec p50, p90, maximum et compte au-delà de 16,7 ms. Les lignes ne contiennent ni conversation ni audio. Le préchauffage prépare réglages, courbes et dessin hors du thread principal ; une fenêtre de session n'est pas créée en avance au démarrage du téléphone.

Pour une recette réelle : attribuer le rôle avec `adb shell cmd role add-role-holder android.app.role.ASSISTANT fr.tom.sirius`, contrôler ses titulaires et les deux réglages secure sans les écrire ; vérifier user_setup_complete=1, réveiller/déverrouiller ; ouvrir Paramètres clair ; remettre les trois échelles à 1 ; `adb shell input keyevent KEYCODE_ASSIST`. Le log attendu est source=assist invocation=7. Tester glisser, Retour, micro, silence12 s, puis l'éveil et reveil_rejete. Retirer le rôle, désactiver l'éveil et remettre les échelles à 0 en fin de recette. `cmd voiceinteraction show` n'est qu'une sonde. KEYCODE_VOICE_ASSIST et l'activité ACTION_ASSIST ne prouvent pas ce parcours.

`analyser_video.py` accepte une vidéo native 1080x2340 et un JSON de fenêtres de cinq secondes au plus autour de chaque transition, avec le rectangle hud-pill du dump final :

```json
[{"name":"assistant-froid","direction":"entree","start":1.0,"end":2.5,"pill":[100,2100,980,2230]}]
```

```bash
e2e/.venv/bin/python e2e/analyser_video.py video.mp4 events.json --output logcat/analyse-assistant.json --sheets captures/
e2e/.venv/bin/python -m unittest e2e.test_analyser_video -v
```

L'analyse garde les PTS de ffprobe et extrait avec `-fps_mode passthrough`. Elle donne durée, images intermédiaires, monotonie, saut maximal, écart de PTS maximal, luminance de la première pilule et ratios du voile ; une planche de huit images accompagne chaque transition. Entrée exigée : 140 à 480 ms, six images intermédiaires, première pilule au moins 85 % du fond, premier voile moins de 40 %, saut au plus 50 %, écart au plus 100 ms. Sortie : 120 à 380 ms, quatre images, mêmes contrôles de continuité, fond restauré à 2 % près. Les seuils de voile sont ceux de check_veil. Ces mesures ont trois tests synthétiques ; le repérage du bord de pilule et le décodage complet sont encore à calibrer sur des vidéos réelles. L'analyseur n'est pas encore appelé automatiquement par le pilote.

Le faux serveur conserve eveil (query ou X-Sirius-Eveil) dans chaque preuve microphone_chunk et son état. Avec reject_wake, il émet reveil_rejete sans réponse ni audio. Le test de protocole exerce query et en-tête.

Restent pour le banc : cache du modèle Vosk avec SHA256 épinglé, WAV d'éveil validés sur Vosk hôte, injection gRPC après l'échec Pulse de e2e-fix, quatre scénarios assistant et analyse automatique dans resultat.json. Aucun crochet de test n'a été ajouté. Les durées réelles, FrameMetrics, latences à froid/chaud, appui long power, voix de Tom et 60 i/s sur S25 restent non vérifiés.
