package lab.doppio.bloom.core.scoring

import lab.doppio.bloom.core.model.SignalType

/** The reason lines stored with a score and shown in the dashboard, built from the [FitRules] they explain. */
internal object ScoreReasons {
    const val MOBILE_APP = "app mobile en production"
    const val BOTH_STORES = "présent sur les deux stores (terrain KMP)"
    const val CORE_SECTOR = "secteur où Doppio a une référence directe"
    const val MARKET_FR = "marché France"
    const val MARKET_EUROPE = "marché Europe"

    val micro = "moins de ${FitRules.MICRO_MAX_HEADCOUNT + 1} personnes (budget incertain)"
    val small = "${FitRules.MICRO_MAX_HEADCOUNT + 1} à ${FitRules.SMALL_MAX_HEADCOUNT} personnes"
    val mid = "${FitRules.SMALL_MAX_HEADCOUNT + 1} à ${FitRules.MID_MAX_HEADCOUNT} personnes (cœur de cible)"
    val large = "${FitRules.MID_MAX_HEADCOUNT + 1}+ personnes (cycle long, achats)"

    /** "+12 app mobile en production": the points a rule gave, then why. */
    fun fit(points: Int, reason: String) = "+$points $reason"

    /** Points taken off when a sourced fact shows a Flutter or React Native stack, with the fact it rests on. */
    fun crossPlatform(points: Int, factId: String) =
        "-$points stack cross-platform (Flutter ou React Native) : Doppio fait du natif et du KMP [F:$factId]"

    fun signal(points: Int, type: SignalType) = "+$points signal ${type.name.lowercase()}"

    /** The signal lines must add up to the intent shown, so say when the cap took over. */
    fun intentCapped(cap: Int, raw: Int) = "Signaux plafonnés à $cap ($raw avant plafond)"

    fun warmPath(points: Int, path: String?) = "+$points lien chaud : $path"
}
