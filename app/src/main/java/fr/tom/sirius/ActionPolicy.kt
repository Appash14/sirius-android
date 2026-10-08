package fr.tom.sirius

import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.text.Normalizer
import java.util.Locale

class ActionFailure(val reason: String) : Exception(reason)
fun requireAction(value: Boolean, reason: String) { if (!value) throw ActionFailure(reason) }

data class PhoneAction(val id: String, val action: String, val args: JSONObject) {
    companion object {
        fun parse(json: JSONObject): PhoneAction {
            val id = json.opt("id") as? String ?: throw ActionFailure("id_invalide")
            requireAction(id.matches(Regex("[A-Za-z0-9._:-]{1,100}")), "id_invalide")
            val action = json.optString("action")
            val args = json.opt("args") as? JSONObject ?: throw ActionFailure("arguments_invalides")
            val keys = args.keys().asSequence().toSet()
            val allowed = when (action) {
                "ecran", "capture", "notifications", "arreter_session" -> emptySet()
                "ouvrir" -> setOf("paquet", "nom")
                "toucher" -> setOf("texte", "id", "x", "y")
                "ecrire" -> setOf("texte")
                "defiler" -> setOf("direction")
                "glisser" -> setOf("direction", "explicite")
                "global" -> setOf("commande")
                "lancer_intent" -> setOf("uri")
                "sequence" -> setOf("actions")
                "envoyer_message" -> setOf("appli", "conversation", "texte")
                else -> throw ActionFailure("action_inconnue")
            }
            requireAction(keys.all { it in allowed }, "arguments_invalides")
            fun string(key: String, max: Int = 300): String {
                val value = args.opt(key) as? String ?: throw ActionFailure("arguments_invalides")
                requireAction(value.isNotBlank() && value.length <= max, "arguments_invalides")
                return value
            }
            when (action) {
                "ouvrir" -> { requireAction(keys.size == 1, "arguments_invalides"); val value = string(keys.single()); if ("paquet" in keys) requireAction(value.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")), "arguments_invalides") }
                "toucher" -> {
                    val label = keys == setOf("texte") || keys == setOf("id")
                    requireAction(label || keys == setOf("x", "y"), "arguments_invalides")
                    if (label) string(keys.single()) else for (key in keys) {
                        requireAction(args.opt(key) is Number && args.getDouble(key).isFinite() && args.getDouble(key) >= 0, "arguments_invalides")
                    }
                }
                "ecrire" -> { requireAction(keys == setOf("texte"), "arguments_invalides"); string("texte", 4000) }
                "defiler" -> requireAction(keys == setOf("direction") && string("direction") in setOf("haut", "bas"), "arguments_invalides")
                "glisser" -> {
                    // Sideways swipes change tabs or open messages: only when Tom asked for it, marked "explicite": true.
                    val direction = string("direction")
                    requireAction(direction in setOf("haut", "bas", "gauche", "droite"), "arguments_invalides")
                    if (direction == "gauche" || direction == "droite") requireAction(keys == setOf("direction", "explicite") && args.opt("explicite") == true, "arguments_invalides")
                    else requireAction(keys == setOf("direction"), "arguments_invalides")
                }
                "global" -> requireAction(keys == setOf("commande") && string("commande") in setOf("retour", "accueil", "recents", "notifications"), "arguments_invalides")
                "lancer_intent" -> { requireAction(keys == setOf("uri"), "arguments_invalides"); ActionPolicy.validateUri(string("uri", 2000)) }
                "sequence" -> {
                    val steps = args.opt("actions") as? JSONArray ?: throw ActionFailure("arguments_invalides")
                    requireAction(steps.length() in 1..20, "arguments_invalides")
                    for (i in 0 until steps.length()) {
                        val step = steps.optJSONObject(i) ?: throw ActionFailure("arguments_invalides")
                        requireAction(step.keys().asSequence().toSet() == setOf("action", "args"), "arguments_invalides")
                        requireAction(step.optString("action") !in setOf("sequence", "envoyer_message", "arreter_session"), "arguments_invalides")
                        parse(JSONObject().put("id", "step-$i").put("action", step.opt("action")).put("args", step.opt("args")))
                    }
                }
                "envoyer_message" -> {
                    requireAction(keys == setOf("appli", "conversation", "texte") && string("appli") == "telegram", "arguments_invalides")
                    string("conversation"); string("texte", 4000)
                }
            }
            return PhoneAction(id, action, args)
        }
    }
}

object ActionPolicy {
    private val sensitiveWords = listOf("envoyer", "send", "appeler", "call", "payer", "pay", "publier", "post", "valider le paiement",
        "acheter", "purchase", "commander", "submit", "partager", "share", "transferer", "confirmer", "reserver", "reply", "repondre")
    // Intentionally conservative. Unknown financial apps can be added here without granting any new permission.
    private val financialPackages = listOf("bank", "banque", "banking", "bnp", "creditagricole", "creditmutuel", "caissedepargne", "caisse", "societegenerale",
        "boursorama", "boursobank", "bourso", "revolut", "n26", "paypal", "paylib", "wallet", "spay", "samsungpay", "lydia", "sumeria", "wise", "bunq", "monzo", "hsbc",
        "lcl", "lapostemobile.banque", "labanquepostale", "fortuneo", "hellobank", "floa", "stripe", "coinbase", "binance", "crypto", "venmo", "cashapp", "chase", "capitalone",
        // Switzerland (Tom lives there): banks, cantonal banks, payment apps and neobanks.
        "postfinance", "raiffeisen", "twint", "paymit", "migrosbank", "creditsuisse", "credit_suisse", "swissquote", "transferwise", "bankcler", "alpian",
        "radicant", "kantonalbank", "banquecantonale", "bancacantonale", "bancastato", "banca", "valiant", "cembra", "viseca", "swisscard", "cornercard",
        "juliusbaer", "vontobel", "pictet", "lombardodier", "finpension")
    // Short names only match a whole word of the package or label, never the inside of another word (subscriptions).
    private val financialWords = setOf("ubs", "bcn", "bcv", "bcvs", "bcge", "bcf", "bcj", "fkb", "zkb", "bkb", "bekb", "lukb", "sgkb", "tkb", "gkb", "akb",
        "blkb", "szkb", "zugerkb", "nkb", "okb", "glkb", "ukb", "appkb", "shkb", "yuh", "neon", "zak", "cler", "wise", "viac", "saxo", "ibkr", "key4",
        "twint", "revolut", "lcl", "hsbc", "n26", "bunq", "monzo", "lydia", "paypal", "sumeria")
    // Searched in the compacted label too: "Migros Bank" becomes "migrosbank", "Credit Suisse" becomes "creditsuisse".
    private val financialLabels = listOf("banque", "bank", "banca", "paiement", "payment", "wallet", "portefeuille", "postfinance", "raiffeisen", "twint",
        "creditsuisse", "swissquote", "revolut", "transferwise", "alpian", "radicant", "kantonalbank", "banquecantonale", "bancastato", "cembra",
        "viseca", "swisscard", "cornercard", "juliusbaer", "vontobel", "pictet", "lombardodier", "finpension", "boursorama", "paypal", "coinbase", "binance")
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    private fun words(text: String) = normalize(text).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    fun blockedPackage(pkg: String, label: String = ""): Boolean {
        val name = normalize(pkg)
        val compactLabel = normalize(label).replace(Regex("[^a-z0-9]+"), "")
        return financialPackages.any { name.contains(it) } || financialLabels.any { compactLabel.contains(it) } ||
            (words(pkg) + words(label)).any { it in financialWords }
    }
    fun needsConfirmation(labels: String, coordinates: Boolean = false) = coordinates || labels.none { it.isLetter() } || sensitiveWords.any { normalize(labels).contains(it) }
    /** Order matters: keyguard, then Tom's emergency stop, then the opt-in switch, then banking apps. */
    fun guard(enabled: Boolean, locked: Boolean, pkg: String = "", label: String = "", stopped: Boolean = false) {
        requireAction(!locked, "verrouille")
        requireAction(!stopped, STOPPED_BY_TOM)
        requireAction(enabled, "pilotage_desactive")
        requireAction(!blockedPackage(pkg, label), "application_bloquee")
    }
    fun validateUri(raw: String): URI {
        val uri = try { URI(raw) } catch (_: Exception) { throw ActionFailure("uri_interdite") }
        requireAction(raw.none { it.code < 32 } && uri.scheme == uri.scheme?.lowercase(Locale.ROOT), "uri_interdite")
        requireAction(uri.scheme in setOf("tel", "sms", "geo", "https"), "uri_interdite")
        requireAction(uri.userInfo == null && uri.fragment == null, "uri_interdite")
        if (uri.scheme == "https") requireAction(!uri.host.isNullOrBlank(), "uri_interdite")
        if (uri.scheme == "tel" || uri.scheme == "sms") {
            // No USSD, recipients lists, body/query or intent extras. Only opening a dialer/composer.
            requireAction(uri.rawSchemeSpecificPart.matches(Regex("\\+?[0-9 ()-]{1,40}")), "uri_interdite")
        }
        if (uri.scheme == "geo") requireAction(uri.rawSchemeSpecificPart.matches(Regex("[-+0-9.,]+(\\?q=[^#]{1,300})?")), "uri_interdite")
        return uri
    }
}

const val STOPPED_BY_TOM = "arrete_par_tom"

fun actionResult(id: String, ok: Boolean, data: JSONObject = JSONObject()): JSONObject =
    JSONObject().put("type", "action_result").put("id", id).put("ok", ok).put("data", data)
fun failedAction(id: String, reason: String) = actionResult(id, false, JSONObject().put("raison", reason))
fun reasonLabel(reason: String): String = when (reason) {
    "verrouille" -> "téléphone verrouillé"
    "pilotage_desactive" -> "pilotage désactivé"
    STOPPED_BY_TOM -> "arrêté par toi"
    "application_bloquee" -> "application bancaire ou de paiement"
    "confirmation_refusee" -> "tu as refusé"
    "confirmation_expiree" -> "confirmation expirée"
    "confirmation_indisponible" -> "notification de confirmation indisponible"
    "cible_modifiee" -> "l'écran a changé"
    "accessibilite_absente" -> "accessibilité à activer"
    "notifications_absentes" -> "accès aux notifications à activer"
    "connexion_perdue" -> "connexion interrompue"
    "cible_ambigue" -> "plusieurs éléments correspondent"
    else -> "action indisponible"
}
