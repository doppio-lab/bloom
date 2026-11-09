package lab.doppio.bloom.core.discovery

import lab.doppio.bloom.core.model.AppReview
import java.time.Instant

/** Reads app reviews: which low ratings describe a technical problem Doppio could audit. */
object ReviewRules {
    /** Only low ratings (this or below) count as complaints. */
    const val COMPLAINT_MAX_RATING = 2

    /**
     * What a low-rated review complains about, by keyword. [label] completes "N avis parlent de ...".
     * Whole words only: a plain substring test matched "lent" in "excellent" and "bug" in "debug".
     */
    enum class Theme(val label: String, words: String) {
        CRASH("plantages", "crash\\p{L}*|plante\\p{L}*|freeze|fige|figé|se ferme|écran blanc"),
        BUG("bugs", "bugs?|bugu\\p{L}*|beug\\p{L}*|erreurs?"),
        SLOW("lenteurs", "lent|lente|lents|lentes|lenteur|slow|chargement|charge non stop"),
        LOGIN("problèmes de connexion", "connexion|connecter|connect|reconnect\\p{L}*|déconnect\\p{L}*|login"),
        BROKEN(
            "fonctions inutilisables",
            "ne fonctionne (?:plus|pas)|ne marche (?:plus|pas)|fonctionne pas|marche pas|dysfonction\\p{L}*|inutilisable\\p{L}*|impossible|doesn't work",
        ),
        SYNC("la synchronisation", "synchronis\\p{L}*|sync"),
        UPDATE("la mise à jour", "mise à jour"),
        BATTERY("la batterie", "batterie"),
        ;

        internal val regex = Regex("(?<![\\p{L}\\p{N}])($words)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    /** The themes a review mentions, whatever its rating. */
    fun themes(title: String, content: String): Set<Theme> =
        "$title $content".let { text -> Theme.entries.filterTo(mutableSetOf()) { it.regex.containsMatchIn(text) } }

    fun isTechnicalComplaint(rating: Int, title: String, content: String): Boolean =
        rating <= COMPLAINT_MAX_RATING && themes(title, content).isNotEmpty()

    /**
     * The low-rated reviews worth quoting to the company: the ones that name a technical problem first, then the
     * most recent. A rating with no text says nothing and is left out.
     */
    fun mostTelling(reviews: List<AppReview>, limit: Int = 5): List<AppReview> =
        reviews.filter { it.rating <= COMPLAINT_MAX_RATING && it.content.isNotBlank() }
            .sortedWith(
                compareByDescending<AppReview> { themes(it.title, it.content).isNotEmpty() }
                    .thenByDescending { it.updated ?: Instant.MIN },
            )
            .take(limit)
}
