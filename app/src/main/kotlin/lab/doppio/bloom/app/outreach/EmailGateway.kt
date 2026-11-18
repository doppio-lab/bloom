package lab.doppio.bloom.app.outreach

import jakarta.mail.Message
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lab.doppio.bloom.core.model.Mailbox
import lab.doppio.bloom.core.port.MailSender
import lab.doppio.bloom.core.port.SendRefused
import org.slf4j.LoggerFactory
import java.util.Properties
import java.util.UUID

class EmailGateway(private val config: MailConfig, private val tokens: UnsubscribeTokens) : MailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    internal fun smtpProperties(): Properties = Properties().apply {
        put("mail.smtp.host", config.smtpHost)
        put("mail.smtp.port", config.smtpPort.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.starttls.required", "true")
        put("mail.smtp.connectiontimeout", MAIL_SOCKET_TIMEOUT_MS)
        put("mail.smtp.timeout", MAIL_SOCKET_TIMEOUT_MS)
        put("mail.smtp.writetimeout", MAIL_SOCKET_TIMEOUT_MS)
    }

    private fun session(mailbox: Mailbox): Session = Session.getInstance(
        smtpProperties(),
        object : jakarta.mail.Authenticator() {
            override fun getPasswordAuthentication() = jakarta.mail.PasswordAuthentication(mailbox.address, mailbox.password)
        },
    )

    /** The one-click unsubscribe link of a contact, used in the footer and in the List-Unsubscribe header. */
    private fun unsubscribeUrl(contactId: Long): String = "${config.publicBaseUrl}/u/${tokens.tokenFor(contactId)}"

    fun footer(contactId: Long, language: String): String {
        val url = unsubscribeUrl(contactId)
        return if (language == "en") MailTexts.footerEn(config.legalFooterEn, url) else MailTexts.footerFr(config.legalFooterFr, url)
    }

    override fun newMessageId(mailbox: Mailbox): String = "<${UUID.randomUUID()}@${mailbox.address.substringAfter('@')}>"

    /**
     * Envoie un email texte brut (meilleure délivrabilité que du HTML en prospection, et pas de pixel de suivi).
     * Renvoie le Message-ID, qui sert à rattacher les réponses à l'inscription.
     */
    override suspend fun send(
        mailbox: Mailbox,
        to: String,
        subject: String,
        body: String,
        contactId: Long,
        language: String,
        inReplyTo: String?,
        messageId: String,
    ): String = withContext(Dispatchers.IO) {
        val unsubscribeUrl = unsubscribeUrl(contactId)
        val fullBody = body + footer(contactId, language)

        if (config.dryRun) {
            log.info("[DRY RUN] {} -> {} | {}\n{}", mailbox.address, to, subject, fullBody)
            return@withContext messageId
        }
        val msg = object : MimeMessage(session(mailbox)) {
            override fun updateMessageID() { setHeader("Message-ID", messageId) }
        }
        msg.setFrom(InternetAddress(mailbox.address, mailbox.displayName, "UTF-8"))
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        msg.setSubject(if (inReplyTo != null && !subject.startsWith("Re:")) "Re: $subject" else subject, "UTF-8")
        msg.setText(fullBody, "UTF-8", "plain")
        // RFC 8058 : désinscription en un clic, exigée par Gmail et Yahoo pour les expéditeurs en volume
        msg.setHeader("List-Unsubscribe", "<$unsubscribeUrl>, <mailto:${mailbox.address}?subject=unsubscribe>")
        msg.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
        if (inReplyTo != null) {
            msg.setHeader("In-Reply-To", inReplyTo)
            msg.setHeader("References", inReplyTo)
        }
        try {
            Transport.send(msg)
        } catch (e: SendFailedException) {
            throw if (isRefusedBeforeAcceptance(e)) SendRefused(e.message, e) else e
        }
        messageId
    }
}

/**
 * True only when the SMTP server refused the message before accepting it: a SendFailedException with no
 * address it was sent to (SMTPSendFailedException, SMTPAddressFailedException). Any other error, a timeout
 * after DATA was accepted among them, may already have delivered the message.
 */
internal fun isRefusedBeforeAcceptance(e: Throwable): Boolean = e is SendFailedException && e.validSentAddresses.isNullOrEmpty()
