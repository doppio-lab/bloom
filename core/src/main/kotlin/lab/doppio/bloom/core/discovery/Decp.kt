package lab.doppio.bloom.core.discovery

import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import lab.doppio.bloom.core.model.CompanyIds

data class AwardedContract(
    val id: String,
    val subject: String,
    /** SIRET of the buyer. The records carry no buyer name. */
    val buyerSiret: String,
    val holderSiret: String?,
    val notifiedOn: LocalDate,
    val durationMonths: Int,
    val amountEur: Long?,
) {
    val buyerSiren: String get() = buyerSiret.take(CompanyIds.SIREN_DIGITS)
    val endsOn: LocalDate get() = notifiedOn.plusMonths(durationMonths.toLong())
}

object Decp {
    /** A contract ending sooner than this is too late to approach: the new tender is already written. */
    const val RENEWAL_MIN_MONTHS = 1L

    /** A buyer whose contract ends within a year will tender again. */
    const val RENEWAL_MAX_MONTHS = 12L

    private val mobileApp = Regex("(?i)\\b(appli(cations?)?|apps?)\\b.{0,25}\\b(mobiles?|smartphones?|ios|android)\\b")

    fun parse(record: JsonObject): AwardedContract? {
        val buyer = record["acheteur_id"].text()?.filter(Char::isDigit)?.takeIf { it.length == CompanyIds.SIRET_DIGITS } ?: return null
        return AwardedContract(
            id = record["id"].text() ?: return null,
            subject = record["objet"].text() ?: return null,
            buyerSiret = buyer,
            holderSiret = record["titulaire_id_1"].text()?.filter(Char::isDigit)?.takeIf { it.length == CompanyIds.SIRET_DIGITS },
            notifiedOn = record["datenotification"].text()?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null,
            durationMonths = record["dureemois"].text()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: return null,
            amountEur = record["montant"].text()?.toDoubleOrNull()?.toLong(),
        )
    }

    /**
     * A mobile app contract that ends between [minMonths] and [maxMonths] from [today]: the buyer has to
     * renew it, and usually prepares the new tender a few months before the end.
     */
    fun isRenewalCandidate(
        contract: AwardedContract,
        today: LocalDate,
        minMonths: Long = RENEWAL_MIN_MONTHS,
        maxMonths: Long = RENEWAL_MAX_MONTHS,
    ): Boolean =
        mobileApp.containsMatchIn(contract.subject) &&
            !contract.endsOn.isBefore(today.plusMonths(minMonths)) &&
            !contract.endsOn.isAfter(today.plusMonths(maxMonths))
}
