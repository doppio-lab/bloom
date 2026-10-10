package lab.doppio.bloom.app.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import lab.doppio.bloom.app.AppContext
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.Csv
import lab.doppio.bloom.core.discovery.Targeting
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.scoring.Scoring
import lab.doppio.bloom.data.db.AccountRow
import org.slf4j.LoggerFactory

@Serializable data class PeopleSearch(val role: String, val url: String)
@Serializable data class ToComplete(
    val row: AccountRow, val offer: Offer, val roles: List<String>, val why: String,
    val searches: List<PeopleSearch>, val facts: List<Fact>,
)
@Serializable data class NewContactRequest(
    val fullName: String, val role: String, val email: String? = null, val linkedinUrl: String? = null,
    /** Lancer la séquence tout de suite si un email est connu (ou trouvé). */
    val enroll: Boolean = true,
)
@Serializable data class NewContactResult(val contactId: Long, val enrolled: Boolean, val message: String)
@Serializable data class ExcludeRequest(val reason: String)
@Serializable data class ImportReport(val created: Int, val updated: Int, val contacts: Int, val errors: List<String>)

private val log = LoggerFactory.getLogger("DiscoveryRoutes")

/** Inscrit un contact dans la séquence de l'offre recommandée. Renvoie vrai si c'est fait. */
suspend fun enrollContact(ctx: AppContext, contact: Contact): Boolean {
    val email = contact.email ?: return false
    if (ctx.repo.isSuppressed(email)) return false
    if (ctx.repo.enrollmentsOfContact(contact.id).isNotEmpty()) return false
    val offer = ctx.repo.dossier(contact.accountId)?.offer
        ?: ctx.repo.accountsByScore(500).firstOrNull { it.account.id == contact.accountId }?.recommendedOffer
        ?: Offer.DISCOVERY
    val seq = ctx.repo.sequenceFor(offer) ?: ctx.repo.sequenceFor(Offer.DISCOVERY) ?: return false
    ctx.repo.enroll(contact.id, seq.id)
    ctx.repo.logEvent("ENROLLED", contact.accountId, contact.id)
    ctx.orchestrator.planDrafts()
    return true
}

fun Route.discoveryRoutes(ctx: AppContext) = route("/api") {
    val repo = ctx.repo

    /** Lancer la découverte sans attendre la nuit. Elle tourne en tâche de fond (plusieurs minutes). */
    post("/discovery/run") {
        if (ctx.orchestrator.discoveryRunning) return@post call.respond(HttpStatusCode.Conflict, mapOf("status" to "déjà en cours"))
        ctx.background.launch {
            runCatching { ctx.orchestrator.runDiscovery(); ctx.orchestrator.researchBatch() }
                .onFailure { log.error("Découverte manuelle en échec", it) }
        }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "lancée"))
    }

    /** Comptes qui valent le coup mais à qui il manque la personne à contacter. */
    get("/to-complete") {
        val items = repo.accountsToComplete().map { row ->
            val offer = repo.dossier(row.account.id)?.offer ?: row.recommendedOffer ?: Offer.DISCOVERY
            val persona = Targeting.personaFor(offer)
            ToComplete(
                row = row,
                offer = offer,
                roles = persona.roles,
                why = persona.why,
                searches = persona.roles.map { PeopleSearch(it, Targeting.linkedinSearchUrl(it, row.account.name)) },
                facts = repo.factsOf(row.account.id).take(3),
            )
        }
        call.respond(items)
    }

    post("/accounts/{id}/contacts") {
        val accountId = call.parameters["id"]!!.toLong()
        val account = repo.account(accountId) ?: return@post call.respond(HttpStatusCode.NotFound)
        val req = call.receive<NewContactRequest>()
        require(req.fullName.isNotBlank() && req.role.isNotBlank()) { "Nom et rôle sont obligatoires" }
        req.email?.let { require(!repo.isSuppressed(it)) { "Cette personne s'est opposée à la prospection" } }

        val contact = Contact(
            accountId = accountId,
            fullName = req.fullName.trim(),
            role = req.role.trim(),
            persona = Targeting.personaFromRole(req.role),
            email = req.email?.trim()?.takeIf { it.contains('@') },
            linkedinUrl = req.linkedinUrl?.trim()?.takeIf { it.startsWith("https://") },
            dataSource = "saisie manuelle (profil public LinkedIn ou site de l'entreprise)",
        )
        val id = repo.addContact(contact)
        val saved = contact.copy(id = id)

        if (saved.email != null) {
            val enrolled = req.enroll && enrollContact(ctx, saved)
            return@post call.respond(HttpStatusCode.Created, NewContactResult(id, enrolled, if (enrolled) "Séquence lancée" else "Contact ajouté"))
        }
        val finder = ctx.dropcontact
        if (finder == null) {
            return@post call.respond(HttpStatusCode.Created, NewContactResult(id, false, "Contact ajouté. Ajoute son email pour lancer la séquence."))
        }
        // Recherche d'email en tâche de fond : Dropcontact répond en une à trois minutes
        ctx.background.launch {
            runCatching {
                val found = finder.findEmail(saved.fullName, account.name, account.domain)
                if (found != null) {
                    repo.updateContactEmail(id, found.email, "Dropcontact (${found.qualification}) à partir du nom saisi")
                    repo.logEvent("EMAIL_FOUND", accountId, id)
                    if (req.enroll) repo.contact(id)?.let { enrollContact(ctx, it) }
                } else {
                    repo.logEvent("EMAIL_NOT_FOUND", accountId, id)
                }
            }.onFailure { log.warn("Dropcontact en échec pour le contact $id", it) }
        }
        call.respond(HttpStatusCode.Accepted, NewContactResult(id, false, "Contact ajouté, recherche de l'email en cours"))
    }

    post("/accounts/{id}/exclude") {
        val id = call.parameters["id"]!!.toLong()
        val reason = runCatching { call.receive<ExcludeRequest>().reason }.getOrDefault("écarté à la main")
        repo.excludeAccount(id, reason)
        repo.logEvent("ACCOUNT_EXCLUDED", id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "écarté"))
    }

    /**
     * Import de ta propre liste (réseau, anciens clients, salons). CSV avec en-tête, virgule ou point-virgule :
     * name, domain, siren, ios_app_id, sector, headcount, warm_path, contact_name, contact_role, contact_email, contact_linkedin
     */
    post("/import") {
        val rows = Csv.parse(call.receiveText())
        var created = 0; var updated = 0; var contacts = 0
        val errors = mutableListOf<String>()
        val knownSlugs = repo.accountsByScore(5000).map { CompanyNames.normalize(it.account.name) }.toMutableSet()
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
                val id = repo.upsertAccount(account)
                if (existed) updated++ else created++
                repo.saveScore(Scoring.score(account.copy(id = id), repo.signalsOf(id)))
                val contactName = r["contact_name"].orEmpty()
                if (contactName.isNotBlank()) {
                    val email = r["contact_email"]?.trim()?.takeIf { it.contains('@') }
                    if (email != null && repo.isSuppressed(email)) {
                        errors += "ligne $line : $email s'est opposé à la prospection, contact ignoré"
                    } else {
                        repo.addContact(
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
        repo.logEvent("IMPORT", payload = """{"created":$created,"updated":$updated,"contacts":$contacts}""")
        call.respond(ImportReport(created, updated, contacts, errors))
    }
}
