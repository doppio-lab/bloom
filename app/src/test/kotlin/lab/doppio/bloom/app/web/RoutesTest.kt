package lab.doppio.bloom.app.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.head
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
import kotlin.test.assertTrue

class RoutesTest {
    @Test
    fun `health answers without credentials, for GET and HEAD`() = routeTest {
        assertEquals("ok", client.get("/health").bodyAsText())
        assertEquals(HttpStatusCode.OK, client.head("/health").status)
    }

    @Test
    fun `admin routes require basic auth`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/overview").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/drafts").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/drafts") { header(HttpHeaders.Authorization, "Basic ZG9wcGlvOm5vcGU=") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/overview") { admin() }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/drafts") { admin() }.status)
    }

    @Test
    fun `a cross-site post is refused before it reaches the use case`() = routeTest { ports ->
        val enrollment = ports.sequences.addEnrollment(ports.contacts.add().id)
        val draft = ports.drafts.add(enrollment.id, DraftStatus.PENDING_REVIEW, step = 1, body = "Bonjour, une question ?")
        val response = client.post("/api/drafts/${draft.id}/reject") { admin(); header("Sec-Fetch-Site", "cross-site") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(DraftStatus.PENDING_REVIEW, ports.drafts.status(draft.id))
        // The same request from the dashboard goes through
        assertEquals(HttpStatusCode.OK, client.post("/api/drafts/${draft.id}/reject") { admin(); header("Sec-Fetch-Site", "same-origin") }.status)
    }

    // --- Drafts -----------------------------------------------------------------------------

    private fun lab.doppio.bloom.app.TestPorts.pendingDraft(body: String = "Bonjour Jane, avez-vous regardé ma note ?"): Long {
        accounts.rows = listOf(AccountRow(Account(10, "Acme"), 60, 0, 0, 0, null, emptyList(), null))
        val enrollment = sequences.addEnrollment(contacts.add().id, nextStep = 1)
        return drafts.add(enrollment.id, DraftStatus.PENDING_REVIEW, step = 1, body = body).id
    }

    @Test
    fun `approving a draft answers 200 and stores the decision`() = routeTest { ports ->
        val id = ports.pendingDraft()
        val response = client.post("/api/drafts/$id/approve") { admin(); contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"APPROVED"}""", response.bodyAsText())
        assertEquals(DraftStatus.APPROVED, ports.drafts.status(id))
    }

    @Test
    fun `approving without a body works too`() = routeTest { ports ->
        val id = ports.pendingDraft()
        assertEquals(HttpStatusCode.OK, client.post("/api/drafts/$id/approve") { admin() }.status)
    }

    @Test
    fun `rejecting a draft answers 200`() = routeTest { ports ->
        val id = ports.pendingDraft()
        val response = client.post("/api/drafts/$id/reject") { admin() }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"REJECTED"}""", response.bodyAsText())
        assertEquals(DraftStatus.REJECTED, ports.drafts.status(id))
    }

    @Test
    fun `deciding a draft that is already decided answers 409 and changes nothing`() = routeTest { ports ->
        val id = ports.pendingDraft()
        assertEquals(HttpStatusCode.OK, client.post("/api/drafts/$id/reject") { admin() }.status)
        val approve = client.post("/api/drafts/$id/approve") { admin() }
        val reject = client.post("/api/drafts/$id/reject") { admin() }
        assertEquals(HttpStatusCode.Conflict, approve.status)
        assertEquals(HttpStatusCode.Conflict, reject.status)
        assertTrue("n'est plus à valider" in approve.bodyAsText())
        assertEquals(DraftStatus.REJECTED, ports.drafts.status(id))
    }

    @Test
    fun `an unknown draft answers 404`() = routeTest {
        assertEquals(HttpStatusCode.NotFound, client.post("/api/drafts/999/approve") { admin() }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/drafts/999/reject") { admin() }.status)
    }

    @Test
    fun `approval applies the guard again, so a blocked draft cannot be approved`() = routeTest { ports ->
        val blocked = ports.pendingDraft(body = "Notre synergie vous plairait, non ?")
        val response = client.post("/api/drafts/$blocked/approve") { admin() }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue("synergie" in response.bodyAsText())
        assertEquals(DraftStatus.PENDING_REVIEW, ports.drafts.status(blocked))

        // A human correction goes through the guard too
        val ok = ports.pendingDraft()
        val edited = client.post("/api/drafts/$ok/approve") {
            admin(); contentType(ContentType.Application.Json); setBody("""{"body":"Une synergie, vous en pensez quoi ?"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, edited.status)
        assertEquals(DraftStatus.PENDING_REVIEW, ports.drafts.status(ok))
    }

    // --- Unsubscribe --------------------------------------------------------------------------

    /** A contact with one sequence in each status. Returns the contact and the unsubscribe token. */
    private fun lab.doppio.bloom.app.TestPorts.contactWithSequences(): Pair<Long, String> {
        val contact = contacts.add(email = "jane@client.test")
        EnrollmentStatus.entries.forEach { sequences.addEnrollment(contact.id, it) }
        return contact.id to tokens.tokenFor(contact.id)
    }

    @Test
    fun `the unsubscribe link suppresses the address and stops only running sequences`() = routeTest { ports ->
        val (contactId, token) = ports.contactWithSequences()
        val response = client.get("/u/$token")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("C'est fait." in response.bodyAsText())
        assertTrue("jane@client.test" in ports.replies.suppressed)
        // ACTIVE and PAUSED became STOPPED_UNSUBSCRIBE (that status was also seeded once), history is kept as it was
        val statuses = ports.sequences.enrollments.values.filter { it.contactId == contactId }.map { it.status }
        assertEquals(3, statuses.count { it == EnrollmentStatus.STOPPED_UNSUBSCRIBE })
        EnrollmentStatus.entries.filterNot { it in setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.PAUSED, EnrollmentStatus.STOPPED_UNSUBSCRIBE) }
            .forEach { assertEquals(1, statuses.count { s -> s == it }, it.name) }
        assertTrue(ports.events.types.contains("UNSUBSCRIBED"))
    }

    @Test
    fun `the one-click POST of RFC 8058 does the same`() = routeTest { ports ->
        val (contactId, token) = ports.contactWithSequences()
        val response = client.post("/u/$token") { contentType(ContentType.Application.FormUrlEncoded); setBody("List-Unsubscribe=One-Click") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("jane@client.test" in ports.replies.suppressed)
        assertEquals(3, ports.sequences.enrollments.values.count { it.contactId == contactId && it.status == EnrollmentStatus.STOPPED_UNSUBSCRIBE })
        assertEquals(1, ports.sequences.enrollments.values.count { it.contactId == contactId && it.status == EnrollmentStatus.COMPLETED })
    }

    @Test
    fun `an invalid token answers 400 and changes nothing`() = routeTest { ports ->
        val (contactId, token) = ports.contactWithSequences()
        val get = client.get("/u/$contactId.deadbeef")
        assertEquals(HttpStatusCode.BadRequest, get.status)
        assertEquals("Lien invalide.", get.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.post("/u/not-a-token").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/u/${token}x").status)
        assertTrue(ports.replies.suppressed.isEmpty())
        assertEquals(1, ports.sequences.enrollments.values.count { it.status == EnrollmentStatus.STOPPED_UNSUBSCRIBE })
    }

    // --- Contacts -----------------------------------------------------------------------------

    @Test
    fun `the enroll endpoint starts a sequence, then refuses while it runs`() = routeTest { ports ->
        val contact = ports.contacts.add()
        val first = client.post("/api/contacts/${contact.id}/enroll") { admin(); contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.Created, first.status)
        assertTrue("enrollmentId" in first.bodyAsText())
        val second = client.post("/api/contacts/${contact.id}/enroll") { admin(); contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.BadRequest, second.status)
        assertTrue("déjà inscrit" in second.bodyAsText())
        assertEquals(1, ports.sequences.enrollments.size)
    }

    @Test
    fun `a human can enroll again once the previous sequence is over`() = routeTest { ports ->
        val contact = ports.contacts.add()
        ports.sequences.addEnrollment(contact.id, EnrollmentStatus.COMPLETED)
        val response = client.post("/api/contacts/${contact.id}/enroll") { admin(); contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(2, ports.sequences.enrollments.size)
    }

    @Test
    fun `the enroll endpoint refuses a contact who opted out, and an unknown contact`() = routeTest { ports ->
        val contact = ports.contacts.add()
        ports.replies.suppressed[contact.email!!] = "opposition"
        val refused = client.post("/api/contacts/${contact.id}/enroll") { admin(); contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(ports.sequences.enrollments.isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.post("/api/contacts/999/enroll") { admin(); contentType(ContentType.Application.Json); setBody("{}") }.status)
    }

    @Test
    fun `adding a contact with an email to an account starts its sequence`() = routeTest { ports ->
        ports.accounts.rows = listOf(AccountRow(Account(10, "Acme"), 60, 0, 0, 0, null, emptyList(), null))
        val response = client.post("/api/accounts/10/contacts") {
            admin(); contentType(ContentType.Application.Json)
            setBody("""{"fullName":"Jane Doe","role":"CTO","email":"jane@acme.test"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue("Séquence lancée" in response.bodyAsText())
        assertEquals(1, ports.sequences.enrollments.size)
    }

    @Test
    fun `without an email nor a Dropcontact key the contact is only saved`() = routeTest { ports ->
        ports.accounts.rows = listOf(AccountRow(Account(10, "Acme"), 60, 0, 0, 0, null, emptyList(), null))
        val response = client.post("/api/accounts/10/contacts") {
            admin(); contentType(ContentType.Application.Json); setBody("""{"fullName":"Jane Doe","role":"CTO"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue("Ajoutez son email" in response.bodyAsText())
        assertTrue(ports.sequences.enrollments.isEmpty())
    }
}
