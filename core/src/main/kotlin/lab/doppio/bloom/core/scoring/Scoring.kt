package lab.doppio.bloom.core.scoring

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Score de priorité d'un compte, entièrement déterministe et explicable.
 *
 * score = fit ICP (0..40) + intention (0..50, signaux pondérés et amortis dans le temps) + lien chaud (0..10)
 *
 * Le LLM n'intervient pas ici : un score qu'on ne peut pas expliquer ligne par ligne
 * ne sert à rien pour décider qui appeler lundi matin.
 */
@Serializable
data class ScoreBreakdown(
    val accountId: Long,
    val total: Int,
    val fit: Int,
    val intent: Int,
    val warm: Int,
    val recommendedOffer: Offer?,
    val reasons: List<String>,
)

object Scoring {

    /** Poids de base et demi-vie (jours) par type de signal. Hypothèses à recalibrer avec les données de réponse. */
    data class SignalRule(val weight: Double, val halfLifeDays: Double)

    val rules: Map<SignalType, SignalRule> = mapOf(
        SignalType.REFERRAL to SignalRule(30.0, 60.0),
        SignalType.HIRING_STUCK to SignalRule(22.0, 30.0),
        SignalType.QUALITY_COMPLAINTS to SignalRule(20.0, 30.0),
        SignalType.RATING_DROP to SignalRule(18.0, 45.0),
        SignalType.HIRING_KMP to SignalRule(18.0, 45.0),
        SignalType.NEW_TECH_LEADER to SignalRule(16.0, 90.0),
        SignalType.FUNDING to SignalRule(15.0, 90.0),
        SignalType.HIRING_MOBILE to SignalRule(12.0, 30.0),
        SignalType.PRODUCT_LAUNCH to SignalRule(12.0, 60.0),
        SignalType.RELEASE_STALL to SignalRule(10.0, 120.0),
        SignalType.CONTENT_ENGAGEMENT to SignalRule(8.0, 14.0),
        SignalType.AI_ADOPTION to SignalRule(6.0, 90.0),
    )

    /** Offre la plus naturelle pour chaque signal (voir doc de cadrage, matrice signal vers offre). */
    val offerBySignal: Map<SignalType, Offer> = mapOf(
        SignalType.QUALITY_COMPLAINTS to Offer.DISCOVERY,
        SignalType.RATING_DROP to Offer.DISCOVERY,
        SignalType.RELEASE_STALL to Offer.DISCOVERY,
        SignalType.HIRING_STUCK to Offer.REGIE,
        SignalType.HIRING_MOBILE to Offer.REGIE,
        SignalType.HIRING_KMP to Offer.TRAINING_KMP,
        SignalType.FUNDING to Offer.FORFAIT,
        SignalType.PRODUCT_LAUNCH to Offer.FORFAIT,
        SignalType.NEW_TECH_LEADER to Offer.DISCOVERY,
        SignalType.AI_ADOPTION to Offer.TRAINING_ANTHROPIC,
        SignalType.CONTENT_ENGAGEMENT to Offer.DISCOVERY,
        SignalType.REFERRAL to Offer.DISCOVERY,
    )

    private val coreSectors = setOf(
        Sector.BANK_INSURANCE, Sector.RETAIL, Sector.LOGISTICS, Sector.IOT_HARDWARE,
        Sector.PRODUCTIVITY_SAAS, Sector.MUSIC_MEDIA, Sector.MOBILITY_TRANSPORT, Sector.PRIVACY,
    )

    fun fit(account: Account, reasons: MutableList<String>): Int {
        var fit = 0
        if (account.iosAppId != null || account.androidPackage != null) {
            fit += 12; reasons += "+12 app mobile en production"
        }
        if (account.iosAppId != null && account.androidPackage != null) {
            fit += 4; reasons += "+4 présent sur les deux stores (terrain KMP)"
        }
        when (account.headcount) {
            null -> {}
            in 0..14 -> { fit += 2; reasons += "+2 moins de 15 personnes (budget incertain)" }
            in 15..49 -> { fit += 8; reasons += "+8 15 à 49 personnes" }
            in 50..499 -> { fit += 12; reasons += "+12 50 à 499 personnes (cœur de cible)" }
            else -> { fit += 9; reasons += "+9 500+ personnes (cycle long, achats)" }
        }
        if (account.sector in coreSectors) {
            fit += 8; reasons += "+8 secteur où Doppio a une référence directe"
        }
        when (account.market) {
            Market.FR -> { fit += 4; reasons += "+4 marché France" }
            Market.EU, Market.UK -> { fit += 2; reasons += "+2 marché Europe" }
            else -> {}
        }
        return fit.coerceAtMost(40)
    }

    fun decay(observedAt: Instant, now: Instant, halfLifeDays: Double): Double {
        val ageDays = Duration.between(observedAt, now).toHours().coerceAtLeast(0) / 24.0
        return exp(-ln(2.0) * ageDays / halfLifeDays)
    }

    fun score(account: Account, signals: List<Signal>, now: Instant = Instant.now()): ScoreBreakdown {
        val reasons = mutableListOf<String>()
        val fit = fit(account, reasons)

        // Un seul signal par type compte (le plus fort après amortissement), pour éviter qu'une
        // série de 10 avis négatifs écrase le reste.
        val bestByType = signals
            .groupBy { it.type }
            .mapValues { (type, list) ->
                val rule = rules.getValue(type)
                list.maxOf { rule.weight * it.strength.coerceIn(0.0, 1.0) * decay(Instant.parse(it.observedAt), now, rule.halfLifeDays) }
            }
            .filterValues { it >= 0.5 }

        val intentRaw = bestByType.values.sum()
        val intent = intentRaw.coerceAtMost(50.0).roundToInt()
        bestByType.entries.sortedByDescending { it.value }.forEach { (type, v) ->
            reasons += "+${v.roundToInt()} signal ${type.name.lowercase()}"
        }

        val warm = if (account.warmPath != null) 10 else 0
        if (warm > 0) reasons += "+10 lien chaud : ${account.warmPath}"

        val recommended = bestByType.maxByOrNull { it.value }?.key?.let { offerBySignal[it] }

        return ScoreBreakdown(
            accountId = account.id,
            total = fit + intent + warm,
            fit = fit,
            intent = intent,
            warm = warm,
            recommendedOffer = recommended,
            reasons = reasons,
        )
    }

    /** Seuils d'action. Sous 45, on surveille ; au-dessus de 65, on écrit cette semaine. */
    fun tier(total: Int): Tier = when {
        total >= 65 -> Tier.ACT_NOW
        total >= 45 -> Tier.NURTURE
        else -> Tier.WATCH
    }

    enum class Tier { ACT_NOW, NURTURE, WATCH }
}
