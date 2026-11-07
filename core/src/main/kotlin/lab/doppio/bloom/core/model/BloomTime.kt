package lab.doppio.bloom.core.model

import java.time.ZoneId

/** Doppio works on Paris time: sending windows, day boundaries in reports, the daily token budget. */
object BloomTime {
    const val PARIS_ID = "Europe/Paris"
    val PARIS: ZoneId = ZoneId.of(PARIS_ID)
}
