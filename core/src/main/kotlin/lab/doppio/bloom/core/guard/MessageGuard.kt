package lab.doppio.bloom.core.guard

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.references.ReferenceLibrary

/**
 * Garde-fou déterministe appliqué à chaque brouillon avant qu'il n'arrive dans la file de validation.
 *
 * C'est l'équivalent du claims-check du projet marketing : un message qui affirme quelque chose
 * sur le prospect sans fait sourcé, ou qui invente un chiffre, est bloqué. Le LLM rédige,
 * le code vérifie.
 */
@Serializable
enum class Severity { BLOCK, WARN }

@Serializable
data class Violation(val rule: String, val severity: Severity, val detail: String) {
    override fun toString(): String = "[$severity] $rule : $detail"
}

data class GuardInput(
    val channel: Channel,
    val stepIndex: Int,
    val subject: String?,
    val body: String,
    val citedFactIds: List<String>,
    val availableFacts: List<Fact>,
    val referenceKey: String?,
    val sector: Sector,
    val offer: Offer,
    val language: String,
)

object MessageGuard {

    /** Marqueur de citation que l'agent place dans le texte, retiré avant envoi. */
    val citationRegex = Regex("""\[F:([A-Za-z0-9_-]+)]""")

    private val bannedFr = listOf(
        "accompagn", "digitalisation", "synergie", "partenaire de votre transformation", "leader",
        "incontournable", "expert reconnu", "n'hésitez pas", "je me permets", "solution innovante",
        "booster", "révolution", "à la pointe", "clé en main", "valeur ajoutée", "j'espère que vous allez bien",
    )
    private val bannedEn = listOf(
        "leverage", "synergy", "cutting-edge", "game-changer", "game changer", "hope this email finds you",
        "quick question", "touch base", "world-class", "best-in-class", "revolutionize", "just following up",
    )
    private val emojiRegex = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F000}-\\x{1F2FF}]")
    private val urlRegex = Regex("""https?://\S+|www\.\S+""")
    // Thousands separators: space, no-break space (U+00A0, French quotes), narrow no-break space (U+202F), dot, comma.
    private val numberSeparators = "[ \u00A0\u202F.,]"
    private val numberRegex = Regex("\\d+(?:$numberSeparators\\d+)*")

    private val ambiguousNames = setOf("routine", "proton")

    /** Nombres toujours autorisés : durées des offres, petits entiers de langage courant. */
    private val alwaysAllowedNumbers = setOf("1", "2", "3", "15", "20", "30")

    /** Marker plus the space that precedes it, so "paiement [F:a2]." gives "paiement." and "minutes ? [F:a2]" keeps its space. */
    private val citationWithSpaceRegex = Regex(" ?" + citationRegex.pattern)

    fun stripCitations(text: String): String =
        citationWithSpaceRegex.replace(text, "").replace(Regex(" {2,}"), " ").trim()

    fun check(input: GuardInput): List<Violation> {
        val v = mutableListOf<Violation>()
        val plain = stripCitations(input.body)
        // Subject and body together: a number or a link in the subject reaches the prospect just the same.
        val full = input.subject.orEmpty() + "\n" + plain
        val lower = full.lowercase()

        // 1. Typographie et ton
        if ('—' in lower || " – " in lower) {
            v += Violation("tiret-cadratin", Severity.BLOCK, "remplacer par une virgule, un point ou des parenthèses")
        }
        if (emojiRegex.containsMatchIn(lower)) v += Violation("emoji", Severity.BLOCK, "aucun emoji en prospection")
        val banned = if (input.language == "en") bannedEn else bannedFr
        banned.filter { it in lower }.forEach { v += Violation("vocabulaire", Severity.BLOCK, "expression interdite : « $it »") }
        if (plain.count { it == '!' } > 0) v += Violation("exclamation", Severity.WARN, "ton trop vendeur")

        // 2. Longueur par canal et par étape
        val words = plain.split(Regex("\\s+")).count { it.isNotBlank() }
        when (input.channel) {
            Channel.EMAIL -> {
                val max = if (input.stepIndex == 0) 120 else 80
                if (words > max) v += Violation("longueur", Severity.BLOCK, "$words mots, maximum $max pour cette étape")
                val subjectWords = input.subject?.split(" ")?.count { it.isNotBlank() } ?: 0
                if (input.subject.isNullOrBlank()) v += Violation("objet", Severity.BLOCK, "objet manquant")
                if (subjectWords > 7) v += Violation("objet", Severity.WARN, "objet de $subjectWords mots, viser 2 à 6")
                if (input.subject?.trim()?.lowercase()?.startsWith("re:") == true) {
                    v += Violation("objet", Severity.BLOCK, "faux « Re: » interdit")
                }
            }
            Channel.LINKEDIN -> if (plain.length > 300) {
                v += Violation("longueur", Severity.BLOCK, "${plain.length} caractères, maximum 300 pour une note LinkedIn")
            }
            Channel.PHONE -> {}
        }

        // 3. Preuves : chaque citation doit exister, le premier message doit en avoir au moins une
        val factsById = input.availableFacts.associateBy { it.id }
        val markers = citationRegex.findAll(input.body).map { it.groupValues[1] }.toSet()
        val cited = markers + input.citedFactIds
        cited.filter { it !in factsById }.forEach {
            v += Violation("fait-inconnu", Severity.BLOCK, "la citation F:$it ne correspond à aucun fait du dossier")
        }
        if (input.stepIndex == 0 && input.channel == Channel.EMAIL && markers.isEmpty()) {
            v += Violation("sans-preuve", Severity.BLOCK, "le premier email doit s'appuyer sur au moins un fait sourcé")
        }

        // 4. Chiffres inventés : tout nombre doit figurer dans un fait cité ou dans la preuve de la référence
        val reference = input.referenceKey?.let { ReferenceLibrary.byKey(it) }
        val provenanceText = buildString {
            cited.mapNotNull { factsById[it] }.forEach { append(it.statement).append(' ').append(it.excerpt.orEmpty()).append(' ') }
            reference?.let { append(it.proof) }
            append(' ').append(input.offer.label)
        }
        // Whole numbers only: "12" must not pass because a fact mentions "412".
        val sourcedNumbers = numberRegex.findAll(provenanceText).map { it.value.normalizeNumber() }.toSet()
        numberRegex.findAll(full).map { it.value.normalizeNumber() }.distinct()
            .filter { it !in alwaysAllowedNumbers && it !in sourcedNumbers }
            .forEach { v += Violation("chiffre-non-sourcé", Severity.BLOCK, "« $it » n'apparaît dans aucun fait cité") }

        // 5. Références clients : seulement celles qui ont une raison d'être citées
        if (reference == null && input.referenceKey != null) {
            v += Violation("référence", Severity.BLOCK, "référence inconnue : ${input.referenceKey}")
        }
        if (reference != null && !ReferenceLibrary.isAllowed(reference.key, input.sector, input.offer)) {
            v += Violation("référence", Severity.BLOCK, "${reference.client} n'est pas pertinent pour ce secteur et cette offre")
        }
        ReferenceLibrary.all
            .filter { it.key != input.referenceKey }
            .filter { ref -> ref.client.substringBefore(" (").lowercase().let { name -> Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(lower) } }
            .forEach {
                // "Routine" est aussi un mot courant : on alerte sans bloquer.
                val severity = if (it.key in ambiguousNames) Severity.WARN else Severity.BLOCK
                v += Violation("référence", severity, "${it.client} est cité sans être déclaré comme référence")
            }
        if (reference != null && !reference.detailsPublic && reference.proof.lowercase() in lower) {
            v += Violation("référence", Severity.WARN, "${reference.client} : vérifier que le détail est citable")
        }

        // 6. Délivrabilité
        if (input.channel == Channel.EMAIL && input.stepIndex == 0 && urlRegex.containsMatchIn(full)) {
            v += Violation("lien", Severity.BLOCK, "pas de lien dans le premier email (délivrabilité)")
        }
        urlRegex.findAll(full).map { it.value }.filterNot { "doppio-lab.com" in it }.forEach {
            v += Violation("lien", Severity.BLOCK, "lien externe non autorisé : $it")
        }

        // 7. Un message, une question
        if (input.channel == Channel.EMAIL && plain.count { it == '?' } == 0) {
            v += Violation("appel-à-l'action", Severity.WARN, "aucune question : terminer par une demande simple")
        }
        if (plain.count { it == '?' } > 2) {
            v += Violation("appel-à-l'action", Severity.WARN, "trop de questions, en garder une")
        }
        return v
    }

    fun isPublishable(violations: List<Violation>): Boolean = violations.none { it.severity == Severity.BLOCK }

    private fun String.normalizeNumber(): String = replace(Regex(numberSeparators), "")
}
