package lab.doppio.bloom.core.model

import java.time.Instant

/** A sending mailbox. The password stays with the mail adapters, the use cases only pick one by address. */
data class Mailbox(val address: String, val password: String, val displayName: String)

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
