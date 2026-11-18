package lab.doppio.bloom.app.outreach

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Mailbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmailGatewayInputTest {
    private val mailbox = Mailbox("me@example.org", "pw", "Me")
    private val config = MailConfig(
        smtpHost = "smtp.example.org", smtpPort = 587, imapHost = "imap.example.org", mailboxes = listOf(mailbox),
        publicBaseUrl = "http://localhost:8080", unsubscribeSecret = "secret-long-enough-0", legalFooterFr = "fr", legalFooterEn = "en", dryRun = true,
    )
    private val gateway = EmailGateway(config, UnsubscribeTokens(config.unsubscribeSecret))

    @Test
    fun `a recipient that is a list or carries a header is refused before anything is sent`() = runTest {
        for (bad in listOf("a@x.fr, b@y.fr", "a@x.fr\r\nBcc: z@y.fr", "Alex <a@x.fr>")) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { gateway.send(mailbox, bad, "Objet", "Corps", 1, "fr", null, "<1@x>") }
            assertEquals("Adresse email invalide : « ${bad.trim()} »", e.message)
        }
    }

    @Test
    fun `a valid recipient goes through`() = runTest {
        assertEquals("<1@x>", gateway.send(mailbox, " A@X.fr ", "Objet\r\nBcc: z@y.fr", "Corps", 1, "fr", null, "<1@x>"))
    }
}
