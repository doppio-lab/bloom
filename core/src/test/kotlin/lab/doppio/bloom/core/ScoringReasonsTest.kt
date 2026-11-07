package lab.doppio.bloom.core

import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.scoring.Scoring
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins every fit reason, every fit score and the tier boundaries, so the named constants cannot drift. */
class ScoringReasonsTest {
    private fun fit(account: Account): Pair<Int, List<String>> {
        val reasons = mutableListOf<String>()
        return Scoring.fit(account, reasons) to reasons
    }

    private val base = Account(name = "X", market = Market.US, sector = Sector.OTHER)

    @Test
    fun `fit reasons and points are the documented ones`() {
        assertEquals(0 to emptyList(), fit(base))
        assertEquals(12 to listOf("+12 app mobile en production"), fit(base.copy(iosAppId = 1)))
        assertEquals(
            16 to listOf("+12 app mobile en production", "+4 présent sur les deux stores (terrain KMP)"),
            fit(base.copy(iosAppId = 1, androidPackage = "a.b")),
        )
        assertEquals(12 to listOf("+12 app mobile en production"), fit(base.copy(androidPackage = "a.b")))
        assertEquals(2 to listOf("+2 moins de 15 personnes (budget incertain)"), fit(base.copy(headcount = 0)))
        assertEquals(2 to listOf("+2 moins de 15 personnes (budget incertain)"), fit(base.copy(headcount = 14)))
        assertEquals(8 to listOf("+8 15 à 49 personnes"), fit(base.copy(headcount = 15)))
        assertEquals(8 to listOf("+8 15 à 49 personnes"), fit(base.copy(headcount = 49)))
        assertEquals(12 to listOf("+12 50 à 499 personnes (cœur de cible)"), fit(base.copy(headcount = 50)))
        assertEquals(12 to listOf("+12 50 à 499 personnes (cœur de cible)"), fit(base.copy(headcount = 499)))
        assertEquals(9 to listOf("+9 500+ personnes (cycle long, achats)"), fit(base.copy(headcount = 500)))
        assertEquals(8 to listOf("+8 secteur où Doppio a une référence directe"), fit(base.copy(sector = Sector.RETAIL)))
        assertEquals(4 to listOf("+4 marché France"), fit(base.copy(market = Market.FR)))
        assertEquals(2 to listOf("+2 marché Europe"), fit(base.copy(market = Market.EU)))
        assertEquals(2 to listOf("+2 marché Europe"), fit(base.copy(market = Market.UK)))
    }

    @Test
    fun `fit is capped at 40`() {
        val (points, reasons) = fit(
            base.copy(iosAppId = 1, androidPackage = "a.b", headcount = 100, sector = Sector.BANK_INSURANCE, market = Market.FR),
        )
        assertEquals(40, points)
        assertEquals(
            listOf(
                "+12 app mobile en production",
                "+4 présent sur les deux stores (terrain KMP)",
                "+12 50 à 499 personnes (cœur de cible)",
                "+8 secteur où Doppio a une référence directe",
                "+4 marché France",
            ),
            reasons,
        )
    }

    @Test
    fun `score reasons list the signals then the warm path`() {
        val now = Instant.parse("2026-10-12T08:00:00Z")
        val account = base.copy(market = Market.FR, warmPath = "ancien client")
        val signals = listOf(
            Signal(0, SignalType.PUBLIC_TENDER, 1.0, now.toString(), emptyList()),
            Signal(0, SignalType.AI_ADOPTION, 0.05, now.toString(), emptyList()),
            Signal(0, SignalType.HIRING_STUCK, 1.0, now.toString(), emptyList()),
        )
        val s = Scoring.score(account, signals, now)
        assertEquals(4, s.fit)
        assertEquals(50, s.intent)
        assertEquals(10, s.warm)
        assertEquals(64, s.total)
        assertEquals(
            listOf("+4 marché France", "+45 signal public_tender", "+22 signal hiring_stuck", "+10 lien chaud : ancien client"),
            s.reasons,
        )
    }

    @Test
    fun `tier boundaries`() {
        assertEquals(Scoring.Tier.ACT_NOW, Scoring.tier(65))
        assertEquals(Scoring.Tier.NURTURE, Scoring.tier(64))
        assertEquals(Scoring.Tier.NURTURE, Scoring.tier(45))
        assertEquals(Scoring.Tier.WATCH, Scoring.tier(44))
    }
}
