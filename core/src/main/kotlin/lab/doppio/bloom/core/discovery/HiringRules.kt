package lab.doppio.bloom.core.discovery

import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import java.time.Instant

/** Rules shared by every source of job postings (France Travail, hosted job boards). */
object HiringRules {
    /** Strength of a posting for a mobile role. */
    const val MOBILE_STRENGTH = 0.7

    /** Strength of a posting that names Kotlin Multiplatform: the stack Doppio trains and staffs. */
    const val KMP_STRENGTH = 0.9

    /** A posting open for more than this many days means the hire is stuck. */
    const val STUCK_AFTER_DAYS = 45L

    /** Days past [STUCK_AFTER_DAYS] it takes to reach the full strength. */
    private const val STUCK_RAMP_DAYS = 60.0

    /** A stuck posting never weighs less than this. */
    private const val STUCK_MIN_STRENGTH = 0.3

    // Native mobile stacks are enough on their own. Cross-platform stacks are not: Doppio builds native
    // (and Kotlin Multiplatform) apps, so a Flutter or React Native opening is not a need it can serve.
    private val nativeTitle = Regex("(?i)\\b(ios|android|swiftui|kotlin)\\b")
    // "Swift" is also the bank messaging network and "mobile" also names telecom sales or heavy machinery:
    // both count only next to a word that says the role builds software.
    private val ambiguousTitle = Regex("(?i)\\b(swift|mobiles?)\\b")
    private val developerWord = Regex("(?i)d[ée]velopp|developer|\\bdev\\b|engineer|ing[ée]nieur|architect|\\blead\\b|\\bsoftware\\b|\\bapps?\\b|\\bapplications?\\b")
    // "Business developer" and "développeur commercial" sell, they do not build
    private val salesRole = Regex("(?i)business develop|d[ée]veloppeu(r|se) (commercial|d'affaires)|sales|commercial")
    private val kmp = Regex("(?i)(kotlin multiplatform|compose multiplatform|\\bkmp\\b|\\bcmp\\b)")
    private val crossPlatform = Regex("(?i)(flutter|react native)")

    fun isMobile(title: String): Boolean =
        nativeTitle.containsMatchIn(title) || (ambiguousTitle.containsMatchIn(title) && developerWord.containsMatchIn(title) && !salesRole.containsMatchIn(title))
    fun mentionsKmp(text: String): Boolean = kmp.containsMatchIn(text)
    fun mentionsCrossPlatform(text: String): Boolean = crossPlatform.containsMatchIn(text)

    // "Migration depuis Flutter", "replace React Native": the posting is about leaving the stack, which is good news
    private val leavingCrossPlatform = Regex("(?i)(migr|replac|remplac|abandon|depuis|from|quitt|sortir de|away from)\\p{L}*[^.]{0,40}(flutter|react native)")

    /** True when the text says the company builds on a cross-platform stack, not when it only quotes one to leave it. */
    fun indicatesCrossPlatformStack(text: String): Boolean = mentionsCrossPlatform(text) && !leavingCrossPlatform.containsMatchIn(text)

    /** Marker shared by the statement a source writes and the check [isCrossPlatformEvidence] makes on it. */
    private const val CROSS_PLATFORM_MARK = "stack cross-platform"

    fun crossPlatformStatement(title: String): String = "L'offre « $title » mentionne une $CROSS_PLATFORM_MARK (Flutter ou React Native)"

    /** A sourced fact that states a cross-platform stack: the only evidence scoring accepts for it. */
    fun isCrossPlatformEvidence(fact: Fact): Boolean = fact.kind == FactKind.JOB_POSTING && CROSS_PLATFORM_MARK in fact.statement

    /** When a hiring signal is dated: the publication day when the source gives it (never in the future), else the read time. */
    fun observedAt(publishedAt: Instant?, now: Instant): Instant = publishedAt?.takeIf { !it.isAfter(now) } ?: now

    /** A posting open for more than [STUCK_AFTER_DAYS]: the hire is stuck. Null below that. */
    fun stuckStrength(ageDays: Long?): Double? =
        ageDays?.takeIf { it > STUCK_AFTER_DAYS }?.let { ((it - STUCK_AFTER_DAYS) / STUCK_RAMP_DAYS).coerceIn(STUCK_MIN_STRENGTH, 1.0) }
}
