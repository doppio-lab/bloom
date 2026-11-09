package lab.doppio.bloom.core.discovery

import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** The `nature` of a tender that is still a call for bids (BOAMP writes it this way, TED notices are mapped to it). */
internal const val CALL_FOR_TENDERS = "APPEL_OFFRE"

data class Tender(
    val id: String,
    val subject: String,
    val buyer: String,
    val publishedOn: String,
    val deadline: Instant?,
    val nature: String?,
    val url: String,
)

object Boamp {
    private const val NOTICE_PAGE_URL = "https://www.boamp.fr/pages/avis/?q=idweb:"

    private val mobileApp = Regex("(?i)\\bappli(cations?)?\\b.{0,25}\\b(mobiles?|smartphones?|ios|android)\\b|\\bd[ée]veloppement d'une appli")

    fun parse(record: JsonObject): Tender? {
        val id = record["idweb"].text() ?: return null
        return Tender(
            id = id,
            subject = record["objet"].text() ?: return null,
            buyer = record["nomacheteur"].text() ?: return null,
            publishedOn = record["dateparution"].text() ?: return null,
            deadline = instant(record["datelimitereponse"].text()),
            nature = record["nature"].text(),
            url = record["url_avis"].text() ?: "$NOTICE_PAGE_URL$id",
        )
    }

    /** A call for tenders (not an award notice) still open, whose subject asks for a mobile app. */
    fun isOpenMobileTender(tender: Tender, now: Instant): Boolean =
        tender.nature == CALL_FOR_TENDERS && tender.deadline?.isAfter(now) == true && mobileApp.containsMatchIn(tender.subject)
}
