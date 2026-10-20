package lab.doppio.bloom.data

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.db.Contacts
import lab.doppio.bloom.data.db.Events
import lab.doppio.bloom.data.db.Replies
import lab.doppio.bloom.data.db.Repository
import lab.doppio.bloom.data.db.dbQuery
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GdprPurgeTest {
    private val repo = Repository()
    private val now = Instant.parse("2026-10-20T04:00:00Z")

    @BeforeTest
    fun clean() = TestDatabase.reset()

    private data class Trail(val contactId: Long, val enrollmentId: Long, val draftId: Long, val replyId: Long)

    /** A contact with everything a sequence leaves behind: an enrollment, a draft, a reply and an event. */
    private suspend fun contactWithTrail(name: String, createdAt: Instant): Trail {
        repo.ensureSequences(DefaultSequences.all())
        val accountId = repo.upsertAccount(Account(name = name))
        val contactId = repo.addContact(Contact(accountId = accountId, fullName = "Alex $name", role = "CTO", persona = Persona.CTO, email = "alex@$name.fr", dataSource = "test"))
        dbQuery { Contacts.update({ Contacts.id eq contactId }) { it[Contacts.createdAt] = createdAt } }
        val enrollmentId = repo.enroll(contactId, repo.sequences().first().id)
        val draftId = repo.createDraft(Draft(enrollmentId = enrollmentId, stepIndex = 0, channel = Channel.EMAIL, subject = "Objet", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = DraftStatus.SENT))
        val replyId = repo.saveReply(enrollmentId, "alex@$name.fr", "Re", "Merci", ReplyIntent.NOT_NOW, 0.9, null, "Plus tard", createdAt.plusSeconds(60))
        repo.logEvent("SENT", accountId, contactId, draftId)
        return Trail(contactId, enrollmentId, draftId, replyId)
    }

    private suspend fun replyRow(id: Long) = dbQuery { Replies.selectAll().where { Replies.id eq id }.firstOrNull() }
    private suspend fun eventsOf(contactId: Long) = dbQuery { Events.selectAll().where { Events.contactId eq contactId }.count() }

    @Test
    fun `la purge RGPD efface tout ce qu'un contact inactif a laissé`() = runTest {
        val old = contactWithTrail("Alpha", now.minus(4 * 365L, ChronoUnit.DAYS))
        val young = contactWithTrail("Beta", now.minus(30, ChronoUnit.DAYS))

        assertEquals(1, repo.purgeStaleContacts(now))

        assertNull(repo.contact(old.contactId))
        assertNull(repo.enrollment(old.enrollmentId))
        assertNull(repo.draft(old.draftId))
        assertNull(replyRow(old.replyId))
        assertEquals(0, eventsOf(old.contactId))

        assertNotNull(repo.contact(young.contactId))
        assertNotNull(repo.enrollment(young.enrollmentId))
        assertNotNull(repo.draft(young.draftId))
        assertNotNull(replyRow(young.replyId))
        assertEquals(1, eventsOf(young.contactId))
    }

    @Test
    fun `le texte des réponses de plus de 12 mois est effacé, pas leur intention`() = runTest {
        val old = contactWithTrail("Alpha", now.minus(400, ChronoUnit.DAYS))
        val young = contactWithTrail("Beta", now.minus(60, ChronoUnit.DAYS))

        assertEquals(1, repo.purgeOldReplyBodies(now))

        val blanked = replyRow(old.replyId)!!
        assertEquals("", blanked[Replies.body])
        assertEquals(ReplyIntent.NOT_NOW.name, blanked[Replies.intent])
        assertEquals("Plus tard", blanked[Replies.summary])
        assertEquals("Merci", replyRow(young.replyId)!![Replies.body])
        assertEquals(0, repo.purgeOldReplyBodies(now), "already blanked bodies are not counted again")
    }
}
