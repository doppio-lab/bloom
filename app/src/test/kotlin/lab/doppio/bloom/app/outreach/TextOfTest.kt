package lab.doppio.bloom.app.outreach

import jakarta.activation.DataHandler
import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class TextOfTest {
    private val poller = ImapPoller(
        MailConfig("smtp", 587, "imap", emptyList(), "http://localhost", "secret-long-enough-0", "fr", "en", dryRun = true),
    )
    private val session = Session.getInstance(Properties())

    @Test
    fun `a multipart message yields its plain text part`() {
        val msg = MimeMessage(session)
        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(MimeBodyPart().apply { setText("Bonjour, je suis absent.", "UTF-8") })
        alternative.addBodyPart(MimeBodyPart().apply { setContent("<p>Bonjour, <b>je suis absent.</b></p>", "text/html; charset=UTF-8") })
        msg.setContent(alternative)
        msg.saveChanges()
        assertEquals("Bonjour, je suis absent.", poller.textOf(msg))
    }

    @Test
    fun `an HTML-only message is turned into text`() {
        val msg = MimeMessage(session)
        msg.setContent("<p>Bonjour, <b>je suis absent.</b></p>", "text/html; charset=UTF-8")
        msg.saveChanges()
        assertEquals("Bonjour, je suis absent.", poller.textOf(msg))
    }

    @Test
    fun `a text part with an unknown charset is read as bytes instead of failing`() {
        val msg = MimeMessage(session)
        msg.dataHandler = DataHandler(ByteArrayDataSource("Bonjour".toByteArray(), "text/plain; charset=x-unknown-123"))
        msg.saveChanges()
        assertEquals("Bonjour", poller.textOf(msg))
    }

    @Test
    fun `an unusual content type gives an empty body, never an exception`() {
        val msg = MimeMessage(session)
        msg.dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(1, 2, 3), "application/x-unknown"))
        msg.saveChanges()
        assertEquals("", poller.textOf(msg))
    }
}
