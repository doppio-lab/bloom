package lab.doppio.bloom.core.model

import java.time.Instant
import java.time.LocalDate

fun Instant.iso(): String = toString()
fun LocalDate.iso(): String = toString()
