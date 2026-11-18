package lab.doppio.bloom.app.web

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import lab.doppio.bloom.app.admin
import lab.doppio.bloom.app.routeTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.port.AccountRow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the exact text of the API answers that reach the dashboard. */
class ApiMessagesPinTest {
    private val json = ContentType.Application.Json

    private fun lab.doppio.bloom.app.TestPorts.account() {
        accounts.rows = listOf(AccountRow(Account(10, "Acme"), 60, 0, 0, 0, null, emptyList(), null))
    }

    @Test
    fun `launching a research answers lancée`() = routeTest { ports ->
        ports.account()
        val response = client.post("/api/accounts/10/research") { admin() }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"status":"lancée"}""", response.bodyAsText())
    }

    @Test
    fun `a draft that is not waiting any more answers the same sentence for approve and reject`() = routeTest { ports ->
        val enrollment = ports.sequences.addEnrollment(ports.contacts.add().id)
        val draft = ports.drafts.add(enrollment.id, DraftStatus.REJECTED, step = 1, body = "Bonjour")
        val expected = """{"error":"ce brouillon n'est plus à valider"}"""
        val approve = client.post("/api/drafts/${draft.id}/approve") { admin() }
        assertEquals(HttpStatusCode.Conflict, approve.status)
        assertEquals(expected, approve.bodyAsText())
        assertEquals(expected, client.post("/api/drafts/${draft.id}/reject") { admin() }.bodyAsText())
    }

    @Test
    fun `a task that is not pending answers 409`() = routeTest { ports ->
        val enrollment = ports.sequences.addEnrollment(ports.contacts.add().id)
        val draft = ports.drafts.add(enrollment.id, DraftStatus.REJECTED, step = 1, body = "Bonjour")
        val response = client.post("/api/drafts/${draft.id}/done") { admin() }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("""{"error":"cette tâche n'est plus à faire"}""", response.bodyAsText())
    }

    @Test
    fun `enrolling a contact who opted out or is already enrolled gives the French reason`() = routeTest { ports ->
        val contact = ports.contacts.add()
        ports.sequences.addEnrollment(contact.id, EnrollmentStatus.ACTIVE)
        val already = client.post("/api/contacts/${contact.id}/enroll") { admin(); contentType(json); setBody("{}") }
        assertEquals("""{"error":"Ce contact est déjà inscrit dans une séquence"}""", already.bodyAsText())
        val optedOut = ports.contacts.add(email = "out@client.test")
        ports.replies.suppressed[optedOut.email!!] = "opposition"
        val refused = client.post("/api/contacts/${optedOut.id}/enroll") { admin(); contentType(json); setBody("{}") }
        assertEquals("""{"error":"Ce contact s'est opposé à la prospection"}""", refused.bodyAsText())
    }

    @Test
    fun `an unreadable body is a client mistake, not an internal error`() = routeTest {
        val response = client.post("/api/deals") { admin(); contentType(json); setBody("{") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"error":"requête invalide"}""", response.bodyAsText())
    }

    @Test
    fun `discovery and blacklist endpoints answer fixed statuses`() = routeTest { ports ->
        ports.account()
        val run = client.post("/api/discovery/run") { admin() }
        assertEquals(HttpStatusCode.Accepted, run.status)
        assertEquals("""{"status":"lancée"}""", run.bodyAsText())
        assertEquals("""{"status":"écartée"}""", client.post("/api/demands/dismiss") { admin(); contentType(json); setBody("""{"key":"k"}""") }.bodyAsText())
        assertEquals("""{"status":"écarté"}""", client.post("/api/accounts/10/exclude") { admin(); contentType(json); setBody("""{"reason":""}""") }.bodyAsText())
        assertEquals("""{"status":"réintégré"}""", client.post("/api/accounts/10/restore") { admin() }.bodyAsText())
        assertEquals("""{"status":"ajouté"}""", client.post("/api/blacklist") { admin(); contentType(json); setBody("""{"name":"Acme"}""") }.bodyAsText())
        assertEquals("""{"status":"retiré"}""", client.post("/api/blacklist/3/remove") { admin() }.bodyAsText())
        val blank = client.post("/api/blacklist") { admin(); contentType(json); setBody("""{"name":"  "}""") }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals("""{"error":"Nom obligatoire"}""", blank.bodyAsText())
    }

    @Test
    fun `adding a contact tells what happens next`() = routeTest { ports ->
        ports.account()
        val noEmail = client.post("/api/accounts/10/contacts") { admin(); contentType(json); setBody("""{"fullName":"Jane Doe","role":"CTO"}""") }
        assertEquals("""{"contactId":1,"enrolled":false,"message":"Contact ajouté. Ajoutez son email pour lancer la séquence."}""", noEmail.bodyAsText().replace(Regex("\"contactId\":\\d+"), "\"contactId\":1"))
        val enrolled = client.post("/api/accounts/10/contacts") { admin(); contentType(json); setBody("""{"fullName":"Jane Doe","role":"CTO","email":"jane@acme.test"}""") }
        assertEquals(true, enrolled.bodyAsText().endsWith(""""enrolled":true,"message":"Séquence lancée"}"""), enrolled.bodyAsText())
        val kept = client.post("/api/accounts/10/contacts") { admin(); contentType(json); setBody("""{"fullName":"Jane Doe","role":"CTO","email":"john@acme.test","enroll":false}""") }
        assertEquals(true, kept.bodyAsText().endsWith(""""enrolled":false,"message":"Contact ajouté"}"""), kept.bodyAsText())
        val invalid = client.post("/api/accounts/10/contacts") { admin(); contentType(json); setBody("""{"fullName":" ","role":"CTO"}""") }
        assertEquals("""{"error":"Nom et rôle sont obligatoires"}""", invalid.bodyAsText())
    }

    @Test
    fun `the contact book export keeps its header, BOM and file name`() = routeTest {
        val response = client.get("/api/book.csv") { admin() }
        assertEquals("﻿entreprise;nom;role;email;linkedin;statut;source", response.bodyAsText())
        assertEquals("""attachment; filename="carnet-bloom.csv"""", response.headers[HttpHeaders.ContentDisposition])
    }

    @Test
    fun `the unsubscribe page keeps its exact HTML`() = routeTest { ports ->
        val token = ports.tokens.tokenFor(ports.contacts.add(email = "jane@client.test").id)
        assertEquals(
            "<!doctype html><meta charset=utf-8><title>Désinscription</title><body style='font-family:system-ui;max-width:32rem;margin:4rem auto'>" +
                "<h1>C'est fait.</h1><p>Vous ne recevrez plus de message de Doppio Lab. Désolé pour le dérangement.</p></body>",
            client.get("/u/$token").bodyAsText(),
        )
    }

    @Test
    fun `texts that no fake route can reach are pinned on the object`() {
        assertEquals("non demandée", ApiMessages.NOT_REQUESTED)
        assertEquals("déjà en cours", ApiMessages.ALREADY_RUNNING)
        assertEquals("Demande introuvable", ApiMessages.DEMAND_NOT_FOUND)
        assertEquals("écarté à la main", ApiMessages.EXCLUDED_BY_HAND)
        assertEquals("Le dossier conclut qu'aucune offre ne convient : choisir l'offre explicitement", ApiMessages.NO_FIT)
        assertEquals("Contact ajouté, recherche de l'email en cours", ApiMessages.CONTACT_ADDED_SEARCHING_EMAIL)
        assertEquals("contact", ApiMessages.CSV_STATUS_CONTACT)
        assertEquals("suggestion", ApiMessages.CSV_STATUS_SUGGESTION)
    }
}
