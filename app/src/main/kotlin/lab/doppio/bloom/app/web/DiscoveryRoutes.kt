package lab.doppio.bloom.app.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import lab.doppio.bloom.app.AppContext
import lab.doppio.bloom.app.outreach.normalizeEmail
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.Csv
import lab.doppio.bloom.core.discovery.Targeting
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.scoring.Scoring
import lab.doppio.bloom.data.db.AccountRow
import lab.doppio.bloom.data.db.OfficerRow
import lab.doppio.bloom.data.discovery.DiscoveryReport
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
    /** True when the person was picked among the legal officers published by the company registry. */
    val fromRegistry: Boolean = false,
)
@Serializable data class NewContactResult(val contactId: Long, val enrolled: Boolean, val message: String)
@Serializable data class ExcludeRequest(val reason: String)
@Serializable data class BlockRequest(val name: String, val reason: String? = null)
@Serializable data class BookContact(val contact: Contact, /** "sequence", "email" (email known, no sequence) or "no-email". */ val status: String)
@Serializable data class BookEntry(
    val accountId: Long, val name: String, val score: Int, val source: String, val offer: Offer, val why: String,
    val contacts: List<BookContact>, val officers: List<OfficerRow>, val searches: List<PeopleSearch>,
)
@Serializable data class ImportReport(val created: Int, val updated: Int, val contacts: Int, val errors: List<String>)

private val log = LoggerFactory.getLogger("DiscoveryRoutes")

/** A blank email is no email; a non-blank one must be a single valid address or the request is refused. */
internal fun emailOrNull(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return normalizeEmail(raw) ?: throw IllegalArgumentException("Adresse email invalide : « ${raw.trim()} »")
}

/**
 * Inscrit un contact dans la séquence de l'offre [requested], sinon celle du dossier ou recommandée.
 * Renvoie l'inscription, ou null quand rien ne démarre : pas d'email, opposition, séquence déjà en cours,
 * ou dossier qui conclut qu'aucune offre ne convient (sauf offre choisie explicitement).
 */
suspend fun enrollContact(ctx: AppContext, contact: Contact, requested: Offer? = null): Long? {
    val email = contact.email ?: return null
    if (ctx.repo.isSuppressed(email)) return null
    if (ctx.repo.enrollmentsOfContact(contact.id).any { it.status == EnrollmentStatus.ACTIVE || it.status == EnrollmentStatus.PAUSED }) return null
    val dossier = ctx.repo.dossier(contact.accountId)
    // The researcher found no offer that fits: no sequence starts on its own
    if (requested == null && dossier?.noFit == true) return null
    val offer = requested ?: dossier?.offer
        ?: ctx.repo.accountsByScore(500).firstOrNull { it.account.id == contact.accountId }?.recommendedOffer
        ?: Offer.DISCOVERY
    val seq = ctx.repo.sequenceFor(offer) ?: ctx.repo.sequenceFor(Offer.DISCOVERY) ?: return null
    val id = ctx.repo.enroll(contact.id, seq.id)
    ctx.repo.logEvent("ENROLLED", contact.accountId, contact.id)
    ctx.orchestrator.planSoon()
    return id
}

@Serializable data class DismissDemand(val key: String)

@Serializable
data class LastDiscovery(
    val ranAt: String? = null,
    val running: Boolean = false,
    val bySource: Map<String, Int> = emptyMap(),
    val notes: List<String> = emptyList(),
)

/** Accounts above the display threshold, or that already have a contact, with the people known for each. */
private suspend fun book(ctx: AppContext): List<BookEntry> {
    val repo = ctx.repo
    val contacts = repo.contactsByAccount()
    val officers = repo.officersByAccount()
    val enrolled = repo.activeEnrollments().map { it.contactId }.toSet()
    return repo.accountsByScore(500).filter { it.score >= 45 || it.account.id in contacts }.take(150).map { row ->
        val id = row.account.id
        val offer = repo.dossier(id)?.offer ?: row.recommendedOffer ?: Offer.DISCOVERY
        val persona = Targeting.personaFor(offer)
        val known = contacts[id].orEmpty()
        val knownNames = known.map { CompanyNames.normalize(it.fullName) }.toSet()
        BookEntry(
            accountId = id, name = row.account.name, score = row.score, source = row.account.source, offer = offer, why = persona.why,
            contacts = known.map { c -> BookContact(c, if (c.id in enrolled) "sequence" else if (c.email != null) "email" else "no-email") },
            officers = officers[id].orEmpty().filter { CompanyNames.normalize(it.fullName) !in knownNames },
            searches = persona.roles.map { PeopleSearch(it, Targeting.linkedinSearchUrl(it, row.account.name)) },
        )
    }
}

private val lenientJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * A quoted CSV cell. A value starting with =, +, -, @, tab or CR would be run as a formula by a
 * spreadsheet (a company name or a role is typed by anyone): a leading quote makes it text.
 */
internal fun csvCell(value: String): String {
    val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
    return "\"" + safe.replace("\"", "\"\"") + "\""
}

fun Route.discoveryRoutes(ctx: AppContext) = route("/api") {
    val repo = ctx.repo

    /** Lancer la découverte sans attendre la nuit. Elle tourne en tâche de fond (plusieurs minutes). */
    post("/discovery/run") {
        // Only the instance that runs the loops discovers: two runs at once would merge the same accounts twice
        if (!ctx.orchestrator.isLeader) return@post call.respond(HttpStatusCode.Conflict, mapOf("status" to "cette instance ne tient pas les tâches de fond, réessayer dans une minute"))
        if (ctx.orchestrator.discoveryRunning) return@post call.respond(HttpStatusCode.Conflict, mapOf("status" to "déjà en cours"))
        ctx.background.launch {
            runCatching { ctx.orchestrator.runDiscovery(); ctx.orchestrator.researchBatch() }
                .onFailure { log.error("Découverte manuelle en échec", it) }
        }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "lancée"))
    }

    /** What the last discovery run found, per source, and what failed or is switched off. */
    get("/discovery/last") {
        // Before any finished run, the line still says whether one is in progress
        val (payload, at) = repo.lastEvent("DISCOVERY_RUN") ?: return@get call.respond(LastDiscovery(running = ctx.orchestrator.discoveryRunning))
        val report = runCatching { lenientJson.decodeFromString(DiscoveryReport.serializer(), payload) }.getOrDefault(DiscoveryReport())
        call.respond(LastDiscovery(at.toString(), ctx.orchestrator.discoveryRunning, report.bySource, report.notes))
    }

    /** Comptes qui valent le coup mais à qui il manque la personne à contacter. */
    get("/to-complete") {
        val items = repo.accountsToComplete().filter { repo.dossier(it.account.id)?.noFit != true }.map { row ->
            val offer = repo.dossier(row.account.id)?.offer ?: row.recommendedOffer ?: Offer.DISCOVERY
            val persona = Targeting.personaFor(offer)
            ToComplete(
                row = row,
                offer = offer,
                roles = persona.roles,
                why = persona.why,
                searches = persona.roles.map { PeopleSearch(it, Targeting.linkedinSearchUrl(it, row.account.name)) },
                // The facts behind the strongest signals come first: they are why the account is in the list
                facts = repo.signalsOf(row.account.id)
                    .sortedByDescending { Scoring.rules.getValue(it.type).weight * it.strength }
                    .flatMap { it.factIds }.distinct().withIndex().associate { (rank, id) -> id to rank }
                    .let { rank -> repo.factsOf(row.account.id).sortedBy { rank[it.id] ?: Int.MAX_VALUE }.take(3) },
            )
        }
        call.respond(items)
    }

    post("/accounts/{id}/contacts") {
        val accountId = call.parameters["id"]!!.toLong()
        val account = repo.account(accountId) ?: return@post call.respond(HttpStatusCode.NotFound)
        val req = call.receive<NewContactRequest>()
        require(req.fullName.isNotBlank() && req.role.isNotBlank()) { "Nom et rôle sont obligatoires" }
        val email = emailOrNull(req.email)
        email?.let { require(!repo.isSuppressed(it)) { "Cette personne s'est opposée à la prospection" } }

        val contact = Contact(
            accountId = accountId,
            fullName = req.fullName.trim(),
            role = req.role.trim(),
            persona = Targeting.personaFromRole(req.role),
            email = email,
            linkedinUrl = req.linkedinUrl?.trim()?.takeIf { it.startsWith("https://") },
            dataSource = if (req.fromRegistry) "Annuaire des entreprises (dirigeant publié au registre)" else "saisie manuelle (profil public LinkedIn ou site de l'entreprise)",
        )
        val id = repo.addContact(contact)
        val saved = contact.copy(id = id)

        if (saved.email != null) {
            val enrolled = req.enroll && enrollContact(ctx, saved) != null
            return@post call.respond(HttpStatusCode.Created, NewContactResult(id, enrolled, if (enrolled) "Séquence lancée" else "Contact ajouté"))
        }
        val finder = ctx.dropcontact
        if (finder == null) {
            return@post call.respond(HttpStatusCode.Created, NewContactResult(id, false, "Contact ajouté. Ajoutez son email pour lancer la séquence."))
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

    /** Requests published on marketplaces, to answer on the platform. Never turned into email sequences. */
    get("/demands") { call.respond(repo.openDemands()) }

    post("/demands/dismiss") {
        if (!repo.dismissDemand(call.receive<DismissDemand>().key)) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Demande introuvable"))
        call.respond(HttpStatusCode.OK, mapOf("status" to "écartée"))
    }

    /** Blacklist an account: it leaves every list, its sequences stop, and discovery never brings it back. */
    post("/accounts/{id}/exclude") {
        val id = call.parameters["id"]!!.toLong()
        val reason = call.receiveOrDefault(ExcludeRequest("")).reason.ifBlank { "écarté à la main" }
        repo.excludeAccount(id, reason)
        repo.logEvent("ACCOUNT_EXCLUDED", id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "écarté"))
    }

    post("/accounts/{id}/restore") {
        val id = call.parameters["id"]!!.toLong()
        repo.restoreAccount(id)
        repo.logEvent("ACCOUNT_RESTORED", id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "réintégré"))
    }

    get("/blacklist") { call.respond(repo.blacklist()) }

    /** Block a company by name, before or after discovery found it. */
    post("/blacklist") {
        val req = call.receive<BlockRequest>()
        require(CompanyNames.normalize(req.name).isNotEmpty()) { "Nom obligatoire" }
        val accountId = repo.blockName(req.name, req.reason?.trim()?.takeIf { it.isNotEmpty() })
        if (accountId != null) repo.logEvent("ACCOUNT_EXCLUDED", accountId)
        call.respond(HttpStatusCode.Created, mapOf("status" to "ajouté"))
    }

    post("/blacklist/{id}/remove") {
        repo.unblockName(call.parameters["id"]!!.toLong())
        call.respond(HttpStatusCode.OK, mapOf("status" to "retiré"))
    }

    /**
     * The contact book: for each account worth writing to, the people already chosen, the legal officers
     * published by the company registry, and the searches to find the right role.
     */
    get("/book") { call.respond(book(ctx)) }

    get("/book.csv") {
        val lines = listOf("entreprise;nom;role;email;linkedin;statut;source") + book(ctx).flatMap { e ->
            e.contacts.map { c -> listOf(e.name, c.contact.fullName, c.contact.role, c.contact.email.orEmpty(), c.contact.linkedinUrl.orEmpty(), "contact", c.contact.dataSource) } +
                e.officers.map { o -> listOf(e.name, o.fullName, o.role, "", "", "suggestion", o.sourceUrl) }
        }.map { cells -> cells.joinToString(";") { csvCell(it) } }
        call.response.headers.append("Content-Disposition", "attachment; filename=\"carnet-bloom.csv\"")
        call.respondText("\uFEFF" + lines.joinToString("\r\n"), io.ktor.http.ContentType.Text.CSV)
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
                    val email = emailOrNull(r["contact_email"])
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
