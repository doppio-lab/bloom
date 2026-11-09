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
