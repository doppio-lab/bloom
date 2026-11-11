package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.latestPerTopic
import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.Targeting
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.OfficerRepository
import lab.doppio.bloom.core.port.OfficerRow
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.core.scoring.Scoring

@Serializable data class PeopleSearch(val role: String, val url: String)
@Serializable data class ToComplete(
    val row: AccountRow, val offer: Offer, val roles: List<String>, val why: String,
    val searches: List<PeopleSearch>, val facts: List<Fact>,
)
@Serializable data class BookContact(val contact: Contact, /** "sequence", "email" (email known, no sequence) or "no-email". */ val status: String)
@Serializable data class BookEntry(
    val accountId: Long, val name: String, val score: Int, val source: String, val offer: Offer, val why: String,
    val contacts: List<BookContact>, val officers: List<OfficerRow>, val searches: List<PeopleSearch>,
)

/** Accounts above the display threshold, or that already have a contact, with the people known for each. */
class ContactBook(
    private val accountRepo: AccountRepository,
    private val contactRepo: ContactRepository,
    private val officerRepo: OfficerRepository,
    private val sequenceRepo: SequenceRepository,
    private val factRepo: FactRepository,
) {
    suspend operator fun invoke(): List<BookEntry> {
        val contacts = contactRepo.contactsByAccount()
        val officers = officerRepo.officersByAccount()
        val enrolled = sequenceRepo.activeEnrollments().map { it.contactId }.toSet()
        val entries = mutableListOf<BookEntry>()
        for (row in accountRepo.accountsByScore(500)) {
            if (entries.size == 150) break
            val id = row.account.id
            val dossier = factRepo.dossier(id)
            // An account the researcher found no offer for is left out, unless someone already put a contact on it
            if (id !in contacts && (row.score < 45 || dossier?.noFit == true)) continue
            val offer = dossier?.offer ?: row.recommendedOffer ?: Offer.DISCOVERY
            val persona = Targeting.personaFor(offer)
            val known = contacts[id].orEmpty()
            val knownNames = known.map { CompanyNames.normalize(it.fullName) }.toSet()
            entries += BookEntry(
                accountId = id, name = row.account.name, score = row.score, source = row.account.source, offer = offer, why = persona.why,
                contacts = known.map { c -> BookContact(c, if (c.id in enrolled) "sequence" else if (c.email != null) "email" else "no-email") },
                officers = officers[id].orEmpty().filter { CompanyNames.normalize(it.fullName) !in knownNames },
                searches = persona.roles.map { PeopleSearch(it, Targeting.linkedinSearchUrl(it, row.account.name)) },
            )
        }
        return entries
    }
}

/** Comptes qui valent le coup mais à qui il manque la personne à contacter. */
class ListToComplete(
    private val accountRepo: AccountRepository,
    private val factRepo: FactRepository,
) {
    suspend operator fun invoke(): List<ToComplete> =
        accountRepo.accountsToComplete().filter { factRepo.dossier(it.account.id)?.noFit != true }.map { row ->
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
                        val all = factRepo.factsOf(row.account.id)
                        val current = all.latestPerTopic().map { it.id }.toSet()
                        all.filter { it.id in current }.sortedBy { rank[it.id] ?: Int.MAX_VALUE }.take(3)
                    },
            )
        }
}
