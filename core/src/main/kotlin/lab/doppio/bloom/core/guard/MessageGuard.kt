package lab.doppio.bloom.core.guard

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.Brand
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

    private val digitRun = Regex("""\d+""")

    /**
     * Numbers written as a single word, checked like digits. Left out on purpose: the words for the always
     * allowed numbers, "neuf" and "cent" (too often not a count), and compounds such as "quatre-vingt-dix".
     */
    private val numberWords = mapOf(
        "quatre" to "4", "cinq" to "5", "six" to "6", "sept" to "7", "huit" to "8", "dix" to "10", "onze" to "11",
        "douze" to "12", "treize" to "13", "quatorze" to "14", "seize" to "16", "quarante" to "40",
        "cinquante" to "50", "soixante" to "60",
        "four" to "4", "five" to "5", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
        "eleven" to "11", "twelve" to "12",
    )
    private val numberWordRegex = Regex("""(?<![\p{L}-])(?:${numberWords.keys.joinToString("|")})(?![\p{L}-])""")

    fun stripCitations(text: String): String =
        citationRegex.replace(text, "").replace(Regex(" {2,}"), " ").replace(Regex(" +([.,;:!?])"), "$1").trim()

    fun check(input: GuardInput): List<Violation> {
        val v = mutableListOf<Violation>()
        val plain = stripCitations(input.body)
        val lower = (input.subject.orEmpty() + "\n" + plain).lowercase()

        // 1. Typographie et ton
        if ('—' in lower || " – " in lower) {
            v += Violation("tiret-cadratin", Severity.BLOCK, GuardMessages.EM_DASH)
        }
        if (emojiRegex.containsMatchIn(lower)) v += Violation("emoji", Severity.BLOCK, GuardMessages.EMOJI)
        val banned = if (input.language == "en") bannedEn else bannedFr
        banned.filter { it in lower }.forEach { v += Violation("vocabulaire", Severity.BLOCK, GuardMessages.bannedPhrase(it)) }
        if (plain.count { it == '!' } > 0) v += Violation("exclamation", Severity.WARN, GuardMessages.EXCLAMATION)

        // 2. Longueur par canal et par étape
        val words = plain.split(Regex("\\s+")).count { it.isNotBlank() }
        when (input.channel) {
            Channel.EMAIL -> {
                val max = if (input.stepIndex == 0) GuardLimits.FIRST_EMAIL_WORDS else GuardLimits.FOLLOW_UP_WORDS
                if (words > max) v += Violation("longueur", Severity.BLOCK, GuardMessages.emailTooLong(words, max))
                val subjectWords = input.subject?.split(" ")?.count { it.isNotBlank() } ?: 0
                if (input.subject.isNullOrBlank()) v += Violation("objet", Severity.BLOCK, GuardMessages.SUBJECT_MISSING)
                if (subjectWords > GuardLimits.SUBJECT_WARN_ABOVE) {
                    v += Violation("objet", Severity.WARN, GuardMessages.subjectTooLong(subjectWords, GuardLimits.SUBJECT_MIN_WORDS, GuardLimits.SUBJECT_MAX_WORDS))
                }
                if (input.subject?.trim()?.lowercase()?.startsWith("re:") == true) {
                    v += Violation("objet", Severity.BLOCK, GuardMessages.FAKE_REPLY_PREFIX)
                }
            }
            Channel.LINKEDIN -> if (plain.length > GuardLimits.LINKEDIN_CHARS) {
                v += Violation("longueur", Severity.BLOCK, GuardMessages.linkedinTooLong(plain.length, GuardLimits.LINKEDIN_CHARS))
            }
            Channel.PHONE -> {}
        }

        // 3. Preuves : chaque citation doit exister, le premier message doit en avoir au moins une
        val factsById = input.availableFacts.associateBy { it.id }
        val markers = citationRegex.findAll(input.body).map { it.groupValues[1] }.toSet()
        val cited = markers + input.citedFactIds
        cited.filter { it !in factsById }.forEach {
            v += Violation("fait-inconnu", Severity.BLOCK, GuardMessages.unknownCitation(it))
        }
        if (input.stepIndex == 0 && input.channel == Channel.EMAIL && markers.isEmpty()) {
            v += Violation("sans-preuve", Severity.BLOCK, GuardMessages.NO_EVIDENCE)
        }

        // 4. Chiffres inventés : tout nombre doit figurer dans un fait cité ou dans la preuve de la référence
        val reference = input.referenceKey?.let { ReferenceLibrary.byKey(it) }
        val provenanceText = buildString {
            cited.mapNotNull { factsById[it] }.forEach { append(it.statement).append(' ').append(it.excerpt.orEmpty()).append(' ') }
            reference?.let { append(it.proof) }
            append(' ').append(input.offer.label)
        }
        // Whole numbers only: "40" is not sourced by a fact that says "1405". A number is known as written
        // ("5.2" as 52), as each of its digit runs (5 and 2), and without leading zeros (05 as 5).
        val knownNumbers = (digitRun.findAll(provenanceText.normalizeNumbers()) + digitRun.findAll(provenanceText))
            .flatMap { sequenceOf(it.value, it.value.trimStart('0')) }.toSet()
        val written = numberRegex.findAll(plain).map { it.value.normalizeNumber() } +
            numberWordRegex.findAll(plain.lowercase()).map { numberWords.getValue(it.value) }
        written.distinct()
            .filter { it !in GuardLimits.ALWAYS_ALLOWED_NUMBERS && it !in knownNumbers }
            .forEach { v += Violation("chiffre-non-sourcé", Severity.BLOCK, GuardMessages.unsourcedNumber(it)) }

        // 5. Références clients : seulement celles qui ont une raison d'être citées
        if (reference == null && input.referenceKey != null) {
            v += Violation("référence", Severity.BLOCK, GuardMessages.unknownReference(input.referenceKey))
        }
        if (reference != null && !ReferenceLibrary.isAllowed(reference.key, input.sector, input.offer)) {
            v += Violation("référence", Severity.BLOCK, GuardMessages.irrelevantReference(reference.client))
        }
        ReferenceLibrary.all
            .filter { it.key != input.referenceKey }
            .filter { ref -> ref.client.substringBefore(" (").lowercase().let { name -> Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(lower) } }
            .forEach {
                // "Routine" est aussi un mot courant : on alerte sans bloquer.
                val severity = if (it.key in ambiguousNames) Severity.WARN else Severity.BLOCK
                v += Violation("référence", severity, GuardMessages.undeclaredReference(it.client))
            }
        if (reference != null && !reference.detailsPublic && reference.proof.lowercase() in lower) {
            v += Violation("référence", Severity.WARN, GuardMessages.uncitableReferenceDetail(reference.client))
        }

        // 6. Délivrabilité
        if (input.channel == Channel.EMAIL && input.stepIndex == 0 && urlRegex.containsMatchIn(plain)) {
            v += Violation("lien", Severity.BLOCK, GuardMessages.NO_LINK_IN_FIRST_EMAIL)
        }
        urlRegex.findAll(plain).map { it.value }.filterNot { Brand.DOMAIN in it }.forEach {
            v += Violation("lien", Severity.BLOCK, GuardMessages.externalLink(it))
        }

        // 7. Un message, une question
        if (input.channel == Channel.EMAIL && plain.count { it == '?' } == 0) {
            v += Violation("appel-à-l'action", Severity.WARN, GuardMessages.NO_QUESTION)
        }
        if (plain.count { it == '?' } > GuardLimits.MAX_QUESTIONS) {
            v += Violation("appel-à-l'action", Severity.WARN, GuardMessages.TOO_MANY_QUESTIONS)
        }
        return v
    }

    fun isPublishable(violations: List<Violation>): Boolean = violations.none { it.severity == Severity.BLOCK }

    private fun String.normalizeNumber(): String = replace(Regex(numberSeparators), "")
    private fun String.normalizeNumbers(): String = numberRegex.replace(this) { it.value.normalizeNumber() }
}
