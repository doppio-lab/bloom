package lab.doppio.bloom.data.sources

import lab.doppio.bloom.core.discovery.HiringRules

/**
 * The sentence of every fact a source states, as the reviewer and the writer agent read it. One function per
 * statement; the callers pass values already formatted (dates, amounts) so the sentences stay plain templates.
 */
internal object FactTemplates {
    /** One decimal with a comma, as French prints a rating. */
    private fun rating(value: Double): String = "%.1f".format(value).replace('.', ',')

    // --- App Store ------------------------------------------------------------------------------------
    fun appRating(name: String, average: Double, ratings: Int, country: String) =
        "$name : note moyenne App Store ${rating(average)} sur $ratings notes ($country)"

    fun appLastRelease(name: String, version: String?, daysAgo: Long) =
        "$name : dernière version $version publiée il y a $daysAgo jours"

    fun appRecentRating(name: String, reviews: Int, windowDays: Long, recentAverage: Double, overallAverage: Double) =
        "$name : moyenne des $reviews avis des $windowDays derniers jours ${rating(recentAverage)}, contre ${rating(overallAverage)} en moyenne globale"

    fun appComplaints(name: String, complaints: Int, reviews: Int, windowDays: Long, mostCitedVersion: String?) =
        "$name : $complaints avis sur $reviews des $windowDays derniers jours signalent un problème technique" +
            (mostCitedVersion?.let { " (version la plus citée : $it)" } ?: "")

    // --- Company registry -----------------------------------------------------------------------------
    fun registryHeadcount(name: String, range: String, label: String) = "$name : tranche d'effectif INSEE $range ($label)"
    fun registryNaf(name: String, naf: String) = "$name : code NAF $naf"
    fun registryCreated(name: String, createdOn: String) = "$name : créée le $createdOn"

    /** INSEE headcount band code to its label. */
    fun headcountBand(code: String): String = when (code) {
        "00" -> "0 salarié"; "01" -> "1 ou 2"; "02" -> "3 à 5"; "03" -> "6 à 9"; "11" -> "10 à 19"
        "12" -> "20 à 49"; "21" -> "50 à 99"; "22" -> "100 à 199"; "31" -> "200 à 249"; "32" -> "250 à 499"
        "41" -> "500 à 999"; "42" -> "1 000 à 1 999"; "51" -> "2 000 à 4 999"; "52" -> "5 000 à 9 999"; "53" -> "10 000 et plus"
        else -> "inconnu"
    }

    // --- Hiring: France Travail and the job boards state a posting the same way ----------------------
    fun jobOffer(title: String, ageDays: Long?) =
        "Offre d'emploi « $title »" + (ageDays?.let { " publiée il y a $it jours" } ?: "")

    /** Written by [HiringRules] because scoring recognizes this statement as evidence of the stack. */
    fun crossPlatformStack(title: String) = HiringRules.crossPlatformStatement(title)

    // --- Web page read by the research agent ----------------------------------------------------------
    fun webPage(title: String) = "Page lue : $title"

    // --- Discovery sources -------------------------------------------------------------------------------
    fun capitalIncrease(name: String, publishedOn: String, capital: String) =
        "$name : augmentation de capital publiée au BODACC le $publishedOn, capital porté à $capital"

    fun publicTender(buyer: String, publishedOn: String, deadline: String?) =
        "$buyer : appel d'offres publié le $publishedOn" + (deadline?.let { ", réponse avant le $it" } ?: "")

    fun publicContract(buyer: String, notifiedOn: String, months: Int, endsOn: String, amount: String?) =
        "$buyer : marché d'application mobile notifié le $notifiedOn pour $months mois, échéance le $endsOn" +
            (amount?.let { ", montant $it" } ?: "")

    fun githubRepository(organisation: String, withinDays: Long) =
        "$organisation : dépôt public Kotlin Multiplatform mis à jour ces $withinDays derniers jours"

    fun fundingRound(company: String, amount: String, announcedOn: String) = "$company : levée de $amount annoncée le $announcedOn"
}
