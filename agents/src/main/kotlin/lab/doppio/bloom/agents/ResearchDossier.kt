package lab.doppio.bloom.agents

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
        .filter { h -> h.factIds.isNotEmpty() && h.factIds.all { it in knownFactIds } }
        .map { h -> "${h.statement} " + h.factIds.joinToString(" ") { "[F:$it]" } }
