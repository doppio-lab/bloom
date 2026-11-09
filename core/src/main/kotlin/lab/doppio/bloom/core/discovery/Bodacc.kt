package lab.doppio.bloom.core.discovery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import lab.doppio.bloom.core.model.CompanyIds

data class CapitalNotice(
    val id: String,
    val siren: String,
    val name: String,
    val legalForm: String?,
    /** The capital after the change. The notice does not give the amount raised. */
    val capitalEur: Long?,
    val publishedOn: String,
    val description: String,
    val url: String,
)

object Bodacc {
    private const val NOTICE_PAGE_URL = "https://www.bodacc.fr/pages/annonces-commerciales-detail/?q.id=id:"

    private val nonTargetForms = Regex("(?i)soci[ée]t[ée] civile|agricole|groupement|\\bsci\\b|\\bscea\\b|\\bgaec\\b|\\bearl\\b")

    fun parse(record: JsonObject): CapitalNotice? {
        val id = record["id"].text() ?: return null
        val siren = (record["registre"] as? JsonArray)?.firstNotNullOfOrNull { it.text()?.filter(Char::isDigit)?.takeIf { d -> d.length == CompanyIds.SIREN_DIGITS } } ?: return null
        val people = record["listepersonnes"].embedded()?.get("personne")
        val person = people.obj() ?: (people as? JsonArray)?.firstOrNull().obj()
        return CapitalNotice(
            id = id,
            siren = siren,
            name = record["commercant"].text() ?: person?.get("denomination").text() ?: return null,
            legalForm = person?.get("formeJuridique").text(),
            capitalEur = person?.get("capital").obj()?.get("montantCapital").text()?.substringBefore('.')?.filter(Char::isDigit)?.toLongOrNull(),
            publishedOn = record["dateparution"].text() ?: return null,
            description = record["modificationsgenerales"].embedded()?.get("descriptif").text().orEmpty(),
            url = record["url_complete"].text() ?: "$NOTICE_PAGE_URL$id",
        )
    }

    /** Cheap filter applied before any lookup: most capital increases come from holdings, farms and property companies. */
    fun isCandidate(notice: CapitalNotice, minCapitalEur: Long): Boolean =
        notice.description.contains("augmentation", ignoreCase = true) &&
            (notice.capitalEur ?: 0) >= minCapitalEur &&
            notice.legalForm?.let { nonTargetForms.containsMatchIn(it) } != true
}
