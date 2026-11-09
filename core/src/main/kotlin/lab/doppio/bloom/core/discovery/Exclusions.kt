package lab.doppio.bloom.core.discovery

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
        }?.let { return ExclusionReasons.onList(it) }
        val lower = name.lowercase()
        keywords.firstOrNull { it in lower }?.let { return ExclusionReasons.keyword(it) }
        if (naf != null && nafPrefixes.any { naf.startsWith(it) }) return ExclusionReasons.naf(naf)
        return null
    }

    /** Les ESN déclarent souvent 62.02A : on ne les exclut pas d'office, mais on le signale. */
    fun isPossibleServiceCompany(naf: String?): Boolean = naf == "62.02A" || naf == "62.02B"
}
