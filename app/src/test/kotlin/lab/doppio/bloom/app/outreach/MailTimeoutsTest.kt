package lab.doppio.bloom.app.outreach

import kotlin.test.Test
import kotlin.test.assertEquals

class MailTimeoutsTest {
    private val config = MailConfig(
        smtpHost = "smtp.example.org", smtpPort = 587, imapHost = "imap.example.org",
        mailboxes = listOf(Mailbox("me@example.org", "pw", "Me")), publicBaseUrl = "http://localhost:8080",
        unsubscribeSecret = "secret-long-enough-0", legalFooterFr = "fr", legalFooterEn = "en", dryRun = true,
    )

    @Test
    fun `SMTP sessions time out on connect, read and write`() {
        val props = EmailGateway(config, UnsubscribeTokens(config.unsubscribeSecret)).smtpProperties()
        assertEquals("30000", props.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("30000", props.getProperty("mail.smtp.timeout"))
        assertEquals("30000", props.getProperty("mail.smtp.writetimeout"))
        assertEquals("true", props.getProperty("mail.smtp.starttls.required"))
    }

    @Test
    fun `IMAP sessions time out on connect and read`() {
        val props = ImapPoller(config).imapProperties()
        assertEquals("30000", props.getProperty("mail.imaps.connectiontimeout"))
        assertEquals("30000", props.getProperty("mail.imaps.timeout"))
        assertEquals("imaps", props.getProperty("mail.store.protocol"))
    }
}
