package lab.doppio.bloom.core.model

import java.security.MessageDigest
import java.time.Instant

/** Stable short ids: the same statement from the same URL always gets the same fact id. */
object FactIds {
    fun of(sourceUrl: String, statement: String): String =
        MessageDigest.getInstance("SHA-1").digest("$sourceUrl|$statement".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
}

fun fact(accountId: AccountId, kind: FactKind, statement: String, sourceUrl: String, excerpt: String? = null, at: Instant = Instant.now()) =
    Fact(FactIds.of(sourceUrl, statement), accountId, kind, statement, sourceUrl, at.toString(), excerpt?.take(500))

data class AppMeta(val appId: Long, val name: String, val sellerName: String?, val sellerUrl: String?, val genreId: Int?)

/** What a fact source returns for one account: sourced facts and the signals computed from them. */
data class SourceResult(val facts: List<Fact>, val signals: List<Signal>, val app: AppMeta? = null)
