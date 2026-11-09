package lab.doppio.bloom.data.discovery

import java.time.Instant
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountDiscoveredPayload
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.scoring.Scoring
import lab.doppio.bloom.data.sources.facts.CompanyRegistrySource

/** What happened to one company or lead during a discovery run. */
internal enum class Outcome { Created, Refreshed, Excluded, Skipped }

/** The write path every discovery flow shares: merge the account, store its facts and score, log the discovery. */
internal class AccountIngest(
    private val accountRepo: AccountRepository,
    private val eventRepo: EventRepository,
    private val factRepo: FactRepository,
    private val registry: CompanyRegistrySource,
) {
    /**
     * Merges [account] into the known accounts, persists the [result] built for its id, and when the account is
     * new saves the registry facts of [siren] (if any) and logs [discovered]. Returns whether the account is new.
     */
    suspend fun mergeAndPersist(
        account: Account,
        siren: String?,
        discovered: AccountDiscoveredPayload,
        now: Instant,
        result: (accountId: Long) -> SourceResult,
    ): Boolean {
        val (accountId, isNew) = accountRepo.mergeDiscovered(account)
        persist(accountId, result(accountId), now)
        if (isNew) {
            siren?.let { factRepo.saveFacts(registry.facts(accountId, it)) }
            eventRepo.logEvent(EventType.ACCOUNT_DISCOVERED, accountId, payload = discovered.toJson())
        }
        return isNew
    }

    /** Enregistre faits et signaux, puis recalcule le score tout de suite (sans LLM). */
    suspend fun persist(accountId: Long, result: SourceResult, now: Instant) {
        factRepo.saveFacts(result.facts)
        factRepo.saveSignals(result.signals)
        val account = accountRepo.account(accountId) ?: return
        accountRepo.saveScore(Scoring.score(account, factRepo.signalsOf(accountId), now, factRepo.factsOf(accountId)))
    }
}
