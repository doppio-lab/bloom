package lab.doppio.bloom.core.sequence

import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep

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
