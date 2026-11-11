package lab.doppio.bloom.core.usecase

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.Csv
import lab.doppio.bloom.core.discovery.Targeting
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.scoring.Scoring
import java.time.Clock

@Serializable data class ImportReport(val created: Int, val updated: Int, val contacts: Int, val errors: List<String>)

/**
 * Import de ta propre liste (réseau, anciens clients, salons). CSV avec en-tête, virgule ou point-virgule :
 * name, domain, siren, ios_app_id, sector, headcount, warm_path, contact_name, contact_role, contact_email, contact_linkedin
 */
class ImportAccounts(
    private val accountRepo: AccountRepository,
    private val factRepo: FactRepository,
    private val contactRepo: ContactRepository,
    private val replyRepo: ReplyRepository,
    private val eventRepo: EventRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(csv: String): ImportReport {
        val rows = Csv.parse(csv)
        var created = 0; var updated = 0; var contacts = 0
        val errors = mutableListOf<String>()
        val knownSlugs = accountRepo.accountsByScore(5000).map { CompanyNames.normalize(it.account.name) }.toMutableSet()
        rows.forEachIndexed { i, r ->
            val line = i + 2
            val name = r["name"].orEmpty()
            if (name.isBlank()) { errors += "ligne $line : nom manquant"; return@forEachIndexed }
            runCatching {
                val existed = !knownSlugs.add(CompanyNames.normalize(name))
                val account = Account(
                    name = name,
                    domain = CompanyNames.domainOf(r["domain"]) ?: r["domain"]?.takeIf { it.contains('.') }?.lowercase(),
                    siren = r["siren"]?.filter { it.isDigit() }?.takeIf { it.length == 9 },
                    sector = r["sector"]?.uppercase()?.let { s -> Sector.entries.firstOrNull { it.name == s } } ?: Sector.OTHER,
                    market = Market.FR,
                    headcount = r["headcount"]?.toIntOrNull(),
                    iosAppId = r["ios_app_id"]?.toLongOrNull(),
                    warmPath = r["warm_path"]?.takeIf { it.isNotBlank() },
                    source = "import",
                )
                val id = accountRepo.upsertAccount(account)
                if (existed) updated++ else created++
                accountRepo.saveScore(Scoring.score(account.copy(id = id), factRepo.signalsOf(id), clock.instant(), factRepo.factsOf(id)))
                val contactName = r["contact_name"].orEmpty()
                if (contactName.isNotBlank()) {
                    val email = r["contact_email"]?.trim()?.takeIf { it.contains('@') }
                    if (email != null && replyRepo.isSuppressed(email)) {
                        errors += "ligne $line : $email s'est opposé à la prospection, contact ignoré"
                    } else {
                        contactRepo.addContact(
                            Contact(
                                accountId = id, fullName = contactName, role = r["contact_role"].orEmpty().ifBlank { "inconnu" },
                                persona = Targeting.personaFromRole(r["contact_role"].orEmpty()), email = email,
                                linkedinUrl = r["contact_linkedin"]?.takeIf { it.startsWith("https://") },
                                dataSource = "import de la liste Doppio",
                            ),
                        )
                        contacts++
                    }
                }
            }.onFailure { errors += "ligne $line : ${it.message}" }
        }
        eventRepo.logEvent("IMPORT", payload = """{"created":$created,"updated":$updated,"contacts":$contacts}""")
        return ImportReport(created, updated, contacts, errors)
    }
}
