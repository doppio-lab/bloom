package lab.doppio.bloom.core.port

import java.time.Instant
import lab.doppio.bloom.core.model.AccountId

/** Dirigeants publiés par l'annuaire des entreprises. */
interface OfficerRepository {
    suspend fun saveOfficers(accountId: AccountId, officers: List<OfficerRow>)

    suspend fun officersByAccount(): Map<AccountId, List<OfficerRow>>

    /** Accounts with a SIREN whose officers were never read, best score first. */
    suspend fun accountsWithoutOfficers(limit: Int): List<Pair<AccountId, String>>

    /** RGPD : officer names read from the registry more than 3 years ago are deleted, like contacts. */
    suspend fun purgeStaleOfficers(now: Instant? = null): Int
}
