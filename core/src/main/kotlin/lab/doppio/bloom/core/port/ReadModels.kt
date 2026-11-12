package lab.doppio.bloom.core.port

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.appStoreUrl
import lab.doppio.bloom.core.model.ReplyIntent

/** One line of the blacklist: an excluded account (accountId set) or a name blocked before any account exists. */
@Serializable
data class Blocked(val id: Long, val accountId: AccountId? = null, val name: String, val reason: String? = null, val since: String? = null)

/** A person published as legal officer by the company registry. */
@Serializable
data class OfficerRow(val fullName: String, val role: String, val sourceUrl: String)

/** An account with its stored score, as the dashboard lists it. */
@Serializable
data class AccountRow(
    val account: Account, val score: Int, val fit: Int, val intent: Int, val warm: Int,
    val recommendedOffer: Offer?, val reasons: List<String>, val refreshedAt: String?,
    /** App Store page of the account's app, when it has one. */
    val appStoreUrl: String? = account.appStoreUrl,
)

@Serializable
data class ReplyRow(
    val id: Long, val enrollmentId: Long?, val from: String, val intent: ReplyIntent, val summary: String,
    val followUpOn: String?, val receivedAt: String, val handled: Boolean,
    /** Company of the contact who replied, when the reply belongs to a sequence. */
    val accountName: String? = null,
    val subject: String? = null,
)

/** Usage of one agent over a period. */
data class AgentStats(val agent: String, val runs: Int, val failures: Int, val inputTokens: Long, val outputTokens: Long, val avgMs: Long)
