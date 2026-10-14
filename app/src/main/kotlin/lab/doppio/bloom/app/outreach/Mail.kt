package lab.doppio.bloom.app.outreach

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Properties
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Mailbox(val address: String, val password: String, val displayName: String)

data class MailConfig(
    val smtpHost: String,
    val smtpPort: Int,
    val imapHost: String,
    val mailboxes: List<Mailbox>,
    val publicBaseUrl: String,
    val unsubscribeSecret: String,
    /** Identité de l'expéditeur et source des données : obligation d'information RGPD art. 14 et CNIL. */
    val legalFooterFr: String,
    val legalFooterEn: String,
    /** Tant que dryRun est vrai, rien ne part : les emails sont journalisés. Valeur par défaut. */
    val dryRun: Boolean,
)

/** Jetons de désinscription signés : pas de base à consulter pour vérifier un lien. */
class UnsubscribeTokens(private val secret: String) {
    private fun mac(data: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }

    fun tokenFor(contactId: Long): String = "$contactId.${mac(contactId.toString())}"

    fun verify(token: String): Long? {
        val id = token.substringBefore('.').toLongOrNull() ?: return null
        return id.takeIf { tokenFor(id) == token }
    }
}

class EmailGateway(private val config: MailConfig, private val tokens: UnsubscribeTokens) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun session(mailbox: Mailbox): Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
        },
        object : jakarta.mail.Authenticator() {
            override fun getPasswordAuthentication() = jakarta.mail.PasswordAuthentication(mailbox.address, mailbox.password)
        },
    )

    fun footer(contactId: Long, language: String): String {
        val url = "${config.publicBaseUrl}/u/${tokens.tokenFor(contactId)}"
        return if (language == "en") "\n\n--\n${config.legalFooterEn}\nNot interested? One click and you will not hear from us again: $url"
        else "\n\n--\n${config.legalFooterFr}\nPas intéressé ? Un clic et vous ne recevrez plus rien de notre part : $url"
    }

    /**
     * Envoie un email texte brut (meilleure délivrabilité que du HTML en prospection, et pas de pixel de suivi).
     * Renvoie le Message-ID, qui sert à rattacher les réponses à l'inscription.
     */
    /** Chosen before the send and stored with the draft, so a reply can always be matched to it. */
    fun newMessageId(mailbox: Mailbox): String = "<${UUID.randomUUID()}@${mailbox.address.substringAfter('@')}>"

    suspend fun send(
        mailbox: Mailbox,
        to: String,
        subject: String,
        body: String,
        contactId: Long,
        language: String,
        inReplyTo: String?,
        messageId: String,
    ): String = withContext(Dispatchers.IO) {
        val unsubscribeUrl = "${config.publicBaseUrl}/u/${tokens.tokenFor(contactId)}"
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
        Transport.send(msg)
        messageId
    }
}

data class InboundMail(
    val mailbox: String,
    val from: String,
    val subject: String?,
    val body: String,
    val inReplyTo: List<String>,
    val receivedAt: Instant,
    val isBounce: Boolean,
)

/** Relève IMAP des boîtes d'envoi. Les messages lus sont marqués vus, rien n'est supprimé. */
class ImapPoller(private val config: MailConfig) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun poll(): List<InboundMail> = withContext(Dispatchers.IO) {
        if (config.dryRun) return@withContext emptyList()
        config.mailboxes.flatMap { mb ->
            runCatching { pollOne(mb) }.getOrElse { log.warn("IMAP en échec pour ${mb.address}", it); emptyList() }
        }
    }

    private fun pollOne(mb: Mailbox): List<InboundMail> {
        val session = Session.getInstance(Properties().apply { put("mail.store.protocol", "imaps") })
        val store = session.getStore("imaps")
        store.connect(config.imapHost, mb.address, mb.password)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            val unseen = inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
            return unseen.map { m ->
                val from = (m.from?.firstOrNull() as? InternetAddress)?.address ?: m.from?.firstOrNull()?.toString().orEmpty()
                val refs = (m.getHeader("In-Reply-To")?.toList().orEmpty() + m.getHeader("References")?.toList().orEmpty())
                    .flatMap { it.split(Regex("\\s+")) }.filter { it.startsWith("<") }
                val bounce = from.contains("mailer-daemon", true) || from.contains("postmaster", true) ||
                    (m.contentType?.contains("report-type=delivery-status", true) == true)
                m.setFlag(Flags.Flag.SEEN, true)
                InboundMail(mb.address, from, m.subject, textOf(m), refs, m.receivedDate?.toInstant() ?: Instant.now(), bounce)
            }.also { inbox.close(false) }
        } finally {
            store.close()
        }
    }

    private fun textOf(p: Part): String = when {
        p.isMimeType("text/plain") -> p.content as String
        p.isMimeType("text/html") -> org.jsoup.Jsoup.parse(p.content as String).text()
        p.isMimeType("multipart/*") -> {
            val mp = p.content as Multipart
            (0 until mp.count).map { mp.getBodyPart(it) }.let { parts ->
                parts.firstOrNull { it.isMimeType("text/plain") }?.let { textOf(it) } ?: parts.joinToString("\n") { textOf(it) }
            }
        }
        else -> ""
    }
}
