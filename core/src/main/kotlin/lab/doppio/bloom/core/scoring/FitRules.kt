package lab.doppio.bloom.core.scoring

/** Points of the ICP fit part of the score (0..[CAP]), one named value per rule. Reason strings use the same values. */
object FitRules {
    /** The fit part never exceeds this, whatever the rules add up to. */
    const val CAP = 40

    /** The account ships a mobile app (iOS or Android). */
    const val MOBILE_APP = 12

    /** Present on both stores: the field where Kotlin Multiplatform pays off. */
    const val BOTH_STORES = 4

    /** A sector where Doppio has a direct reference. */
    const val CORE_SECTOR = 8

    const val MARKET_FR = 4
    const val MARKET_EUROPE = 2

    /** Headcount bands: the upper bound of each band and its points. The last band is open-ended. */
    const val MICRO_MAX_HEADCOUNT = 14
    const val MICRO_POINTS = 2
    const val SMALL_MAX_HEADCOUNT = 49
    const val SMALL_POINTS = 8
    const val MID_MAX_HEADCOUNT = 499

    /** The core target: big enough for a budget, small enough for a short cycle. */
    const val MID_POINTS = 12

    /** Beyond the core target: long cycles and procurement. */
    const val LARGE_POINTS = 9
}
