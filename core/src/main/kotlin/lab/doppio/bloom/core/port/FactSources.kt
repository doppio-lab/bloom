package lab.doppio.bloom.core.port

import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.SourceResult
import java.time.Instant

/** App Store facts and signals (rating, release age, review complaints) for a known app. */
interface AppStoreFacts {
    suspend fun analyze(accountId: AccountId, appId: Long, now: Instant): SourceResult
}

/** Open mobile job offers of a given company. */
interface JobOfferFacts {
    suspend fun mobileJobs(accountId: AccountId, companyName: String, now: Instant): SourceResult
}

/** Company registry facts (headcount range, NAF code, creation date) for a SIREN. */
interface CompanyRegistryFacts {
    suspend fun facts(accountId: AccountId, siren: String): List<Fact>
}

/** Reads the visible text of a public web page. The returned fact carries the exact URL read. */
interface WebPages {
    suspend fun read(accountId: AccountId, url: String, maxChars: Int = 6_000): Pair<Fact, String>?
}
