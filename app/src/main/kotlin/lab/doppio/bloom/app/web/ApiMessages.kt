package lab.doppio.bloom.app.web

/** Every sentence the dashboard API, the CSV export and the unsubscribe page say. Moved here verbatim, never reworded in passing. */
internal object ApiMessages {
    // --- Statuses answered by the action endpoints ---------------------------------------------------
    const val STARTED = "lancée"
    const val NOT_REQUESTED = "non demandée"
    const val ALREADY_RUNNING = "déjà en cours"
    const val DEMAND_DISMISSED = "écartée"
    const val ACCOUNT_EXCLUDED = "écarté"
    const val ACCOUNT_RESTORED = "réintégré"
    const val NAME_BLOCKED = "ajouté"
    const val NAME_UNBLOCKED = "retiré"

    // --- Errors ---------------------------------------------------------------------------------------
    const val INTERNAL_ERROR = "erreur interne"
    const val CROSS_SITE_REFUSED = "requête refusée : elle ne vient pas du dashboard"
    const val DRAFT_NOT_PENDING = "ce brouillon n'est plus à valider"
    const val TASK_NOT_PENDING = "cette tâche n'est plus à faire"
    const val CONTACT_OPTED_OUT = "Ce contact s'est opposé à la prospection"
    const val CONTACT_ALREADY_ENROLLED = "Ce contact est déjà inscrit dans une séquence"
    const val NO_FIT = "Le dossier conclut qu'aucune offre ne convient : choisir l'offre explicitement"
    const val DEMAND_NOT_FOUND = "Demande introuvable"
    const val NAME_REQUIRED = "Nom obligatoire"

    /** Reason stored when the account is excluded from the dashboard without one. */
    const val EXCLUDED_BY_HAND = "écarté à la main"

    // --- What happens next after adding a contact ---------------------------------------------------
    const val SEQUENCE_STARTED = "Séquence lancée"
    const val CONTACT_ADDED = "Contact ajouté"
    const val CONTACT_ADDED_NEEDS_EMAIL = "Contact ajouté. Ajoutez son email pour lancer la séquence."
    const val CONTACT_ADDED_SEARCHING_EMAIL = "Contact ajouté, recherche de l'email en cours"

    // --- Contact book export ------------------------------------------------------------------------
    const val CSV_FILE_NAME = "carnet-bloom.csv"
    const val CSV_HEADER = "entreprise;nom;role;email;linkedin;statut;source"
    const val CSV_STATUS_CONTACT = "contact"
    const val CSV_STATUS_SUGGESTION = "suggestion"

    // --- Unsubscribe page ---------------------------------------------------------------------------
    const val UNSUBSCRIBE_INVALID_LINK = "Lien invalide."

    fun unsubscribeConfirmation(): String =
        "<!doctype html><meta charset=utf-8><title>Désinscription</title><body style='font-family:system-ui;max-width:32rem;margin:4rem auto'>" +
            "<h1>C'est fait.</h1><p>Vous ne recevrez plus de message de Doppio Lab. Désolé pour le dérangement.</p></body>"
}
