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
    /** Same normalization as the account name, otherwise "groupe open" or "creme de la creme" can never match. */
    private val normalizedNames: Set<String> by lazy { names.map { CompanyNames.tokens(it).joinToString(" ") }.toSet() }
    private val keywords = listOf(
        "consulting", "conseil", "esn", "ssii", "interim", "intérim", "recrutement", "recruitment", "staffing", "talent",
        "freelance", "portage", "agence digitale", "digital agency",
    )
    /** Keywords as token sequences, matched on whole words: "esn" must not exclude "Fresnel". */
    private val keywordTokens: List<Pair<String, List<String>>> by lazy {
        keywords.map { it to CompanyNames.tokens(it) }.filter { it.second.isNotEmpty() }
    }
    /** NAF : intérim et placement (78), conseil en gestion (70.22Z). */
    private val nafPrefixes = listOf("78.", "70.22")

    fun reason(name: String, naf: String? = null, extra: Set<String> = emptySet()): String? {
        val tokens = CompanyNames.tokens(name)
        val joined = tokens.joinToString(" ")
        (normalizedNames + extra.map { CompanyNames.tokens(it).joinToString(" ") }).firstOrNull { n ->
            // A short entry ("open" from "groupe open", "atos") only matches the whole name:
            // "Open Banking" is not Groupe Open. Longer entries also match as a prefix or a whole word.
            joined == n || n.length >= 5 && (joined.startsWith("$n ") || Regex("\\b${Regex.escape(n)}\\b").containsMatchIn(joined))
        }?.let { return ExclusionReasons.onList(it) }
        keywordTokens.firstOrNull { (_, k) -> tokens.windowed(k.size).any { it == k } }?.let { return ExclusionReasons.keyword(it.first) }
        if (naf != null && nafPrefixes.any { naf.startsWith(it) }) return ExclusionReasons.naf(naf)
        return null
    }

    /** Les ESN déclarent souvent 62.02A : on ne les exclut pas d'office, mais on le signale. */
    fun isPossibleServiceCompany(naf: String?): Boolean = naf == "62.02A" || naf == "62.02B"
}
