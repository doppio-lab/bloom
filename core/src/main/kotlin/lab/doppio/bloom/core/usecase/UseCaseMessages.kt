package lab.doppio.bloom.core.usecase

/**
 * What the use cases say to the dashboard (validation errors, import report lines) and what they store for people
 * to read later (where a contact came from, why an address is suppressed).
 */
internal object UseCaseMessages {
    // --- AddContact ---------------------------------------------------------------------------------
    const val NAME_AND_ROLE_REQUIRED = "Nom et rôle sont obligatoires"
    const val PERSON_OPTED_OUT = "Cette personne s'est opposée à la prospection"

    // --- Where a contact came from (stored as Contact.dataSource) ---------------------------------
    const val SOURCE_REGISTRY_OFFICER = "Annuaire des entreprises (dirigeant publié au registre)"
    const val SOURCE_MANUAL_ENTRY = "saisie manuelle (profil public LinkedIn ou site de l'entreprise)"
    const val SOURCE_IMPORTED_LIST = "import de la liste Doppio"
    fun sourceDropcontact(qualification: String) = "Dropcontact ($qualification) à partir du nom saisi"

    // --- ImportAccounts -----------------------------------------------------------------------------
    /** Role stored when the imported contact has none. */
    const val UNKNOWN_ROLE = "inconnu"
    fun importMissingName(line: Int) = "ligne $line : nom manquant"
    fun importContactOptedOut(line: Int, email: String) = "ligne $line : $email s'est opposé à la prospection, contact ignoré"
    fun importRowFailed(line: Int, message: String?) = "ligne $line : $message"

    // --- Suppression reasons (stored with the suppressed address) -----------------------------------
    const val BOUNCE_SUMMARY = "Rebond : adresse invalide"
    const val SUPPRESSED_BOUNCE = "rebond"
    const val SUPPRESSED_UNSUBSCRIBE_LINK = "lien de désinscription"
    fun suppressedByReply(summary: String) = "réponse : $summary"
}
