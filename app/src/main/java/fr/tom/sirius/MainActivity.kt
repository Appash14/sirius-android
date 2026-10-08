package fr.tom.sirius

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

private enum class Page { CONVERSATION, SETTINGS, PILOT, RING }

class MainActivity : ComponentActivity() {
    private val app get() = application as SiriusApp
    private var pendingWake = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (app.engine.hasMicPermission()) {
            if (pendingWake) enableWake() else app.engine.beginConversation()
        } else app.engine.report("Autorise le micro pour parler à Sirius.")
        pendingWake = false
        app.pilot.refresh()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { app.pilot.refresh() }
    private val assistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        setContent {
            val state by app.engine.state.collectAsStateWithLifecycle()
            val pilot by app.pilot.state.collectAsStateWithLifecycle()
            var page by remember { mutableStateOf(if (app.settings.read().configured) Page.CONVERSATION else Page.SETTINGS) }
            BackHandler(page != Page.CONVERSATION) { page = Page.CONVERSATION }
            SiriusTheme {
                when (page) {
                    Page.CONVERSATION -> ConversationScreen(state, pilot, { requestAudio(false) }, { app.engine.endConversation() },
                        { page = Page.SETTINGS }, { page = Page.PILOT }, onVolume = app.engine::setSiriusVolume, onAccessibility = { openAccessibility() },
                        onResumePilot = { if (!app.engine.locked()) { app.pilot.setEnabled(true); app.engine.pilotChanged() } })
                    Page.SETTINGS -> SettingsPage(app.settings.read(), state, onBack = { page = Page.CONVERSATION }, onSave = { value ->
                        app.settings.save(value); app.pilot.cancelPending("configuration_modifiee")
                        app.engine.configurationChanged(); page = Page.CONVERSATION
                    }, onWake = { enabled ->
                        if (enabled) requestAudio(true) else { app.settings.wakeEnabled = false; WakeService.stop(this@MainActivity) }
                    }, onDownload = { app.engine.prepareModel() }, onAssistant = { chooseAssistant() }, onVolume = app.engine::setSiriusVolume,
                        onSensitivity = app.engine::setWakeSensitivity, onPilot = { page = Page.PILOT }, pilotEnabled = pilot.enabled,
                        stopCode = app.settings.pilotStopCode, onStopCode = { app.settings.pilotStopCode = it },
                        onRing = { page = Page.RING },
                        onPilotEnabled = { app.pilot.setEnabled(it); app.engine.pilotChanged(); if (it) requestNotifications() })
                    Page.PILOT -> PilotPage(pilot, onBack = { page = Page.CONVERSATION }, onEnabled = { enabled ->
                        app.pilot.setEnabled(enabled); app.engine.pilotChanged()
                        if (enabled) requestNotifications()
                    }, onAccessibility = { openAccessibility() },
                        onNotifications = { openSystemSettings(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
                    Page.RING -> RingCalibrationPage(app.settings.ringTuning, onSave = { app.settings.ringTuning = it; page = Page.SETTINGS },
                        onBack = { page = Page.SETTINGS })
                }
            }
        }
        if (!app.engine.modelStore.installed()) app.engine.prepareModel()
    }
    override fun onResume() {
        super.onResume()
        app.engine.appVisible = true
        getSystemService(NotificationManager::class.java).cancel(43)
        app.pilot.refresh()
        app.engine.ensureConnection()
        if (app.settings.wakeEnabled && app.engine.modelStore.installed() && app.engine.hasMicPermission()) WakeService.start(this)
    }
    override fun onPause() { app.engine.appVisible = false; super.onPause() }
    private fun requestAudio(wake: Boolean) {
        if (!app.settings.read().configured) { app.engine.report("Ouvre Réglages pour connecter Sirius à ton serveur."); return }
        if (app.engine.hasMicPermission()) {
            if (wake) enableWake() else app.engine.beginConversation()
            requestNotifications()
        } else {
            pendingWake = wake
            permissions.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun enableWake() {
        if (!app.engine.modelStore.installed()) { app.engine.prepareModel(); return }
        app.settings.wakeEnabled = true; WakeService.start(this)
    }
    private fun chooseAssistant() {
        val role = getSystemService(RoleManager::class.java)
        if (role.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !role.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            assistantRole.launch(role.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
        } else openSystemSettings(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS)
    }
    private fun openAccessibility() {
        val component = android.content.ComponentName(this, SiriusAccessibilityService::class.java)
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra("android.intent.extra.COMPONENT_NAME", component.flattenToString())
        runCatching { startActivity(detail) }.onFailure { openSystemSettings(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS) }
    }
    private fun openSystemSettings(action: String) { runCatching { startActivity(Intent(action)) }.onFailure { app.engine.report("Ouvre cette autorisation dans les réglages Android.") } }
}

@Composable private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Retour") }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable internal fun SettingsPage(initial: ServerSettings, state: VoiceState, onBack: () -> Unit,
    onSave: (ServerSettings) -> Unit, onWake: (Boolean) -> Unit, onDownload: () -> Unit, onAssistant: () -> Unit, onVolume: (Float) -> Unit,
    onSensitivity: (WakeSensitivity) -> Unit = {}, onPilot: () -> Unit = {}, pilotEnabled: Boolean = false,
    stopCode: String = StopWords.DEFAULT_CODE, onStopCode: (String) -> Unit = {}, onPilotEnabled: (Boolean) -> Unit = {},
    onRing: () -> Unit = {}) {
    var code by remember { mutableStateOf(stopCode) }
    var address by remember { mutableStateOf(initial.address) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var error by remember { mutableStateOf<String?>(null) }
    NightBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PageHeader("Réglages", onBack)
            Text("Voix", style = MaterialTheme.typography.titleLarge)
            SiriusVolume(state.siriusVolume, onVolume)
            Text("Les touches du téléphone règlent le volume médias.", style = MaterialTheme.typography.bodySmall)
            Text(state.audioRoute, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAssistant, modifier = Modifier.fillMaxWidth()) { Text("Choisir Sirius comme assistant") }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Mot d'éveil", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dis Sirius", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.wakeRunning) "Mot d'éveil local actif" else "Micro en veille hors conversation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.wakeRunning, onWake, enabled = state.modelReady && !state.downloading)
            }
            Text("Sensibilité du mot d'éveil", style = MaterialTheme.typography.titleMedium)
            Text("Moyenne par défaut. Faible demande un « Dis Sirius » très net et peut t'en faire répéter un.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WakeSensitivity.entries.forEach { level ->
                    RadioButton(state.wakeSensitivity == level, { onSensitivity(level) })
                    Text(level.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 10.dp))
                }
            }
            if (!state.modelReady) {
                Text(if (state.downloading) "Je prépare le modèle français…" else "Le modèle local permet uniquement le mot d'éveil.", style = MaterialTheme.typography.bodyMedium)
                if (state.downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else OutlinedButton(onClick = onDownload) { Text("Télécharger le modèle") }
            }
            Text("Après chaque réponse, 12 s pour continuer. Sans parole, je me rendors.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Pilotage", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(code, { code = it }, label = { Text("Code d'arrêt vocal") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Text("Seul ce code entier arrête la session. Le bouton ARRÊTER reste immédiat.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onStopCode(code) }, enabled = StopWords.validCode(code)) { Text("Enregistrer le code") }
            OutlinedButton(onClick = onRing) { Text("Caler l'anneau de la caméra") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pilotage autorisé", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(pilotEnabled, onPilotEnabled)
            }
            Text("Autorisations, arrêt d'urgence et journal des actions.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onPilot, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir le pilotage") }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Connexion", style = MaterialTheme.typography.titleLarge)
            Text("Les mêmes accès que ta page voix, chiffrés sur ce téléphone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(address, { address = it }, label = { Text("Adresse HTTPS") }, placeholder = { Text("https://ton-site/voix/") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(username, { username = it }, label = { Text("Identifiant") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Mot de passe") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                try { onSave(ServerSettings(address, username.trim(), password)) }
                catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: "Vérifie les champs." else "Impossible d'enregistrer les réglages." }
            }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer la connexion") }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Sirius ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun PilotPage(state: PilotState, onBack: () -> Unit, onEnabled: (Boolean) -> Unit,
    onAccessibility: () -> Unit, onNotifications: () -> Unit) {
    NightBackdrop(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageHeader("Pilotage", onBack) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pilotage autorisé", style = MaterialTheme.typography.titleMedium)
                        Text("Tu gardes le dernier mot pour envoyer, appeler ou publier.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(state.enabled, onEnabled)
                }
            }
            item { AuthorizationCard("Accessibilité", state.accessibility, "Lire l'écran et agir dans les applications", onAccessibility) }
            item { AuthorizationCard("Notifications", state.notifications, "Lire les 30 dernières notifications", onNotifications) }
            if (state.stopped) item {
                Text("Session arrêtée. Ta prochaine demande vocale pourra piloter si Pilotage autorisé est actif.",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            item { Text("Aucune action sur écran verrouillé. Les applications bancaires et de paiement sont bloquées. Les liens demandent une confirmation. Pendant le pilotage, une notification et une pastille gardent le bouton ARRÊTER. Le code vocal se règle dans Réglages.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!state.confirmations) item { Text("Autorise les notifications de Sirius pour recevoir les demandes de confirmation.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Journal local", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
                Text("100 dernières actions. Aucun texte d'écran ni contenu envoyé n'est conservé.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.log.isEmpty()) item { Text("Le journal est vide.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.log.asReversed(), key = { it.sequence }) { entry ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(entry.action, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.at)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (entry.source.isNotBlank()) Text("Source : ${entry.source}" +
                            if (entry.recognized.isNotBlank()) " · ${entry.recognized}" else "", style = MaterialTheme.typography.bodySmall)
                        Text(if (entry.ok) "Exécutée" else "Refusée : ${reasonLabel(entry.reason)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun AuthorizationCard(title: String, enabled: Boolean, description: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(if (enabled) "Autorisée  ›" else "À activer  ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
