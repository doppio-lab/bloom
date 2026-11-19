package lab.doppio.bloom.agents

import lab.doppio.bloom.core.model.latestPerTopic
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.guard.GuardInput
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.guard.Violation
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftCreatedPayload
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.Drafter
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.references.ReferenceLibrary

/**
 * Agent rédacteur. Boucle : rédiger, vérifier avec MessageGuard, corriger (2 fois maximum).
 * Le brouillon arrive toujours dans la file de validation, avec son rapport de vérification.
 * Un brouillon encore bloqué après 2 corrections reste visible mais ne peut pas être approuvé sans édition.
 */
class WriterAgent(
    private val llm: Llm,
    private val accountRepo: AccountRepository,
    private val contactRepo: ContactRepository,
    private val draftRepo: DraftRepository,
    private val eventRepo: EventRepository,
    private val factRepo: FactRepository,
    private val tracker: RunTracker,
    private val senderName: String,
    private val maxRepairs: Int = WriterLimits.MAX_REPAIRS,
) : Drafter {
    @Serializable
    @SerialName("OutreachDraft")
    @LLMDescription(OutputTexts.DRAFT)
    data class DraftOutput(
        @property:LLMDescription(OutputTexts.DRAFT_SUBJECT)
        val subject: String,
        @property:LLMDescription(OutputTexts.DRAFT_BODY)
        val body: String,
        @property:LLMDescription(OutputTexts.DRAFT_CITED_FACTS)
        val citedFactIds: List<String>,
        @property:LLMDescription(OutputTexts.DRAFT_REFERENCE)
        val referenceKey: String,
        @property:LLMDescription(OutputTexts.DRAFT_RATIONALE)
        val rationale: String,
    )

    override suspend fun draft(enrollment: Enrollment, sequence: Sequence, step: SequenceStep): Long {
        val contact = contactRepo.contact(enrollment.contactId) ?: error("Contact introuvable")
        val account = accountRepo.account(contact.accountId) ?: error("Compte introuvable")
        val facts = factRepo.factsOf(account.id).latestPerTopic()
        val dossier = factRepo.dossier(account.id)
        val references = ReferenceLibrary.eligible(account.sector, sequence.offer)
        val examples = draftRepo.humanEditedExamples(WriterLimits.HUMAN_EXAMPLES)
        val previous = draftRepo.draftsByStatus(DraftStatus.SENT).filter { it.enrollmentId == enrollment.id }

        val context = Prompts.writerContext(contact, account, sequence, step, dossier, facts, references, examples = examples, previous = previous)

        var attempt = 0
        var output: DraftOutput
        var violations: List<Violation>
        var feedback = ""
        do {
            output = tracker.track(AgentName.WRITER, account.id, llm.writer) {
                val r = llm.structured<DraftOutput>(
                    prompt = prompt("draft-${enrollment.id}-${step.index}-$attempt") {
                        system(Prompts.writer(contact.language, senderName))
                        user(context + feedback)
                    },
                    model = llm.writer,
                )
                r.data to r
            }
            violations = MessageGuard.check(
                GuardInput(
                    channel = step.channel,
                    stepIndex = step.index,
                    subject = output.subject.takeIf { step.channel == Channel.EMAIL },
                    body = output.body,
                    citedFactIds = output.citedFactIds,
                    availableFacts = facts,
                    referenceKey = output.referenceKey.ifBlank { null },
                    sector = account.sector,
                    offer = sequence.offer,
                    language = contact.language,
                ),
            )
            feedback = Prompts.writerRepair(output.body, violations)
            attempt++
        } while (!MessageGuard.isPublishable(violations) && attempt <= maxRepairs)

        val draftId = draftRepo.createDraft(
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
        eventRepo.logEvent(EventType.DRAFT_CREATED, account.id, contact.id, draftId, DraftCreatedPayload(attempt, MessageGuard.isPublishable(violations)).toJson())
        return draftId
    }
}
