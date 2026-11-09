package lab.doppio.bloom.core.discovery

import lab.doppio.bloom.core.model.Sector

object SectorMapping {
    fun fromNaf(naf: String?): Sector {
        if (naf == null) return Sector.OTHER
        val div = naf.take(2).toIntOrNull() ?: return Sector.OTHER
        return when (div) {
            64, 65, 66 -> Sector.BANK_INSURANCE
            45, 46, 47 -> Sector.RETAIL
            52, 53 -> Sector.LOGISTICS
            49, 50, 51, 79 -> Sector.MOBILITY_TRANSPORT
            86, 87, 88, 21 -> Sector.HEALTH
            58, 59, 60, 90 -> Sector.MUSIC_MEDIA
            26, 27 -> Sector.IOT_HARDWARE
            62, 63 -> Sector.PRODUCTIVITY_SAAS
            94 -> Sector.CONSUMER_ASSOCIATION
            else -> Sector.OTHER
        }
    }

    /** Identifiants de genre App Store (https://developer.apple.com, genre IDs). */
    val genres: Map<Int, Pair<String, Sector>> = mapOf(
        6015 to ("Finance" to Sector.BANK_INSURANCE),
        6024 to ("Shopping" to Sector.RETAIL),
        6023 to ("Food & Drink" to Sector.RETAIL),
        6003 to ("Travel" to Sector.MOBILITY_TRANSPORT),
        6010 to ("Navigation" to Sector.MOBILITY_TRANSPORT),
        6013 to ("Health & Fitness" to Sector.HEALTH),
        6020 to ("Medical" to Sector.HEALTH),
        6000 to ("Business" to Sector.PRODUCTIVITY_SAAS),
        6007 to ("Productivity" to Sector.PRODUCTIVITY_SAAS),
        6011 to ("Music" to Sector.MUSIC_MEDIA),
        6016 to ("Entertainment" to Sector.MUSIC_MEDIA),
        6012 to ("Lifestyle" to Sector.CONSUMER_ASSOCIATION),
        6005 to ("Social Networking" to Sector.DATING_SOCIAL),
        6004 to ("Sports" to Sector.OTHER),
    )

    fun fromGenre(genreId: Int?): Sector = genreId?.let { genres[it]?.second } ?: Sector.OTHER
}
