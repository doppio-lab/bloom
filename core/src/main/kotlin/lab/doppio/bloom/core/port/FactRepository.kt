package lab.doppio.bloom.core.port

import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Retention
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import java.time.Instant

/** Sourced facts, the signals derived from them, and the researcher's dossier. */
interface FactRepository {
    suspend fun saveFacts(facts: List<Fact>)

    suspend fun factsOf(accountId: AccountId, maxAgeDays: Long = Retention.FACT_DAYS): List<Fact>

    suspend fun saveSignals(signals: List<Signal>)

    suspend fun signalsOf(accountId: AccountId, maxAgeDays: Long = Retention.SIGNAL_DAYS): List<Signal>

    /** Signals older than [Retention.SIGNAL_DAYS] are dead weight for the score. [now] defaults to the clock. */
    suspend fun purgeOldSignals(now: Instant? = null): Int

    /** Signal le plus fort observé sur un compte : sert à mesurer ce qui convertit. */
    suspend fun dominantSignal(accountId: AccountId): SignalType?

    suspend fun saveDossier(d: Dossier)

    suspend fun dossier(accountId: AccountId): Dossier?
}
