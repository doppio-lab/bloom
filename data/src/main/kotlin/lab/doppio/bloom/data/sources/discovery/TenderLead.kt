package lab.doppio.bloom.data.sources.discovery

import lab.doppio.bloom.core.discovery.Tender
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.data.discovery.Lead

/** A tender becomes a lead on its buyer. Shared by BOAMP and TED, which publish the same kind of notice. */
internal fun tenderLead(t: Tender, prefix: String) = Lead(
    name = t.buyer,
    seenKey = "$prefix:${t.id}",
    // Town halls and public bodies are not always matched by name in the registry
    requireRegistry = false,
    build = { accountId, at ->
        val deadline = t.deadline?.minusSeconds(1)?.atZone(paris)?.toLocalDate()?.format(frDate)
        val f = fact(
            accountId, FactKind.PUBLIC_TENDER,
            "${t.buyer} : appel d'offres publié le ${day(t.publishedOn)}" + (deadline?.let { ", réponse avant le $it" } ?: ""),
            t.url,
            excerpt = t.subject,
            at = at,
        )
        // Observed now, not at publication: the tender was checked as still open, so its weight should not have decayed already
        SourceResult(listOf(f), listOf(Signal(accountId, SignalType.PUBLIC_TENDER, 1.0, at.toString(), listOf(f.id))))
    },
)
