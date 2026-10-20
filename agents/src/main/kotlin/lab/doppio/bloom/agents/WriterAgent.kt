package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.guard.GuardInput
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.guard.Violation
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep
import lab.doppio.bloom.core.references.ReferenceLibrary
import lab.doppio.bloom.data.db.Repository

/**
 * Agent rédacteur. Boucle : rédiger, vérifier avec MessageGuard, corriger (2 fois maximum).
 * Le brouillon arrive toujours dans la file de validation, avec son rapport de vérification.
 * Un brouillon encore bloqué après 2 corrections reste visible mais ne peut pas être approuvé sans édition.
 */
class WriterAgent(
    private val llm: Llm,
    private val repo: Repository,
    private val tracker: RunTracker,
    private val senderName: String,
    private val maxRepairs: Int = 2,
) {
    @Serializable
    @SerialName("OutreachDraft")
    @LLMDescription("Brouillon de message de prospection")
    data class DraftOutput(
        @property:LLMDescription("Objet de l'email, 2 à 6 mots. Vide pour LinkedIn et téléphone.")
        val subject: String,
        @property:LLMDescription("Corps du message, avec les marqueurs [F:id] après chaque affirmation sur le prospect")
        val body: String,
        @property:LLMDescription("Identifiants des faits cités")
        val citedFactIds: List<String>,
        @property:LLMDescription("Clé de la référence client utilisée, ou chaîne vide si aucune")
        val referenceKey: String,
        @property:LLMDescription("Pourquoi cet angle, en une phrase, pour la personne qui valide")
        val rationale: String,
    )

    suspend fun draft(enrollment: Enrollment, sequence: Sequence, step: SequenceStep): Long {
        val contact = repo.contact(enrollment.contactId) ?: error("Contact introuvable")
        val account = repo.account(contact.accountId) ?: error("Compte introuvable")
        val facts = repo.factsOf(account.id)
        val dossier = repo.dossier(account.id)
        val references = ReferenceLibrary.eligible(account.sector, sequence.offer)
        val examples = repo.humanEditedExamples(3)
        val previous = repo.draftsByStatus(DraftStatus.SENT).filter { it.enrollmentId == enrollment.id }

        val context = buildString {
            appendLine("Destinataire : ${contact.fullName}, ${contact.role} chez ${account.name}. Langue : ${contact.language}.")
            appendLine("Canal : ${step.channel}. Étape ${step.index + 1} sur ${sequence.steps.size}. Offre : ${sequence.offer.label}.")
            appendLine("Consigne de l'étape : ${step.intent}")
            dossier?.let {
                appendLine("\nDossier :\n${it.summary}\nAngle : ${it.angle}")
                it.hypotheses.forEach { h -> appendLine("Hypothèse : $h") }
            }
            appendLine("\nFaits disponibles (cite-les avec [F:id]) :")
            facts.forEach { appendLine("[${it.id}] ${it.statement}") }
            appendLine("\nRéférences autorisées (clé : preuve) :")
            if (references.isEmpty()) appendLine("aucune, n'en cite pas")
            references.forEach { appendLine("${it.key} : ${it.client}, ${if (it.detailsPublic) it.proof else "nom seul, sans détail"}") }
            if (previous.isNotEmpty()) {
                appendLine("\nMessages déjà envoyés dans ce fil (ne pas répéter) :")
                previous.forEach { appendLine("- ${it.subject.orEmpty()} : ${MessageGuard.stripCitations(it.body)}") }
            }
            if (examples.isNotEmpty()) {
                appendLine("\nExemples de messages corrigés et validés par l'équipe (imite ce ton) :")
                examples.forEach { appendLine("---\n${MessageGuard.stripCitations(it.body)}") }
            }
        }

        val (output, violations, attempts) = compose(
            promptId = "draft-${enrollment.id}-${step.index}",
            accountId = account.id,
            context = context,
            channel = step.channel,
            stepIndex = step.index,
            facts = facts,
            sector = account.sector,
            offer = sequence.offer,
            language = contact.language,
        )

        val draftId = repo.createDraft(
            Draft(
                enrollmentId = enrollment.id,
                stepIndex = step.index,
                channel = step.channel,
                subject = output.subject.takeIf { step.channel == Channel.EMAIL },
                body = output.body,
                citedFactIds = output.citedFactIds,
                referenceUsed = output.referenceKey.ifBlank { null },
                rationale = output.rationale,
                status = DraftStatus.PENDING_REVIEW,
                guardReport = violations.map { it.toString() },
            ),
        )
        repo.logEvent("DRAFT_CREATED", account.id, contact.id, draftId, """{"attempts":$attempts,"publishable":${MessageGuard.isPublishable(violations)}}""")
        return draftId
    }

    internal data class Composition(val output: DraftOutput, val violations: List<Violation>, val attempts: Int)

    /** The write, check, repair loop. Free of the repository so it can run against a fake model. */
    internal suspend fun compose(
        promptId: String,
        accountId: Long,
        context: String,
        channel: Channel,
        stepIndex: Int,
        facts: List<Fact>,
        sector: Sector,
        offer: Offer,
        language: String,
    ): Composition {
        var attempt = 0
        var output: DraftOutput
        var violations: List<Violation>
        var feedback = ""
        do {
            output = tracker.track("writer", accountId, llm.writer) {
                val r = llm.executor.executeStructured<DraftOutput>(
                    prompt = prompt("$promptId-$attempt") {
                        system(Prompts.writer(language, senderName))
                        user(context + feedback)
                    },
                    model = llm.writer,
                    fixingParser = llm.fixingParser,
                ).getOrThrow()
                r.data to r
            }
            violations = MessageGuard.check(
                GuardInput(
                    channel = channel,
                    stepIndex = stepIndex,
                    subject = output.subject.takeIf { channel == Channel.EMAIL },
                    body = output.body,
                    citedFactIds = output.citedFactIds,
                    availableFacts = facts,
                    referenceKey = output.referenceKey.ifBlank { null },
                    sector = sector,
                    offer = offer,
                    language = language,
                ),
            )
            feedback = "\n\nTon brouillon précédent :\n${output.body}\n\nIl a été refusé par le contrôle qualité :\n" +
                violations.joinToString("\n") { "- $it" } + "\nCorrige uniquement ces points."
            attempt++
        } while (!MessageGuard.isPublishable(violations) && attempt <= maxRepairs)
        return Composition(output, violations, attempt)
    }
}
