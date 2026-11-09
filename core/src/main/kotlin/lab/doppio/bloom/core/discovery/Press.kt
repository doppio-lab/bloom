package lab.doppio.bloom.core.discovery

import java.time.Instant

data class PressItem(val title: String, val link: String, val publishedAt: Instant?)

data class FundingHeadline(val company: String, val amount: String)

object Press {
    private val amount = "(\\d+(?:[.,]\\d+)?)\\s*(millions?|milliards?)\\s+d['’]euros"
    private val raises = Regex("^(.{2,40}?)\\s+(?:lève|boucle|sécurise|réalise)\\b.{0,60}?$amount", RegexOption.IGNORE_CASE)
    private val colonThenRaises = Regex("^(.{2,40}?)\\s*:\\s.*?\\blève\\b.{0,40}?$amount", RegexOption.IGNORE_CASE)

    fun parseFeed(xml: String): List<PressItem> = Rss.items(xml).map { PressItem(it.title, it.link, it.publishedAt) }

    /** Company and amount when the headline announces a funding round, null otherwise. Deterministic: headline only. */
    fun funding(title: String): FundingHeadline? {
        val m = raises.find(title) ?: colonThenRaises.find(title) ?: return null
        val company = m.groupValues[1].trim().trim('«', '»', '"', ' ')
        // "Les startups françaises ont levé..." style headlines are round-ups, not a company
        if (company.isEmpty() || company.first().isLowerCase() || company.split(' ').size > 4) return null
        return FundingHeadline(company, "${m.groupValues[2]} ${m.groupValues[3].lowercase()} d'euros")
    }
}
