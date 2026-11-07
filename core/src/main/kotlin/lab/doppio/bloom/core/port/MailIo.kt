package lab.doppio.bloom.core.port

import lab.doppio.bloom.core.model.InboundMail
import lab.doppio.bloom.core.model.Mailbox

/** Sends the outreach emails. Implemented by the SMTP gateway (or by a log in dry run). */
interface MailSender {
    /** Chosen before the send and stored with the draft, so a reply can always be matched to it. */
    fun newMessageId(mailbox: Mailbox): String

    /** Sends one plain text email, footer included. Returns its Message-ID. */
    suspend fun send(
        mailbox: Mailbox,
        to: String,
        subject: String,
        body: String,
        contactId: Long,
        language: String,
        inReplyTo: String?,
        messageId: String,
    ): String
}

/** Reads the replies that landed in the sending mailboxes. Nothing is deleted. */
interface Inbox {
    /** Unread messages of every mailbox. */
    suspend fun poll(): List<InboundMail>

    /** Marks messages seen once they were processed. */
    suspend fun markSeen(mailboxAddress: String, uids: List<Long>)
}
