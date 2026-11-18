package lab.doppio.bloom.app.outreach

import lab.doppio.bloom.core.model.Mailbox

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

/**
 * Socket timeouts (connect, read and write) of the SMTP and IMAP sessions, in milliseconds. Jakarta Mail waits forever
 * by default, so a server that stopped answering would hold the sending or the reply loop until the next restart.
 */
internal const val MAIL_SOCKET_TIMEOUT_MS = "30000"
