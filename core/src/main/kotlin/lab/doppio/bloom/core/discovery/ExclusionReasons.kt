package lab.doppio.bloom.core.discovery

/** Why a company is never prospected: the reason stored with the exclusion and shown in the blacklist. */
internal object ExclusionReasons {
    fun onList(entry: String) = "liste d'exclusion : $entry"
    fun keyword(keyword: String) = "mot-clé : $keyword"
    fun naf(code: String) = "code NAF $code (intérim ou conseil)"
}
