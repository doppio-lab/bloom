package lab.doppio.bloom.app.web

import io.ktor.http.ContentType
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
import lab.doppio.bloom.app.AppContext
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.model.DiscoveryReport
import lab.doppio.bloom.core.usecase.AddContact
import lab.doppio.bloom.core.usecase.BookEntry
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("DiscoveryRoutes")

private val lenientJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun bookCsv(book: List<BookEntry>): String {
    val lines = listOf("entreprise;nom;role;email;linkedin;statut;source") + book.flatMap { e ->
        e.contacts.map { c -> listOf(e.name, c.contact.fullName, c.contact.role, c.contact.email.orEmpty(), c.contact.linkedinUrl.orEmpty(), "contact", c.contact.dataSource) } +
            e.officers.map { o -> listOf(e.name, o.fullName, o.role, "", "", "suggestion", o.sourceUrl) }
    }.map { cells -> cells.joinToString(";") { "\"" + it.replace("\"", "\"\"") + "\"" } }
    return "﻿" + lines.joinToString("\r\n")
}

fun Route.discoveryRoutes(ctx: AppContext) = route("/api") {

    /** Lancer la découverte sans attendre la nuit. Elle tourne en tâche de fond (plusieurs minutes). */
    post("/discovery/run") {
        if (ctx.runDiscovery.running) return@post call.respond(HttpStatusCode.Conflict, mapOf("status" to "déjà en cours"))
        ctx.background.launch {
            runCatching { ctx.runDiscovery(); ctx.researchBatch() }
                .onFailure { log.error("Découverte manuelle en échec", it) }
        }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "lancée"))
    }

    /** What the last discovery run found, per source, and what failed or is switched off. */
    get("/discovery/last") {
        // Before any finished run, the line still says whether one is in progress
        val (payload, at) = ctx.eventRepo.lastEvent("DISCOVERY_RUN") ?: return@get call.respond(LastDiscovery(running = ctx.runDiscovery.running))
        val report = runCatching { lenientJson.decodeFromString(DiscoveryReport.serializer(), payload) }.getOrDefault(DiscoveryReport())
        call.respond(LastDiscovery(at.toString(), ctx.runDiscovery.running, report.bySource, report.notes))
    }

    /** Comptes qui valent le coup mais à qui il manque la personne à contacter. */
    get("/to-complete") { call.respond(ctx.toComplete()) }

    post("/accounts/{id}/contacts") {
        val account = ctx.accountRepo.account(call.parameters["id"]!!.toLong()) ?: return@post call.respond(HttpStatusCode.NotFound)
        val req = call.receive<NewContactRequest>()
        val added = ctx.addContact(account, AddContact.Request(req.fullName, req.role, req.email, req.linkedinUrl, req.enroll, req.fromRegistry))
        val contact = added.contact
        if (contact.email != null) {
            return@post call.respond(HttpStatusCode.Created, NewContactResult(contact.id, added.enrolled, if (added.enrolled) "Séquence lancée" else "Contact ajouté"))
        }
        val finder = ctx.findContactEmail
            ?: return@post call.respond(HttpStatusCode.Created, NewContactResult(contact.id, false, "Contact ajouté. Ajoutez son email pour lancer la séquence."))
        // Recherche d'email en tâche de fond : Dropcontact répond en une à trois minutes
        ctx.background.launch {
            runCatching { finder(contact, account, req.enroll) }.onFailure { log.warn("Dropcontact en échec pour le contact ${contact.id}", it) }
        }
        call.respond(HttpStatusCode.Accepted, NewContactResult(contact.id, false, "Contact ajouté, recherche de l'email en cours"))
    }

    /** Requests published on marketplaces, to answer on the platform. Never turned into email sequences. */
    get("/demands") { call.respond(ctx.discoveryState.openDemands()) }

    post("/demands/dismiss") {
        if (!ctx.discoveryState.dismissDemand(call.receive<DismissDemand>().key)) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Demande introuvable"))
        call.respond(HttpStatusCode.OK, mapOf("status" to "écartée"))
    }

    /** Blacklist an account: it leaves every list, its sequences stop, and discovery never brings it back. */
    post("/accounts/{id}/exclude") {
        val id = call.parameters["id"]!!.toLong()
        val reason = runCatching { call.receive<ExcludeRequest>().reason }.getOrDefault("").ifBlank { "écarté à la main" }
        ctx.accountRepo.excludeAccount(id, reason)
        ctx.eventRepo.logEvent("ACCOUNT_EXCLUDED", id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "écarté"))
    }

    post("/accounts/{id}/restore") {
        val id = call.parameters["id"]!!.toLong()
        ctx.accountRepo.restoreAccount(id)
        ctx.eventRepo.logEvent("ACCOUNT_RESTORED", id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "réintégré"))
    }

    get("/blacklist") { call.respond(ctx.accountRepo.blacklist()) }

    /** Block a company by name, before or after discovery found it. */
    post("/blacklist") {
        val req = call.receive<BlockRequest>()
        require(CompanyNames.normalize(req.name).isNotEmpty()) { "Nom obligatoire" }
        val accountId = ctx.accountRepo.blockName(req.name, req.reason?.trim()?.takeIf { it.isNotEmpty() })
        if (accountId != null) ctx.eventRepo.logEvent("ACCOUNT_EXCLUDED", accountId)
        call.respond(HttpStatusCode.Created, mapOf("status" to "ajouté"))
    }

    post("/blacklist/{id}/remove") {
        ctx.accountRepo.unblockName(call.parameters["id"]!!.toLong())
        call.respond(HttpStatusCode.OK, mapOf("status" to "retiré"))
    }

    /**
     * The contact book: for each account worth writing to, the people already chosen, the legal officers
     * published by the company registry, and the searches to find the right role.
     */
    get("/book") { call.respond(ctx.contactBook()) }

    get("/book.csv") {
        call.response.headers.append("Content-Disposition", "attachment; filename=\"carnet-bloom.csv\"")
        call.respondText(bookCsv(ctx.contactBook()), ContentType.Text.CSV)
    }

    post("/import") { call.respond(ctx.importAccounts(call.receiveText())) }
}
