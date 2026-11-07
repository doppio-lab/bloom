package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.HttpClient
import lab.doppio.bloom.core.discovery.Decp
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.discovery.Lead
import lab.doppio.bloom.data.sources.http.RateLimiter
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** DECP : marchés publics d'app mobile déjà attribués, qui arrivent à échéance (open data, sans clé). */
/**
 * A buyer whose mobile app contract ends within a year will tender again: we reach it before BOAMP does.
 * The records give the buyer's SIRET but not its name, hence [buyerName] (the French registry).
 */
class DecpSource(
    private val http: HttpClient,
    private val buyerName: suspend (siren: String) -> String?,
    private val wasSeen: suspend (String) -> Boolean,
    private val markSeen: suspend (String, Boolean, String) -> Unit,
    private val maxMonths: Long = 12,
    private val lookbackYears: Long = 6,
    clock: Clock = Clock.systemDefaultZone(),
) : DiscoverySource {
    override val key = "decp"
    override val label = "marché attribué"
    private val limiter = RateLimiter(Duration.ofMillis(500), clock)

    override suspend fun leads(now: Instant): List<Lead> {
        val today = now.atZone(paris).toLocalDate()
        val where = "(search(objet,\"application mobile\") or search(objet,\"applications mobiles\") or search(objet,\"app mobile\")) " +
            "and datenotification>=date'${today.minusYears(lookbackYears)}'"
        // The same contracts are published in two datasets (2022 and 2024 formats): one lead per buyer and contract
        val contracts = DATASETS.flatMap { dataset ->
            buildList {
                for (page in 0 until 3) {
                    val batch = limiter.run { http.records("$BASE/$dataset/records", where, limit = 100, offset = page * 100, orderBy = "datenotification desc") }
                    addAll(batch.mapNotNull { Decp.parse(it)?.let { c -> dataset to c } })
                    if (batch.size < 100) break
                }
            }
        }.filter { (_, c) -> Decp.isRenewalCandidate(c, today, maxMonths = maxMonths) }.distinctBy { (_, c) -> c.buyerSiret to c.id }

        val leads = mutableListOf<Lead>()
        for ((dataset, c) in contracts) {
            val seenKey = "decp:${c.buyerSiret}:${c.id}"
            if (wasSeen(seenKey)) continue
            val name = buyerName(c.buyerSiren)
            if (name == null) { markSeen(seenKey, false, "acheteur absent de l'annuaire français"); continue }
            val url = "https://data.economie.gouv.fr/explore/dataset/$dataset/table/?q=${c.id}"
            leads += Lead(
                name = name,
                seenKey = seenKey,
                siren = c.buyerSiren,
                // Town halls and public bodies have no headcount band worth filtering on, as for BOAMP
                requireRegistry = false,
                build = { accountId, at ->
                    val f = fact(
                        accountId, FactKind.PUBLIC_CONTRACT,
                        "$name : marché d'application mobile notifié le ${c.notifiedOn.format(frDate)} pour ${c.durationMonths} mois, échéance le ${c.endsOn.format(frDate)}" +
                            (c.amountEur?.takeIf { it > 0 }?.let { ", montant ${euros(it)}" } ?: ""),
                        url,
                        excerpt = c.subject,
                        at = at,
                    )
                    // Observed now: the contract was checked as ending within the window, its weight decays from here
                    SourceResult(listOf(f), listOf(Signal(accountId, SignalType.CONTRACT_RENEWAL, 1.0, at.toString(), listOf(f.id))))
                },
            )
        }
        return leads
    }

    private companion object {
        const val BASE = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets"
        val DATASETS = listOf("decp-2022-marches-valides", "decp-v3-marches-valides")
    }
}
