package lab.doppio.bloom.core.discovery

import java.text.Normalizer

/**
 * Règles pures de la découverte de prospects : qui garder, qui écarter, comment reconnaître
 * une même entreprise sous deux noms, qui contacter. Aucune entrée/sortie, tout est testé.
 */
object CompanyNames {
    private val legalForms = setOf(
        "sa", "sas", "sasu", "sarl", "eurl", "sca", "snc", "se", "scop", "gie", "inc", "ltd", "llc", "gmbh", "bv", "ag",
        "spa", "srl", "limited", "corp", "corporation", "co", "company", "plc", "holding", "group", "groupe", "france",
        "the", "et", "and", "de", "du", "des", "la", "le", "les",
    )

    /** "Vélomoov SAS" et "VELOMOOV" donnent tous deux "velomoov". */
    fun normalize(name: String): String = tokens(name).joinToString("-")

    fun tokens(name: String): List<String> =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it !in legalForms }

    /** Même entreprise ? Jaccard des mots >= 0,6, ou un nom contenu dans l'autre (4 lettres minimum). */
    fun sameCompany(a: String, b: String): Boolean {
        val ta = tokens(a).toSet()
        val tb = tokens(b).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        val jaccard = (ta intersect tb).size.toDouble() / (ta union tb).size
        if (jaccard >= 0.6) return true
        val ja = ta.joinToString("")
        val jb = tb.joinToString("")
        val shorter = if (ja.length <= jb.length) ja else jb
        val longer = if (shorter === ja) jb else ja
        return shorter.length >= 4 && longer.startsWith(shorter)
    }

    /**
     * Queries to try on the company registry, most exact first. The registry finds nothing for
     * "Finary SAS" or "Bankin' SAS", and a publisher such as "E.Leclerc - Infomil" names two companies.
     */
    fun registryQueries(name: String): List<String> {
        val parts = name.split(Regex("\\s+[-–|/]\\s+")).map { it.trim() }.filter { it.length >= 3 }
        return (listOf(name, tokens(name).joinToString(" ")) + parts.reversed() + parts.reversed().map { tokens(it).joinToString(" ") })
            .map { it.trim() }.filter { it.length >= 3 }.distinct()
    }

    fun domainOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').lowercase()
        return host.removePrefix("www.").takeIf { it.contains('.') && !it.endsWith("apple.com") && !it.endsWith("google.com") }
    }
}
