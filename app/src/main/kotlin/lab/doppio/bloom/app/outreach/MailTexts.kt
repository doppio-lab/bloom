package lab.doppio.bloom.app.outreach

/** What every outgoing email says around the message: the legal footer, the unsubscribe line, the sender name. */
object MailTexts {
    const val DEFAULT_SENDER_NAME = "l'équipe Doppio"

    const val DEFAULT_LEGAL_FOOTER_FR =
        "Doppio Lab (Advisely), Paris. Vos coordonnées professionnelles proviennent de sources publiques " +
            "(site de votre entreprise, annuaire des entreprises) et ne servent qu'à ce contact."
    const val DEFAULT_LEGAL_FOOTER_EN =
        "Doppio Lab (Advisely), Paris, France. Your business contact details come from public sources and are used only for this contact."

    fun footerFr(legalFooter: String, unsubscribeUrl: String): String =
        "\n\n--\n$legalFooter\nPas intéressé ? Un clic et vous ne recevrez plus rien de notre part : $unsubscribeUrl"

    fun footerEn(legalFooter: String, unsubscribeUrl: String): String =
        "\n\n--\n$legalFooter\nNot interested? One click and you will not hear from us again: $unsubscribeUrl"
}
