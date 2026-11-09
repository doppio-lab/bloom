package lab.doppio.bloom.app.outreach

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lab.doppio.bloom.core.model.InboundMail
import lab.doppio.bloom.core.model.Mailbox
import lab.doppio.bloom.core.port.Inbox
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * Relève IMAP des boîtes d'envoi. Rien n'est supprimé. Un message n'est marqué vu qu'après traitement
 * ([markSeen]) : s'il échoue, il sera relu à la passe suivante.
 */
class ImapPoller(private val config: MailConfig, private val clock: java.time.Clock = java.time.Clock.systemDefaultZone()) : Inbox {
    private val log = LoggerFactory.getLogger(javaClass)
    private val messageIdPattern = Regex("<[^<>\\s@]+@[^<>\\s]+>")

    private companion object {
        /** A returned message is read for its headers only: no need to keep more than this. */
        const val RAW_MESSAGE_CHARS = 200_000
    }

    override suspend fun poll(): List<InboundMail> = withContext(Dispatchers.IO) {
        if (config.dryRun) return@withContext emptyList()
        config.mailboxes.flatMap { mb ->
            runCatching { pollOne(mb) }.getOrElse { log.warn("IMAP en échec pour ${mb.address}", it); emptyList() }
        }
    }

    override suspend fun markSeen(mailboxAddress: String, uids: List<Long>) = withContext(Dispatchers.IO) {
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
                m.receivedDate?.toInstant() ?: clock.instant(), bounce, uid, key)
        }
    }

    /** The whole message as text, capped: enough for the headers of a returned message. */
    private fun raw(m: Message): String {
        val out = java.io.ByteArrayOutputStream()
        runCatching { m.writeTo(out) }
        return out.toString(Charsets.UTF_8).take(RAW_MESSAGE_CHARS)
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
