package lab.doppio.bloom.core.port

import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType

/** Sourced facts, the signals derived from them, and the researcher's dossier. */
interface FactRepository {
    suspend fun saveFacts(facts: List<Fact>)

    suspend fun factsOf(accountId: AccountId, maxAgeDays: Long = 120): List<Fact>

    suspend fun saveSignals(signals: List<Signal>)

    suspend fun signalsOf(accountId: AccountId, maxAgeDays: Long = 180): List<Signal>

    /** Signal le plus fort observé sur un compte : sert à mesurer ce qui convertit. */
    suspend fun dominantSignal(accountId: AccountId): SignalType?

    suspend fun saveDossier(d: Dossier)

    suspend fun dossier(accountId: AccountId): Dossier?
}
