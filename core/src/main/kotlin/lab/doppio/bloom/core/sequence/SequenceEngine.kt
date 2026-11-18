package lab.doppio.bloom.core.sequence

import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Machine à états des séquences. Fonction pure : (inscription, séquence, horloge) vers prochaine action.
 * Le scheduler applique les actions, ce module ne fait aucune entrée/sortie, il se teste à l'unité.
 */
sealed interface NextAction {
    /** Préparer le brouillon de l'étape (il passera par l'agent rédacteur puis la validation). */
    data class PrepareDraft(val step: SequenceStep) : NextAction
    /** Rien à faire avant cette date. */
    data class WaitUntil(val at: Instant) : NextAction
    /** La séquence est terminée ou interrompue. */
    data class Finish(val status: EnrollmentStatus) : NextAction
}

object SequenceEngine {

    /** How many days ahead the sending slot search looks before giving up (a policy with no allowed day never matches). */
    const val SLOT_SEARCH_DAYS = 30

    fun next(enrollment: Enrollment, sequence: Sequence, policy: SendingPolicy, now: Instant): NextAction {
        if (enrollment.status != EnrollmentStatus.ACTIVE) return NextAction.Finish(enrollment.status)
        val step = sequence.steps.getOrNull(enrollment.nextStep)
            ?: return NextAction.Finish(EnrollmentStatus.COMPLETED)

        val zone = ZoneId.of(policy.timezone)
        val anchor = enrollment.lastActionAt?.let(Instant::parse) ?: Instant.parse(enrollment.enrolledAt)
        val earliest = addBusinessDays(anchor.atZone(zone).toLocalDate(), step.delayBusinessDays, policy)
        val dueDate = maxOf(earliest, now.atZone(zone).toLocalDate())

        // Les tâches humaines (LinkedIn, téléphone) peuvent être préparées dès le jour dû, hors fenêtre email.
        if (step.channel != Channel.EMAIL) {
            val dueAt = dueDate.atStartOfDay(zone).toInstant()
            return if (now >= dueAt) NextAction.PrepareDraft(step) else NextAction.WaitUntil(dueAt)
        }
        val slot = nextSendingSlot(maxOf(now, dueDate.atStartOfDay(zone).toInstant()), policy)
        // On prépare le brouillon un jour ouvré avant le créneau, pour laisser le temps de valider.
        val slotLocal = slot.atZone(zone)
        val prepareFrom = previousBusinessDay(slotLocal.toLocalDate(), policy).atTime(slotLocal.toLocalTime()).atZone(zone).toInstant()
        return if (now >= prepareFrom) NextAction.PrepareDraft(step) else NextAction.WaitUntil(prepareFrom)
    }

    /** Effet d'une réponse entrante sur l'inscription. Toute réponse humaine arrête la séquence automatique. */
    fun onReply(intent: ReplyIntent): EnrollmentStatus = when (intent) {
        ReplyIntent.UNSUBSCRIBE -> EnrollmentStatus.STOPPED_UNSUBSCRIBE
        ReplyIntent.BOUNCE -> EnrollmentStatus.STOPPED_BOUNCE
        ReplyIntent.OUT_OF_OFFICE -> EnrollmentStatus.PAUSED
        else -> EnrollmentStatus.REPLIED
    }

    fun isBusinessDay(date: LocalDate, policy: SendingPolicy): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && date.toString() !in policy.holidays

    fun addBusinessDays(from: LocalDate, days: Int, policy: SendingPolicy): LocalDate {
        var d = from
        var remaining = days
        while (remaining > 0) {
            d = d.plusDays(1)
            if (isBusinessDay(d, policy)) remaining--
        }
        return d
    }

    fun previousBusinessDay(from: LocalDate, policy: SendingPolicy): LocalDate {
        var d = from.minusDays(1)
        while (!isBusinessDay(d, policy)) d = d.minusDays(1)
        return d
    }

    /** Premier instant >= [from] qui tombe dans la fenêtre d'envoi (jours et heures autorisés). */
    fun nextSendingSlot(from: Instant, policy: SendingPolicy): Instant {
        val zone = ZoneId.of(policy.timezone)
        var t: ZonedDateTime = from.atZone(zone)
        repeat(SLOT_SEARCH_DAYS) {
            val date = t.toLocalDate()
            val start = date.atTime(policy.startHour, policy.startMinute).atZone(zone)
            val end = date.atTime(policy.endHour, policy.endMinute).atZone(zone)
            if (date.dayOfWeek.value in policy.days && isBusinessDay(date, policy)) {
                if (t.isBefore(start)) return start.toInstant()
                if (t.isBefore(end)) return t.toInstant()
            }
            t = date.plusDays(1).atStartOfDay(zone)
        }
        error("Aucun créneau d'envoi dans les $SLOT_SEARCH_DAYS prochains jours : vérifier la SendingPolicy")
    }

    fun inWindow(now: Instant, policy: SendingPolicy): Boolean = nextSendingSlot(now, policy) == now
}
