package lab.doppio.bloom.core.discovery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.w3c.dom.Element
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parsers for the sources where a buyer states its need in public: European tenders (TED) and
 * projects posted on Codeur.com. Pure functions, tested on recorded responses.
 */

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

/** TED gives multilingual values as {"fra": ["..."]} or {"fra": "..."}: the French one, else the first. */
private fun JsonElement?.french(): String? {
    val byLanguage = this as? JsonObject ?: return text()
    val value = byLanguage["fra"] ?: byLanguage.values.firstOrNull()
    return (value as? JsonArray)?.firstOrNull().text() ?: value.text()
}

// --- RSS: shared by the press feeds and the Codeur project feed ----------------------------------------

data class RssItem(val title: String, val link: String, val publishedAt: Instant?, val description: String?, val guid: String?)

object Rss {
    fun items(xml: String): List<RssItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The feed is untrusted input: no DTD, no external entity
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as? Element ?: return@mapNotNull null
            fun child(tag: String) = item.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            RssItem(
                title = child("title") ?: return@mapNotNull null,
                link = child("link") ?: return@mapNotNull null,
                publishedAt = child("pubDate")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() },
                description = child("description"),
                guid = child("guid"),
            )
        }
    }
}

// --- TED: European public tenders --------------------------------------------------------------------

object Ted {
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
            nature = if (notice["notice-type"].text()?.startsWith("cn-") == true) "APPEL_OFFRE" else notice["notice-type"].text(),
            url = "https://ted.europa.eu/fr/notice/-/detail/$id",
        )
    }
}

// --- Codeur.com: projects published by companies looking for a provider --------------------------------

data class CodeurProject(
    val id: String,
    val title: String,
    val url: String,
    val publishedAt: Instant?,
    /** Budget band as written by Codeur ("1 000 € à 10 000 €"). */
    val budget: String?,
    val categories: List<String>,
)

object Codeur {
    private val header = Regex("Budget\\s*:\\s*(.+?)\\s+-\\s+Cat[ée]gories?\\s*:\\s*(.+?)\\s*</p>", RegexOption.IGNORE_CASE)
    private val mobile = Regex("(?i)appli(cation)?s?\\s+(mobiles?|ios|android|smartphone)|\\bapp\\s+mobile|\\b(ios|android|swift|kotlin|flutter|react native)\\b")
    private val amount = Regex("(\\d[\\d\\s\\u00a0\\u202f]*)\\s*€")

    /** The feed gives the budget band and the categories in the first paragraph of the description. */
    fun parseFeed(xml: String): List<CodeurProject> = Rss.items(xml).map { item ->
        val head = item.description?.let { header.find(it) }
        CodeurProject(
            id = item.guid ?: item.link.substringAfterLast('/').substringBefore('-'),
            title = item.title,
            url = item.link,
            publishedAt = item.publishedAt,
            budget = head?.groupValues?.get(1)?.trim(),
            categories = head?.groupValues?.get(2)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        )
    }

    /** Lowest amount of the budget band, in euros: "10 000 € et plus" gives 10000, "Moins de 500 €" gives 0. */
    fun budgetFloor(budget: String?): Int? {
        if (budget == null) return null
        if (budget.contains("moins de", ignoreCase = true)) return 0
        return amount.find(budget)?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull()
    }

    /** A project that asks for a mobile app, judged on its title and categories, with a budget worth an answer. */
    fun isMobileProject(project: CodeurProject, minBudgetEur: Int): Boolean =
        mobile.containsMatchIn(project.title + " " + project.categories.joinToString(" ")) && (budgetFloor(project.budget) ?: 0) >= minBudgetEur
}
