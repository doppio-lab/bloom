package lab.doppio.bloom.data.discovery

/**
 * What a discovery run says about itself: the labels it files accounts and counts under, the notes of the run
 * report shown on the dashboard, and the reason stored when a candidate is dropped.
 */
internal object DiscoveryNotes {
    // --- Labels: stored as Account.source and used as keys of the per-source counts ----------------
    const val JOBS_LABEL = "offres d'emploi"
    const val CHARTS_LABEL = "classement App Store"
    const val DEMANDS_LABEL = "Codeur"

    // --- Run report notes ----------------------------------------------------------------------------
    const val JOBS_DISABLED = "offres d'emploi désactivées : FT_CLIENT_ID absent"
    fun jobsFailed(message: String?) = "offres d'emploi : $message"
    fun chartsFailed(message: String?) = "App Store : $message"
    fun sourceFailed(label: String, message: String?) = "$label : $message"
    fun demandsFailed(message: String?) = "$DEMANDS_LABEL : $message"
    fun jobBoardsFailed(message: String?) = "pages carrières : $message"
    fun jobBoardsRead(read: Int, withMobile: Int) = "pages carrières : $read lues, $withMobile avec une offre mobile"

    // --- Why a candidate was dropped (stored with its seen key) --------------------------------------
    const val PUBLISHER_NOT_IN_REGISTRY = "éditeur non trouvé dans l'annuaire français"
    const val NO_DIFFICULTY_SIGNAL = "aucun signal de difficulté"
    const val NOT_IN_REGISTRY = "non trouvé dans l'annuaire français"
    const val AMBIGUOUS_NAME = "plusieurs entreprises portent ce nom"
    const val NO_SECTOR_NOR_APP = "ni secteur couvert ni app sur l'App Store"
    const val BUYER_NOT_IN_REGISTRY = "acheteur absent de l'annuaire français"
    const val ORGANISATION_OUT_OF_SCOPE = "organisation hors France ou sans site"
    fun belowMinHeadcount(min: Int) = "effectif inférieur à $min"
    fun signalsFound(types: String) = "signaux : $types"
}
