package fr.tom.sirius

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActionPolicyTest {
    private fun refused(reason: String, operation: () -> Unit) {
        try { operation(); fail("Action autorisée") } catch (failure: ActionFailure) { assertEquals(reason, failure.reason) }
    }
    @Test fun keyguardWinsEvenWhenPilotageIsDisabledAndForReadActions() {
        refused("verrouille") { ActionPolicy.guard(false, true) }
        refused("verrouille") { ActionPolicy.guard(true, true) }
        refused("pilotage_desactive") { ActionPolicy.guard(false, false) }
        for (action in listOf("ecran", "capture", "notifications")) {
            assertEquals(action, PhoneAction.parse(JSONObject().put("id", "test-$action").put("action", action).put("args", JSONObject())).action)
        }
    }
    @Test fun financePackagesAndPaymentLabelsCannotBeApproved() {
        for (pkg in listOf("com.revolut.revolut", "com.paypal.android", "com.google.android.apps.walletnfcrel", "com.boursorama.android.clients", "com.samsung.android.spay")) {
            refused("application_bloquee") { ActionPolicy.guard(true, false, pkg) }
        }
        refused("application_bloquee") { ActionPolicy.guard(true, false, "com.example.mobile", "Ma Banque") }
        ActionPolicy.guard(true, false, "com.android.settings", "Paramètres")
    }
    @Test fun swissBanksPaymentAppsAndNeobanksAreBlockedByPackageAndLabelIgnoringCase() {
        val packages = listOf("com.ubs.swidKXJ.android", "COM.UBS.KEY4", "ch.postfinance.android", "ch.raiffeisen.mobile", "ch.bcn.mobile", "ch.bcv.mobile",
            "ch.bcge.android", "ch.zkb.mbanking", "ch.lukb.mobile", "ch.twint.payment", "com.yuh.app", "ch.neon.app", "ch.migrosbank.mobile",
            "com.creditsuisse.directnet", "com.swissquote.android", "com.revolut.revolut", "com.transferwise.android", "ch.cler.zak", "com.alpian.mobile",
            "ch.radicant.app", "ch.viac.app")
        for (pkg in packages) refused("application_bloquee") { ActionPolicy.guard(true, false, pkg, "") }
        val labels = listOf("UBS", "uBs Mobile Banking", "PostFinance", "POSTFINANCE", "Raiffeisen", "BCN", "Banque Cantonale Neuchâteloise", "BCV", "BCGE",
            "ZKB", "Zürcher Kantonalbank", "TWINT", "Yuh", "neon", "Migros Bank", "Credit Suisse", "Swissquote", "Revolut", "Wise", "Zak", "Alpian",
            "radicant", "Banca Stato")
        for (label in labels) refused("application_bloquee") { ActionPolicy.guard(true, false, "com.example.app", label) }
        // Short names are whole words only: no false positive inside ordinary package names.
        for ((pkg, label) in listOf("com.android.settings" to "Paramètres", "com.whatsapp" to "WhatsApp", "com.example.subscriptions" to "Abonnements",
                "ch.sbb.mobile.android.b2c" to "SBB Mobile", "com.spotify.music" to "Spotify", "com.google.android.apps.maps" to "Maps",
                "ch.neuchatel.tourisme" to "Neuchâtel Tourisme", "com.samsung.android.messaging" to "Messages")) {
            assertFalse("$pkg / $label", ActionPolicy.blockedPackage(pkg, label))
        }
    }
    @Test fun frenchEnglishAndAccentsDetectSendingAndOpaqueTargetsRequireTap() {
        for (label in listOf("Envoyer", "SEND message", "Appeler", "Call", "Payer", "Pay", "Publier", "Post", "Valider le paiement", "Confirmer l’achat", "Répondre")) {
            assertTrue(label, ActionPolicy.needsConfirmation(label))
        }
        assertTrue(ActionPolicy.needsConfirmation(""))
        assertTrue(ActionPolicy.needsConfirmation("Ouvrir", coordinates = true))
        assertFalse(ActionPolicy.needsConfirmation("Réglages"))
    }
    @Test fun intentsOnlyOpenSimpleDialerComposerMapsOrHttps() {
        for (uri in listOf("tel:+33123456789", "sms:0612345678", "geo:48.8,2.3", "https://example.org/page")) assertNotNull(ActionPolicy.validateUri(uri))
        for (uri in listOf("intent://send#Intent;end", "file:///etc/passwd", "javascript:alert(1)", "http://example.org", "tel:*21*123#", "sms:123?body=send", "tel:123;456", "https://user:secret@example.org", "HTTPS://example.org")) {
            refused("uri_interdite") { ActionPolicy.validateUri(uri) }
        }
    }
    @Test fun malformedOrOversizedCommandsCannotReachAndroid() {
        val base = JSONObject().put("id", "action-test").put("action", "toucher")
        refused("arguments_invalides") { PhoneAction.parse(JSONObject(base.toString()).put("args", JSONObject().put("texte", "Envoyer").put("x", 20).put("y", 20))) }
        refused("arguments_invalides") { PhoneAction.parse(JSONObject(base.toString()).put("args", JSONObject().put("x", -1).put("y", 1))) }
        refused("action_inconnue") { PhoneAction.parse(JSONObject().put("id", "test").put("action", "shell").put("args", JSONObject())) }
        refused("arguments_invalides") { PhoneAction.parse(JSONObject().put("id", "test").put("action", "ecrire").put("args", JSONObject().put("texte", "a".repeat(4001)))) }
        val result = failedAction("test", "verrouille")
        assertFalse(result.getBoolean("ok")); assertEquals("verrouille", result.getJSONObject("data").getString("raison"))
    }
}
