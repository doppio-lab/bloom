package lab.doppio.bloom.core.discovery

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** TED gives multilingual values as {"fra": ["..."]} or {"fra": "..."}: the French one, else the first. */
private fun JsonElement?.french(): String? {
    val byLanguage = this as? JsonObject ?: return text()
    val value = byLanguage["fra"] ?: byLanguage.values.firstOrNull()
    return (value as? JsonArray)?.firstOrNull().text() ?: value.text()
}

object Ted {
    private const val NOTICE_PAGE_URL = "https://ted.europa.eu/fr/notice/-/detail"

    /** The search query: software CPV codes, performed in France, mentioning a mobile application. */
    fun query(sinceDay: LocalDate): String =
        "classification-cpv IN (72212000 72413000 72200000) AND place-of-performance = FRA AND FT ~ (\"application mobile\") " +
            "AND publication-date >= ${sinceDay.format(DateTimeFormatter.BASIC_ISO_DATE)}"

    val fields = listOf("notice-title", "buyer-name", "publication-date", "publication-number", "deadline-receipt-tender-date-lot", "notice-type")

    private fun day(value: String?): LocalDate? = value?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun parse(notice: JsonObject): Tender? {
        val id = notice["publication-number"].text() ?: return null
        // The title reads "France – <CPV label> – <subject>": only the subject is kept
        val title = notice["notice-title"].french() ?: return null
        val deadline = (notice["deadline-receipt-tender-date-lot"] as? JsonArray)?.mapNotNull { day(it.text()) }?.maxOrNull()
        return Tender(
            id = id,
            subject = title.split(" – ").last().trim(),
            buyer = notice["buyer-name"].french() ?: return null,
            publishedOn = day(notice["publication-date"].text())?.toString() ?: return null,
            // A deadline is a day: the tender stays open until the end of it
            deadline = deadline?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
            // "cn-..." is a contract notice (a call for tenders); "can-..." announces an award
            nature = if (notice["notice-type"].text()?.startsWith("cn-") == true) CALL_FOR_TENDERS else notice["notice-type"].text(),
            url = "$NOTICE_PAGE_URL/$id",
        )
    }
}
