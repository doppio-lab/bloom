package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.reply.ExposedReplyRepository
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
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
}
