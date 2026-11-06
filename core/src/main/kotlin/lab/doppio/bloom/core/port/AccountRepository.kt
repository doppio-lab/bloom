package lab.doppio.bloom.core.port

import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.scoring.ScoreBreakdown

/** Accounts, their stored score, and the blacklist (excluded accounts and blocked names). */
interface AccountRepository {
    /** Création ou mise à jour (saisie manuelle, import) : les champs fournis écrasent les anciens. */
    suspend fun upsertAccount(a: Account): AccountId

    /**
     * Découverte : crée le compte s'il est nouveau, sinon complète seulement les champs vides.
     * Renvoie l'id et vrai si le compte vient d'être créé.
     */
    suspend fun mergeDiscovered(a: Account): Pair<AccountId, Boolean>

    suspend fun account(id: AccountId): Account?

    suspend fun accountsByScore(limit: Int = 200): List<AccountRow>

    /** Comptes intéressants (score >= 45) à qui il manque la personne à contacter. */
    suspend fun accountsToComplete(minScore: Int = 45, limit: Int = 30): List<AccountRow>

    suspend fun saveScore(s: ScoreBreakdown)

    /**
     * Puts an account on the blacklist. It disappears from every list, its running sequences stop and
     * its drafts waiting for review are rejected. Discovery keeps finding it by name and leaves it excluded.
     */
    suspend fun excludeAccount(id: AccountId, reason: String)

    /** Takes an account off the blacklist, and its name with it so discovery stops excluding it. Stopped sequences are not restarted. */
    suspend fun restoreAccount(id: AccountId)

    /** Blocks a name for discovery and excludes the account that already carries it. Returns that account, if any. */
    suspend fun blockName(name: String, reason: String?): AccountId?

    suspend fun unblockName(id: Long)

    suspend fun blockedNames(): Set<String>

    suspend fun blacklist(): List<Blocked>
}
