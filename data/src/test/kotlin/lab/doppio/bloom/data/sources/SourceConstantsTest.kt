package lab.doppio.bloom.data.sources

import lab.doppio.bloom.core.discovery.Decp
import lab.doppio.bloom.core.discovery.HiringRules
import lab.doppio.bloom.core.model.BloomTime
import lab.doppio.bloom.core.model.Retention
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.sources.discovery.BodaccSource
import lab.doppio.bloom.data.sources.discovery.BoampSource
import lab.doppio.bloom.data.sources.discovery.DecpSource
import lab.doppio.bloom.data.sources.discovery.GitHubSource
import lab.doppio.bloom.data.sources.discovery.PressSource
import lab.doppio.bloom.data.sources.discovery.TedSource
import lab.doppio.bloom.data.sources.facts.AppStoreSource
import lab.doppio.bloom.data.sources.facts.FranceTravailSource
import lab.doppio.bloom.data.sources.facts.JobBoardSource
import lab.doppio.bloom.data.sources.http.REQUEST_TIMEOUT_MILLIS
import kotlin.test.Test
import kotlin.test.assertEquals

/** The tuning values of the sources and the retention windows keep the values they had as literals. */
class SourceConstantsTest {
    @Test
    fun `discovery windows`() {
        assertEquals(365L, DiscoverySource.DEFAULT_RECHECK_DAYS)
        assertEquals(365L, FranceTravailSource.RECHECK_AFTER_DAYS)
        assertEquals(90L, GitHubSource.RECHECK_AFTER_DAYS)
        assertEquals(30L, JobBoardSource.REFRESH_AFTER_DAYS)
        assertEquals(7L, BodaccSource.LOOKBACK_DAYS)
        assertEquals(45L, BoampSource.LOOKBACK_DAYS)
        assertEquals(60L, TedSource.LOOKBACK_DAYS)
        assertEquals(6L, DecpSource.LOOKBACK_YEARS)
        assertEquals(60L, GitHubSource.PUSHED_WITHIN_DAYS)
        assertEquals(14L, PressSource.MAX_AGE_DAYS)
        assertEquals(12L, Decp.RENEWAL_MAX_MONTHS)
        assertEquals(1L, Decp.RENEWAL_MIN_MONTHS)
    }

    @Test
    fun `signal strengths`() {
        assertEquals(0.7, HiringRules.MOBILE_STRENGTH)
        assertEquals(0.9, HiringRules.KMP_STRENGTH)
        assertEquals(0.8, GitHubSource.STRENGTH)
        assertEquals(0.8, PressSource.STRENGTH)
        assertEquals(45L, HiringRules.STUCK_AFTER_DAYS)
        assertEquals(0.3, HiringRules.stuckStrength(46))
        assertEquals(1.0, HiringRules.stuckStrength(105))
        assertEquals(0.5, HiringRules.stuckStrength(75))
        assertEquals(null, HiringRules.stuckStrength(45))
    }

    @Test
    fun `app store thresholds`() {
        assertEquals(90L, AppStoreSource.REVIEW_WINDOW_DAYS)
        assertEquals(120L, AppStoreSource.RELEASE_STALL_AFTER_DAYS)
        assertEquals(10, AppStoreSource.MIN_REVIEWS_FOR_RATING)
        assertEquals(0.4, AppStoreSource.RATING_DROP_MIN)
        assertEquals(5, AppStoreSource.MIN_REVIEWS_FOR_COMPLAINTS)
        assertEquals(0.15, AppStoreSource.COMPLAINT_SHARE_MIN)
    }

    @Test
    fun `bodacc defaults`() {
        assertEquals(100_000L, BodaccSource.DEFAULT_MIN_CAPITAL_EUR)
        assertEquals(10, BodaccSource.MIN_HEADCOUNT)
        assertEquals(5, BodaccSource.MAX_PAGES)
    }

    @Test
    fun `retention, send retry, timeout and time zone`() {
        assertEquals(3L * 365L, Retention.CONTACT_DAYS)
        assertEquals(90L, Retention.DEMAND_DAYS)
        assertEquals(120L, Retention.FACT_DAYS)
        assertEquals(180L, Retention.SIGNAL_DAYS)
        assertEquals(3, DraftRepository.MAX_SEND_ATTEMPTS)
        assertEquals(15L, DraftRepository.RETRY_STEP_MINUTES)
        assertEquals(20_000L, REQUEST_TIMEOUT_MILLIS)
        assertEquals("Europe/Paris", BloomTime.PARIS.id)
    }
}
