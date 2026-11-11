package lab.doppio.bloom.core.golden

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.discovery.HiringRules
import lab.doppio.bloom.core.discovery.ReviewRules
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.scoring.ScoreBreakdown
import lab.doppio.bloom.core.scoring.Scoring
import lab.doppio.bloom.core.usecase.ContactBook
import lab.doppio.bloom.core.usecase.FakeAccountRepository
import lab.doppio.bloom.core.usecase.FakeContactRepository
import lab.doppio.bloom.core.usecase.FakeOfficerRepository
import lab.doppio.bloom.core.usecase.FakeResearcher
import lab.doppio.bloom.core.usecase.FakeSequenceRepository
import lab.doppio.bloom.core.usecase.ResearchBatch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the golden cases of [GoldenCases.kt] through the real rules. Failing messages name the case and say why it exists.
 * The builders below mirror what the sources do with a posting or a set of reviews, using the same rule objects.
 */
class GoldenSetTest {
    private val now = Instant.parse("2026-10-12T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `job titles surface only when they describe building a native or KMP mobile app`() {
        titleCases.forEach { assertEquals(it.surface, HiringRules.isMobile(it.title), "${it.title} (${it.why})") }
    }

    @Test
    fun `reviews surface only when a low rating describes a technical problem`() {
        reviewCases.forEach { assertEquals(it.surface, ReviewRules.isTechnicalComplaint(it.rating, it.title, it.content), "${it.title} (${it.why})") }
    }

    @Test
    fun `every account case lands in its tier and surfaces or not as labelled`() {
        scored.forEach { (case, s) ->
            // An account surfaces when its score reaches 45 and no dossier says "no fit" (a filter, not a score term)
            assertEquals(case.surface, s.total >= 45 && case.dossier?.noFit != true, "${case.name}: ${s.total} ${s.reasons} (${case.why})")
            case.tier?.let { assertEquals(it, Scoring.tier(s.total), "${case.name}: ${s.total} ${s.reasons} (${case.why})") }
        }
    }

    @Test
    fun `relative order holds`() {
        orderCases.forEach { (above, below) ->
            val a = scoreOf(above).total; val b = scoreOf(below).total
            assertTrue(a > b, "$above ($a) must rank above $below ($b)")
        }
    }

    @Test
    fun `the research queue and the contact book hold exactly the accounts that surface`() = runTest {
        val repo = FakeAccountRepository(rows)
        val researcher = FakeResearcher()
        ResearchBatch(repo, factRepo, researcher, clock)()
        val expected = accountCases.filter { it.surface }.map { it.account.id }
        assertEquals(expected.toSet(), researcher.researched.toSet(), "researched")
        // Without dossier first, each group by descending score
        val scoreById = rows.associate { it.account.id to it.score }
        val withoutDossier = researcher.researched.filter { factRepo.dossier(it) == null }
        assertEquals(withoutDossier.sortedByDescending { scoreById.getValue(it) }, withoutDossier, "research order")

        val book = ContactBook(repo, FakeContactRepository(), FakeOfficerRepository(), FakeSequenceRepository(), factRepo)().map { it.accountId }
        assertEquals(expected.toSet(), book.toSet(), "contact book")
    }

    @Test
    fun `a cross-platform stack lowers fit by 20 with a sourced reason, a migration away from it does not`() {
        val flutter = scoreOf("Flutter stack stated in a native posting")
        assertEquals(20, flutter.fit)
        assertTrue(flutter.reasons.any { it.startsWith("-20 stack cross-platform") && "[F:" in it }, flutter.reasons.toString())
        val migration = scoreOf("Native iOS hiring, migration away from Flutter")
        assertEquals(40, migration.fit)
        assertTrue(migration.reasons.none { "cross-platform" in it })
    }

    @Test
    fun `a no fit dossier never changes the score, which stays the sum of its three parts`() {
        val noFit = scoreOf("High score but dossier says no fit")
        val lifted = scoreOf("Same account, later dossier lifts the no fit")
        assertEquals(lifted.total, noFit.total)
        assertEquals(noFit.fit + noFit.intent + noFit.warm, noFit.total)
        assertTrue(noFit.reasons.none { "dossier" in it }, noFit.reasons.toString())
    }

    @Test
    fun `the direct reference bonus needs a product of its own in the services sector`() {
        val esn = scoreOf("ESN without product")
        val publisher = scoreOf("Software publisher with an app")
        assertTrue(esn.reasons.none { "référence directe" in it }, esn.reasons.toString())
        assertTrue(publisher.reasons.any { "référence directe" in it }, publisher.reasons.toString())
    }

    @Test
    fun `every score explains itself and the set holds both labels`() {
        scored.forEach { (case, s) -> assertTrue(s.reasons.isNotEmpty(), case.name) }
        assertTrue(accountCases.any { it.surface } && accountCases.any { !it.surface })
    }

    // --- builders ---------------------------------------------------------------------------------------------------

    private class GoldenFacts(
        val facts: Map<AccountId, List<Fact>>, val signals: Map<AccountId, List<Signal>>, val dossiers: Map<AccountId, Dossier>,
    ) : FactRepository {
        override suspend fun saveFacts(facts: List<Fact>) {}
        override suspend fun factsOf(accountId: AccountId, maxAgeDays: Long) = facts[accountId].orEmpty()
        override suspend fun saveSignals(signals: List<Signal>) {}
        override suspend fun signalsOf(accountId: AccountId, maxAgeDays: Long) = signals[accountId].orEmpty()
        override suspend fun dominantSignal(accountId: AccountId): SignalType? = null
        override suspend fun saveDossier(d: Dossier) {}
        override suspend fun dossier(accountId: AccountId) = dossiers[accountId]
    }

    private val built: Map<AccountId, Pair<List<Fact>, List<Signal>>> = accountCases.associate { it.account.id to build(it) }

    private val factRepo = GoldenFacts(
        facts = built.mapValues { it.value.first },
        signals = built.mapValues { it.value.second },
        dossiers = accountCases.mapNotNull { c -> c.dossier?.let { c.account.id to it } }.toMap(),
    )

    private val scored: List<Pair<AccountCase, ScoreBreakdown>> = accountCases.map { c ->
        val (facts, signals) = built.getValue(c.account.id)
        c to Scoring.score(c.account, signals, now, facts)
    }

    private val rows: List<AccountRow> = scored
        .map { (c, s) ->
            AccountRow(c.account, s.total, s.fit, s.intent, s.warm, s.recommendedOffer, s.reasons, now.minus(Duration.ofDays(30)).toString())
        }
        .sortedByDescending { it.score }

    private fun scoreOf(name: String): ScoreBreakdown = scored.single { it.first.name == name }.second

    private fun build(c: AccountCase): Pair<List<Fact>, List<Signal>> {
        val id = c.account.id
        val facts = mutableListOf<Fact>()
        val signals = mutableListOf<Signal>()

        // Same steps as FranceTravailSource: only mobile titles make facts and signals
        c.postings.filter { HiringRules.isMobile(it.title) }.forEach { p ->
            val url = "https://offres.example/${id}/${p.title.hashCode()}"
            val f = fact(id, FactKind.JOB_POSTING, "Offre d'emploi « ${p.title} »" + (p.ageDays?.let { " publiée il y a $it jours" } ?: ""), url, at = now)
            facts += f
            val observed = HiringRules.observedAt(p.ageDays?.let { now.minus(Duration.ofDays(it.toLong())) }, now)
            signals += Signal(id, SignalType.HIRING_MOBILE, 0.7, observed.toString(), listOf(f.id))
            if (HiringRules.mentionsKmp(p.title + p.text)) signals += Signal(id, SignalType.HIRING_KMP, 0.9, observed.toString(), listOf(f.id))
            HiringRules.stuckStrength(p.ageDays?.toLong())?.let { signals += Signal(id, SignalType.HIRING_STUCK, it, now.toString(), listOf(f.id)) }
            if (HiringRules.indicatesCrossPlatformStack(p.title + " " + p.text)) {
                facts += fact(id, FactKind.JOB_POSTING, HiringRules.crossPlatformStatement(p.title), url, at = now)
            }
        }

        // Same steps as AppStoreSource: rating drop from 10 recent reviews, complaints from 5
        val recent = c.reviews.filter { it.ageDays <= 90 }
        val rssUrl = "https://itunes.example/reviews/$id"
        if (recent.size >= 10 && c.overallRating != null) {
            val drop = c.overallRating - recent.map { it.rating }.average()
            val f = fact(id, FactKind.APP_REVIEW, "Moyenne des ${recent.size} avis récents sous la moyenne globale", rssUrl, at = now)
            facts += f
            if (drop >= 0.4) signals += Signal(id, SignalType.RATING_DROP, (drop / 1.5).coerceIn(0.2, 1.0), now.toString(), listOf(f.id))
        }
        val complaints = recent.filter { ReviewRules.isTechnicalComplaint(it.rating, it.title, it.content) }
        if (recent.size >= 5 && complaints.isNotEmpty()) {
            val share = complaints.size.toDouble() / recent.size
            val f = fact(id, FactKind.APP_REVIEW, "${complaints.size} avis sur ${recent.size} signalent un problème technique", rssUrl, at = now)
            facts += f
            if (share >= 0.15) signals += Signal(id, SignalType.QUALITY_COMPLAINTS, (share * 2).coerceIn(0.2, 1.0), now.toString(), listOf(f.id))
        }

        c.publicTenderDaysAgo?.let {
            val f = fact(id, FactKind.PUBLIC_TENDER, "Appel d'offres : application mobile", "https://boamp.example/$id", at = now)
            facts += f
            signals += Signal(id, SignalType.PUBLIC_TENDER, 0.9, now.minus(Duration.ofDays(it.toLong())).toString(), listOf(f.id))
        }
        return facts to signals
    }
}
