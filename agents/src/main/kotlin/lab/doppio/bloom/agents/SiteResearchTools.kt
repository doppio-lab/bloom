package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.WebPages
import java.time.Clock

/**
 * Outils exposés à l'agent d'exploration. L'agent lit des pages et propose des citations ;
 * le code vérifie qu'elles existent mot pour mot avant d'en faire des faits.
 */
@LLMDescription(SiteToolTexts.TOOLS)
class SiteResearchTools(private val accountId: Long, private val domain: String, private val web: WebPages, private val clock: Clock = Clock.systemDefaultZone()) : ToolSet {
    private val pages = mutableMapOf<String, String>()
    val recorded = mutableListOf<Fact>()
    val signals = mutableListOf<Signal>()
    private val categories = setOf("STACK", "MOBILE_PROJECT", "HIRING", "AI_USAGE", "APP_ISSUE")

    /** Curly quotes and non-breaking spaces differ between the page and what the model types back: compare without them. */
    private fun plain(text: String) = text
        .replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201C', '"').replace('\u201D', '"')
        .replace('\u00A0', ' ').replace('\u202F', ' ')
        .replace(Regex("\\s+"), " ")

    // https only: the researcher must not be sent to a plain-text page an attacker could tamper with
    internal val sameSite = Regex("^https://([a-z0-9-]+\\.)*${Regex.escape(domain)}(/.*)?$", RegexOption.IGNORE_CASE)

    @Tool
    @LLMDescription(SiteToolTexts.READ_PAGE)
    suspend fun readPage(
        @LLMDescription(SiteToolTexts.READ_PAGE_URL)
        url: String,
    ): String {
        if (!sameSite.matches(url)) return SiteToolTexts.offSite(domain)
        if (pages.size >= ResearchLimits.MAX_PAGES && url !in pages) return SiteToolTexts.pageLimitReached(ResearchLimits.MAX_PAGES)
        val (_, text) = web.read(accountId, url) ?: return SiteToolTexts.unreadablePage(url)
        pages[url] = text
        return text.take(ResearchLimits.PAGE_CHARS)
    }

    @Tool
    @LLMDescription(SiteToolTexts.RECORD_QUOTE)
    fun recordQuote(
        @LLMDescription(SiteToolTexts.RECORD_QUOTE_URL)
        url: String,
        @LLMDescription(SiteToolTexts.RECORD_QUOTE_QUOTE)
        quote: String,
        @LLMDescription(SiteToolTexts.RECORD_QUOTE_MEANING)
        meaning: String,
        @LLMDescription(SiteToolTexts.RECORD_QUOTE_CATEGORY)
        category: String,
    ): String {
        val page = pages[url] ?: return SiteToolTexts.READ_FIRST
        val normalized = quote.trim().replace(Regex("\\s+"), " ")
        if (normalized.length !in ResearchLimits.QUOTE_MIN_CHARS..ResearchLimits.QUOTE_MAX_CHARS) {
            return SiteToolTexts.quoteLength(ResearchLimits.QUOTE_MIN_CHARS, ResearchLimits.QUOTE_MAX_CHARS)
        }
        if (!plain(page).contains(plain(normalized), ignoreCase = true)) {
            return SiteToolTexts.QUOTE_NOT_FOUND
        }
        val cat = category.uppercase()
        if (cat !in categories) return SiteToolTexts.unknownCategory(categories)
        // The statement is the verbatim quote only: a figure the model adds in [meaning] must never become a
        // sourced fact for the writer and MessageGuard. The meaning stays in the conversation with the model.
        val f = fact(accountId, FactKind.WEB_PAGE, normalized, url, excerpt = normalized, at = clock.instant())
        recorded += f
        when (cat) {
            "AI_USAGE" -> signals += Signal(accountId, SignalType.AI_ADOPTION, ResearchLimits.AI_USAGE_STRENGTH, clock.instant().toString(), listOf(f.id))
            "MOBILE_PROJECT" -> signals += Signal(accountId, SignalType.PRODUCT_LAUNCH, ResearchLimits.MOBILE_PROJECT_STRENGTH, clock.instant().toString(), listOf(f.id))
            "APP_ISSUE" -> signals += Signal(accountId, SignalType.QUALITY_COMPLAINTS, ResearchLimits.APP_ISSUE_STRENGTH, clock.instant().toString(), listOf(f.id))
        }
        return SiteToolTexts.recorded(f.id, meaning.trim())
    }
}
