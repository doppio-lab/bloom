package lab.doppio.bloom.core.sequence

import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Offer
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
        repeat(30) {
            val date = t.toLocalDate()
            val start = date.atTime(policy.startHour, policy.startMinute).atZone(zone)
            val end = date.atTime(policy.endHour, policy.endMinute).atZone(zone)
            if (date.dayOfWeek.value in policy.days && isBusinessDay(date, policy)) {
                if (t.isBefore(start)) return start.toInstant()
                if (t.isBefore(end)) return t.toInstant()
            }
            t = date.plusDays(1).atStartOfDay(zone)
        }
        error("Aucun créneau d'envoi dans les 30 prochains jours : vérifier la SendingPolicy")
    }

    fun inWindow(now: Instant, policy: SendingPolicy): Boolean = nextSendingSlot(now, policy) == now
}

/** Séquences par défaut, alignées sur la matrice signal vers offre. Courtes : 3 touches, 2 canaux. */
object DefaultSequences {
    fun audit() = Sequence(
        name = "App en difficulté vers audit 2 jours",
        offer = Offer.DISCOVERY,
        steps = listOf(
            SequenceStep(0, Channel.EMAIL, 0, "Constat sourcé sur l'app (avis, note, cadence de release), une hypothèse technique, proposition d'audit de 2 jours à prix fixe."),
            SequenceStep(1, Channel.LINKEDIN, 2, "Demande de connexion sans pitch, avec une ligne qui rappelle le constat."),
            SequenceStep(2, Channel.EMAIL, 4, "Relance courte : un apprentissage concret d'un audit comparable, une question fermée."),
        ),
    )

    fun staffing() = Sequence(
        name = "Recrutement mobile bloqué vers senior en régie",
        offer = Offer.REGIE,
        steps = listOf(
            SequenceStep(0, Channel.EMAIL, 0, "Mentionner l'offre d'emploi ouverte depuis X jours, proposer un senior disponible pendant le recrutement, renouvelable au mois."),
            SequenceStep(1, Channel.EMAIL, 3, "Relance : ce que le senior livre dans ses 2 premières semaines."),
            SequenceStep(2, Channel.LINKEDIN, 3, "Connexion avec note courte, sans relance commerciale."),
        ),
    )

    fun kmpTraining() = Sequence(
        name = "Équipe qui adopte KMP vers formation 3 jours",
        offer = Offer.TRAINING_KMP,
        steps = listOf(
            SequenceStep(0, Channel.EMAIL, 0, "L'équipe recrute sur KMP : proposer la formation de 3 jours (un projet WebRTC du repo vide à la prod), citer la vitrine JetBrains si pertinent."),
            SequenceStep(1, Channel.EMAIL, 4, "Relance : programme jour par jour en une phrase, proposer un appel de 15 minutes."),
        ),
    )

    fun all() = listOf(audit(), staffing(), kmpTraining())
}
