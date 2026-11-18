package lab.doppio.bloom.data.persistence

import org.jetbrains.exposed.sql.selectAll
import lab.doppio.bloom.data.persistence.reply.RepliesTable
import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.reply.ExposedReplyRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyRepositoryTest {
    private val replies = ExposedReplyRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `un email entrant n'est enregistré qu'une fois`() = runTest {
        assertFalse(replies.replyRecorded("<r1@client.fr>"))
        replies.saveReply(null, "alex@client.fr", "Re: Objet", "Merci", ReplyIntent.NOT_NOW, 0.9, null, "Plus tard", Instant.now(), "<r1@client.fr>")
        assertTrue(replies.replyRecorded("<r1@client.fr>"))
        assertFails {
            replies.saveReply(null, "alex@client.fr", "Re: Objet", "Merci", ReplyIntent.NOT_NOW, 0.9, null, "Plus tard", Instant.now(), "<r1@client.fr>")
        }
    }

    @Test
    fun `une opposition vaut pour l'adresse exacte ou pour tout le domaine, sans tenir compte de la casse`() = runTest {
        replies.suppress("Alex@Client.fr", "UNSUBSCRIBE")
        replies.suppress("@gros-client.com", "domain")

        assertTrue(replies.isSuppressed("alex@client.fr"))
        assertTrue(replies.isSuppressed("ALEX@CLIENT.FR"))
        assertFalse(replies.isSuppressed("autre@client.fr"), "another address of the same domain is not covered")
        assertTrue(replies.isSuppressed("n.importe.qui@gros-client.com"), "a domain entry covers every address")
        assertFalse(replies.isSuppressed("alex@gros-client.org"))
    }

    @Test
    fun `le texte des réponses de plus de 12 mois est effacé, l'intention et le résumé restent`() = runTest {
        val now = Instant.parse("2026-11-10T08:00:00Z")
        val old = replies.saveReply(null, "a@x.fr", "Re", "texte ancien", ReplyIntent.NOT_NOW, 0.9, null, "Plus tard", now.minus(400, ChronoUnit.DAYS), "<old@x>")
        replies.saveReply(null, "b@x.fr", "Re", "texte récent", ReplyIntent.INTERESTED, 0.9, null, "Intéressé", now.minus(10, ChronoUnit.DAYS), "<new@x>")

        assertEquals(1, replies.purgeOldReplyBodies(now))
        assertEquals(0, replies.purgeOldReplyBodies(now), "a second run has nothing left to blank")

        val rows = dbQuery { RepliesTable.selectAll().associate { it[RepliesTable.id] to (it[RepliesTable.body] to it[RepliesTable.summary]) } }
        assertEquals("" to "Plus tard", rows.getValue(old))
        assertEquals(listOf("texte récent"), rows.filterKeys { it != old }.values.map { it.first })
    }
}
