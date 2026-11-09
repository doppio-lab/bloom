package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.SignalType

/** The labels the dashboard shows for each dominant signal in the conversion table. */
internal object OverviewLabels {
    const val NO_SIGNAL = "Sans signal"

    private val signalLabels = mapOf(
        SignalType.REFERRAL to "Lien chaud", SignalType.HIRING_STUCK to "Recrutement bloqué", SignalType.QUALITY_COMPLAINTS to "Plaintes qualité (avis)",
        SignalType.RATING_DROP to "Note en baisse", SignalType.HIRING_KMP to "Recrutement KMP", SignalType.NEW_TECH_LEADER to "Nouveau dirigeant tech",
        SignalType.FUNDING to "Levée de fonds", SignalType.HIRING_MOBILE to "Recrutement mobile", SignalType.PRODUCT_LAUNCH to "Lancement produit",
        SignalType.RELEASE_STALL to "App sans mise à jour", SignalType.CONTENT_ENGAGEMENT to "Interaction LinkedIn", SignalType.AI_ADOPTION to "Équipe LLM",
        SignalType.PUBLIC_TENDER to "Marché public", SignalType.KMP_ADOPTION to "Code KMP public",
        SignalType.CONTRACT_RENEWAL to "Marché à renouveler",
    )

    fun signal(type: SignalType?): String = signalLabels[type] ?: NO_SIGNAL
}
