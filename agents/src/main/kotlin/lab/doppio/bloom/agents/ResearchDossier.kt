package lab.doppio.bloom.agents

import lab.doppio.bloom.core.model.Offer

private val numberToken = Regex("\\d+")

/** Numbers written in [text] that no fact states. The dossier feeds the writer, so a made-up figure must not enter it. */
internal fun ungroundedNumbers(text: String, grounding: String): Set<String> {
    val known = numberToken.findAll(grounding).map { it.value }.toSet()
    return numberToken.findAll(text).map { it.value }.filter { it !in known }.toSet()
}

/** Drops the sentences that carry a number no fact states. */
internal fun withoutUngroundedSentences(summary: String, grounding: String): String =
    summary.split(Regex("(?<=[.!?])\\s+")).filter { ungroundedNumbers(it, grounding).isEmpty() }.joinToString(" ")

/**
 * Les hypothèses qui citent des faits inexistants sont écartées : pas d'hypothèse sans preuve.
 * Les identifiants retenus deviennent des marqueurs [F:id].
 */
internal fun provenHypotheses(hypotheses: List<ResearchAgent.Hypothesis>, knownFactIds: Set<String>): List<String> =
    hypotheses
        .map { h -> h.statement to h.factIds.map { normalizeFactId(it) } }
        .filter { (_, ids) -> ids.isNotEmpty() && ids.all { it in knownFactIds } }
        .map { (statement, ids) -> "$statement " + ids.joinToString(" ") { "[F:$it]" } }

private val factMarker = Regex("^\\[?F:(.*?)]?$", RegexOption.IGNORE_CASE)

/** "abc", "F:abc" and "[F:abc]" all name the fact abc: the model does not always echo identifiers verbatim. */
internal fun normalizeFactId(raw: String): String =
    raw.trim().let { factMarker.matchEntire(it)?.groupValues?.get(1) ?: it }.removeSuffix("]").trim()

/** Name or label first, then an unambiguous partial wording. Null means no fit (NONE, empty or ambiguous). */
internal fun parseOffer(raw: String): Offer? {
    val wanted = raw.trim().lowercase()
    if (wanted.isBlank() || wanted == "none") return null
    Offer.entries.firstOrNull { it.name.lowercase() == wanted || it.label.lowercase() == wanted }?.let { return it }
    val tokens = wanted.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return null
    return Offer.entries.singleOrNull { offer ->
        val haystack = (offer.name + " " + offer.label).lowercase()
        tokens.all { it in haystack }
    }
}
