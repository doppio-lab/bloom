package lab.doppio.bloom.core.discovery

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import java.net.URLEncoder
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

    fun domainOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').lowercase()
        return host.removePrefix("www.").takeIf { it.contains('.') && !it.endsWith("apple.com") && !it.endsWith("google.com") }
    }
}

/** Entreprises à ne jamais prospecter : ESN, cabinets de recrutement, agences concurrentes, plateformes de freelances. */
object Exclusions {
    private val names = setOf(
        "alten", "akkodis", "capgemini", "sopra steria", "cgi", "inetum", "accenture", "atos", "expleo", "altran", "talan",
        "onepoint", "devoteam", "sii", "aubay", "extia", "ausy", "modis", "hays", "randstad", "adecco", "manpower",
        "michael page", "robert half", "page personnel", "malt", "creme de la creme", "octo technology", "theodo", "bam",
        "ippon", "zenika", "wemanity", "xebia", "publicis sapient", "valtech", "fabernovel", "niji", "ekino", "apside",
        "scalian", "astek", "groupe open", "umanis", "keyrus", "davidson", "hardis", "neosoft", "amaris", "consort",
        "alteca", "cooptalis", "free work", "freework", "lincoln", "nexton", "smile", "wavestone", "doppio",
    )
    private val keywords = listOf(
        "consulting", "conseil", "esn", "ssii", "interim", "intérim", "recrutement", "recruitment", "staffing", "talent",
        "freelance", "portage", "agence digitale", "digital agency",
    )
    /** NAF : intérim et placement (78), conseil en gestion (70.22Z). */
    private val nafPrefixes = listOf("78.", "70.22")

    fun reason(name: String, naf: String? = null, extra: Set<String> = emptySet()): String? {
        val tokens = CompanyNames.tokens(name)
        val joined = tokens.joinToString(" ")
        (names + extra.map { CompanyNames.tokens(it).joinToString(" ") }).firstOrNull { n ->
            joined == n || joined.startsWith("$n ") || Regex("\\b${Regex.escape(n)}\\b").containsMatchIn(joined) && n.length >= 5
        }?.let { return "liste d'exclusion : $it" }
        val lower = name.lowercase()
        keywords.firstOrNull { it in lower }?.let { return "mot-clé : $it" }
        if (naf != null && nafPrefixes.any { naf.startsWith(it) }) return "code NAF $naf (intérim ou conseil)"
        return null
    }

    /** Les ESN déclarent souvent 62.02A : on ne les exclut pas d'office, mais on le signale. */
    fun isPossibleServiceCompany(naf: String?): Boolean = naf == "62.02A" || naf == "62.02B"
}

object SectorMapping {
    fun fromNaf(naf: String?): Sector {
        if (naf == null) return Sector.OTHER
        val div = naf.take(2).toIntOrNull() ?: return Sector.OTHER
        return when (div) {
            64, 65, 66 -> Sector.BANK_INSURANCE
            45, 46, 47 -> Sector.RETAIL
            52, 53 -> Sector.LOGISTICS
            49, 50, 51, 79 -> Sector.MOBILITY_TRANSPORT
            86, 87, 88, 21 -> Sector.HEALTH
            58, 59, 60, 90 -> Sector.MUSIC_MEDIA
            26, 27 -> Sector.IOT_HARDWARE
            62, 63 -> Sector.PRODUCTIVITY_SAAS
            94 -> Sector.CONSUMER_ASSOCIATION
            else -> Sector.OTHER
        }
    }

    /** Identifiants de genre App Store (https://developer.apple.com, genre IDs). */
    val genres: Map<Int, Pair<String, Sector>> = mapOf(
        6015 to ("Finance" to Sector.BANK_INSURANCE),
        6024 to ("Shopping" to Sector.RETAIL),
        6023 to ("Food & Drink" to Sector.RETAIL),
        6003 to ("Travel" to Sector.MOBILITY_TRANSPORT),
        6010 to ("Navigation" to Sector.MOBILITY_TRANSPORT),
        6013 to ("Health & Fitness" to Sector.HEALTH),
        6020 to ("Medical" to Sector.HEALTH),
        6000 to ("Business" to Sector.PRODUCTIVITY_SAAS),
        6007 to ("Productivity" to Sector.PRODUCTIVITY_SAAS),
        6011 to ("Music" to Sector.MUSIC_MEDIA),
        6016 to ("Entertainment" to Sector.MUSIC_MEDIA),
        6012 to ("Lifestyle" to Sector.CONSUMER_ASSOCIATION),
        6005 to ("Social Networking" to Sector.DATING_SOCIAL),
        6004 to ("Sports" to Sector.OTHER),
    )

    fun fromGenre(genreId: Int?): Sector = genreId?.let { genres[it]?.second } ?: Sector.OTHER
}

@Serializable
data class TargetPersona(val roles: List<String>, val why: String)

object Targeting {
    fun personaFor(offer: Offer): TargetPersona = when (offer) {
        Offer.DISCOVERY -> TargetPersona(listOf("Head of Mobile", "Lead mobile", "CTO"), "celui qui subit le problème technique de l'app")
        Offer.REGIE -> TargetPersona(listOf("Engineering Manager", "CTO", "Head of Mobile"), "celui qui porte le recrutement en retard")
        Offer.FORFAIT -> TargetPersona(listOf("CTO", "CPO", "CEO"), "celui qui arbitre le budget du nouveau produit")
        Offer.TRAINING_KMP -> TargetPersona(listOf("Head of Mobile", "Engineering Manager", "Lead Android"), "celui qui fait monter l'équipe en compétence")
        Offer.TRAINING_ANTHROPIC -> TargetPersona(listOf("CTO", "Head of AI", "Engineering Manager"), "celui qui pilote les projets LLM")
    }

    fun personaFromRole(role: String): lab.doppio.bloom.core.model.Persona {
        val r = role.lowercase()
        return when {
            Regex("\\bcto\\b|chief technology|directeur technique|vp eng").containsMatchIn(r) -> lab.doppio.bloom.core.model.Persona.CTO
            Regex("head of mobile|lead mobile|responsable mobile|mobile lead|lead (ios|android)").containsMatchIn(r) -> lab.doppio.bloom.core.model.Persona.HEAD_OF_MOBILE
            Regex("engineering manager|manager .*ing|responsable .*dév").containsMatchIn(r) -> lab.doppio.bloom.core.model.Persona.ENGINEERING_MANAGER
            Regex("\\bceo\\b|fondat|founder|président|directeur général").containsMatchIn(r) -> lab.doppio.bloom.core.model.Persona.CEO_FOUNDER
            Regex("\\bcpo\\b|product|produit").containsMatchIn(r) -> lab.doppio.bloom.core.model.Persona.PRODUCT_LEAD
            else -> lab.doppio.bloom.core.model.Persona.OTHER
        }
    }

    /** Recherche LinkedIn que la personne ouvre elle-même : aucun accès automatisé à LinkedIn. */
    fun linkedinSearchUrl(role: String, company: String): String =
        "https://www.linkedin.com/search/results/people/?keywords=" + URLEncoder.encode("$role $company", Charsets.UTF_8)
}

/** Lecture CSV tolérante : séparateur virgule ou point-virgule (Excel français), guillemets, en-tête obligatoire. */
object Csv {
    fun parse(text: String): List<Map<String, String>> {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull().orEmpty()
        val sep = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var quoted = false
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                quoted && c == '"' && i + 1 < clean.length && clean[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row += field.toString().trim(); field = StringBuilder() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < clean.length && clean[i + 1] == '\n') i++
                    row += field.toString().trim(); field = StringBuilder()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        row += field.toString().trim()
        if (row.any { it.isNotEmpty() }) rows += row
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.lowercase().trim() }
        return rows.drop(1).map { r -> header.mapIndexed { idx, h -> h to r.getOrElse(idx) { "" } }.toMap() }
    }
}
