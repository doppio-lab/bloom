package lab.doppio.bloom.app.outreach

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.UIDFolder
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
    /** IMAP UID, to mark the message seen once it was processed. */
    val uid: Long,
    /** Identifies the message across passes: its Message-ID, or the mailbox UID when it has none. */
    val key: String,
)

/**
 * Relève IMAP des boîtes d'envoi. Rien n'est supprimé. Un message n'est marqué vu qu'après traitement
 * ([markSeen]) : s'il échoue, il sera relu à la passe suivante.
 */
class ImapPoller(private val config: MailConfig) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val messageIdPattern = Regex("<[^<>\\s@]+@[^<>\\s]+>")

    suspend fun poll(): List<InboundMail> = withContext(Dispatchers.IO) {
        if (config.dryRun) return@withContext emptyList()
        config.mailboxes.flatMap { mb ->
            runCatching { pollOne(mb) }.getOrElse { log.warn("IMAP en échec pour ${mb.address}", it); emptyList() }
        }
    }

    suspend fun markSeen(mailboxAddress: String, uids: List<Long>) = withContext(Dispatchers.IO) {
        if (uids.isEmpty()) return@withContext
        val mb = config.mailboxes.firstOrNull { it.address == mailboxAddress } ?: return@withContext
        withInbox(mb, Folder.READ_WRITE) { inbox ->
            (inbox as UIDFolder).getMessagesByUID(uids.toLongArray()).filterNotNull().forEach { it.setFlag(Flags.Flag.SEEN, true) }
        }
    }

    private fun <T> withInbox(mb: Mailbox, mode: Int, block: (Folder) -> T): T {
        val session = Session.getInstance(Properties().apply { put("mail.store.protocol", "imaps") })
        val store = session.getStore("imaps")
        store.connect(config.imapHost, mb.address, mb.password)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(mode)
            return block(inbox).also { inbox.close(false) }
        } finally {
            store.close()
        }
    }

    private fun pollOne(mb: Mailbox): List<InboundMail> = withInbox(mb, Folder.READ_ONLY) { inbox ->
        val uidFolder = inbox as UIDFolder
        inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false)).map { m ->
            val from = (m.from?.firstOrNull() as? InternetAddress)?.address ?: m.from?.firstOrNull()?.toString().orEmpty()
            val refs = (m.getHeader("In-Reply-To")?.toList().orEmpty() + m.getHeader("References")?.toList().orEmpty())
                .flatMap { it.split(Regex("\\s+")) }.filter { it.startsWith("<") }
            val bounce = from.contains("mailer-daemon", true) || from.contains("postmaster", true) ||
                (m.contentType?.contains("report-type=delivery-status", true) == true)
            // A bounce rarely has In-Reply-To: the Message-ID of the original sits in the returned headers
            val bounceRefs = if (bounce) messageIdPattern.findAll(raw(m)).map { it.value }.toList() else emptyList()
            val uid = uidFolder.getUID(m)
            val key = m.getHeader("Message-ID")?.firstOrNull()?.trim() ?: "${mb.address}/${uidFolder.uidValidity}/$uid"
            InboundMail(mb.address, from, m.subject, textOf(m), (refs + bounceRefs).distinct(),
                m.receivedDate?.toInstant() ?: Instant.now(), bounce, uid, key)
        }
    }

    /** The whole message as text, capped: enough for the headers of a returned message. */
    private fun raw(m: Message): String {
        val out = java.io.ByteArrayOutputStream()
        runCatching { m.writeTo(out) }
        return out.toString(Charsets.UTF_8).take(200_000)
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
