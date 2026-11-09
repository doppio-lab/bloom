package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.discovery.Targeting
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Limits
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.currentReviewIssues
import lab.doppio.bloom.core.model.latestPerTopic
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.scoring.Scoring

/** Comptes qui valent le coup mais à qui il manque la personne à contacter. */
class ListToComplete(
    private val accountRepo: AccountRepository,
    private val factRepo: FactRepository,
) {
    suspend operator fun invoke(): List<ToComplete> =
        accountRepo.accountsToComplete().filter { factRepo.dossier(it.account.id)?.noFit != true }.map { row ->
            val all = factRepo.factsOf(row.account.id)
            val offer = factRepo.dossier(row.account.id)?.offer ?: row.recommendedOffer ?: Offer.DISCOVERY
            val persona = Targeting.personaFor(offer)
            ToComplete(
                row = row,
                offer = offer,
                roles = persona.roles,
                why = persona.why,
                searches = persona.roles.map { PeopleSearch(it, Targeting.linkedinSearchUrl(it, row.account.name)) },
                // The facts behind the strongest signals come first: they are why the account is in the list
                facts = factRepo.signalsOf(row.account.id)
                    .sortedByDescending { Scoring.rules.getValue(it.type).weight * it.strength }
                    .flatMap { it.factIds }.distinct().withIndex().associate { (rank, id) -> id to rank }
                    .let { rank ->
                        // Review themes have their own block (issues): they would only repeat the complaints fact here
                        val current = all.latestPerTopic().filter { it.kind != FactKind.APP_REVIEW_ISSUE && it.kind != FactKind.APP_REVIEW_QUOTE }.map { it.id }.toSet()
                        all.filter { it.id in current }.sortedBy { rank[it.id] ?: Int.MAX_VALUE }.take(Limits.FACTS_PER_ACCOUNT_TO_COMPLETE)
                    },
                issues = all.currentReviewIssues(),
            )
        }
}
