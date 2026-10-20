package lab.doppio.bloom.app.web

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import lab.doppio.bloom.app.TestDatabase
import lab.doppio.bloom.app.outreach.UnsubscribeTokens
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.db.Repository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnsubscribeRoutesTest {
    private val repo = Repository()
    private val tokens = UnsubscribeTokens("a-secret-long-enough-for-tests")

    @BeforeTest
    fun clean() = TestDatabase.reset()

    private fun enrolledContact(): Long = runBlocking {
        repo.ensureSequences(DefaultSequences.all())
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val contactId = repo.addContact(Contact(accountId = accountId, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test"))
        repo.enroll(contactId, repo.sequences().first().id)
        contactId
    }

    @Test
    fun `opening the link only asks for confirmation, a scanner cannot opt a prospect out`() = testApplication {
        val contactId = enrolledContact()
        application { routing { unsubscribeRoutes(tokens, repo) } }

        val page = client.get("/u/${tokens.tokenFor(contactId)}")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue("method=\"post\"" in html.lowercase() || "method=post" in html.lowercase(), html)
        assertTrue("Confirmer" in html, html)
        assertFalse(repo.isSuppressed("alex@velomoov.fr"), "GET must not unsubscribe")
        assertEquals(EnrollmentStatus.ACTIVE, repo.enrollmentsOfContact(contactId).single().status)
    }

    @Test
    fun `the confirmation, like the one-click POST, unsubscribes`() = testApplication {
        val contactId = enrolledContact()
        application { routing { unsubscribeRoutes(tokens, repo) } }

        assertEquals(HttpStatusCode.OK, client.post("/u/${tokens.tokenFor(contactId)}").status)
        assertTrue(repo.isSuppressed("alex@velomoov.fr"))
        assertEquals(EnrollmentStatus.STOPPED_UNSUBSCRIBE, repo.enrollmentsOfContact(contactId).single().status)
    }

    @Test
    fun `a bad token is refused on both verbs`() = testApplication {
        application { routing { unsubscribeRoutes(tokens, repo) } }
        assertEquals(HttpStatusCode.BadRequest, client.get("/u/12.deadbeef").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/u/12.deadbeef").status)
    }
}
