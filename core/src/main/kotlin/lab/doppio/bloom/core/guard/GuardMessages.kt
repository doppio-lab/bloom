package lab.doppio.bloom.core.guard

/** The detail line of every violation [MessageGuard] raises: what the reviewer reads next to a blocked draft. */
object GuardMessages {
    const val EM_DASH = "remplacer par une virgule, un point ou des parenthèses"
    const val EMOJI = "aucun emoji en prospection"
    const val EXCLAMATION = "ton trop vendeur"
    const val SUBJECT_MISSING = "objet manquant"
    const val FAKE_REPLY_PREFIX = "faux « Re: » interdit"
    const val NO_EVIDENCE = "le premier email doit s'appuyer sur au moins un fait sourcé"
    const val NO_LINK_IN_FIRST_EMAIL = "pas de lien dans le premier email (délivrabilité)"
    const val NO_QUESTION = "aucune question : terminer par une demande simple"
    const val TOO_MANY_QUESTIONS = "trop de questions, en garder une"

    fun bannedPhrase(phrase: String) = "expression interdite : « $phrase »"
    fun emailTooLong(words: Int, max: Int) = "$words mots, maximum $max pour cette étape"
    fun subjectTooLong(words: Int, min: Int, max: Int) = "objet de $words mots, viser $min à $max"
    fun linkedinTooLong(chars: Int, max: Int) = "$chars caractères, maximum $max pour une note LinkedIn"
    fun unknownCitation(factId: String) = "la citation F:$factId ne correspond à aucun fait du dossier"
    fun unsourcedNumber(number: String) = "« $number » n'apparaît dans aucun fait cité"
    fun unknownReference(key: String?) = "référence inconnue : $key"
    fun irrelevantReference(client: String) = "$client n'est pas pertinent pour ce secteur et cette offre"
    fun undeclaredReference(client: String) = "$client est cité sans être déclaré comme référence"
    fun uncitableReferenceDetail(client: String) = "$client : vérifier que le détail est citable"
    fun externalLink(url: String) = "lien externe non autorisé : $url"
}
