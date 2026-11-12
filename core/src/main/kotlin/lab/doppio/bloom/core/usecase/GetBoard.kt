package lab.doppio.bloom.core.usecase

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.SequenceRepository

/** The columns of the board, in the order of the journey. */
@Serializable
enum class BoardStage { TO_START, TO_REVIEW, IN_SEQUENCE, REPLIED, MEETING }

/** The next thing that happens to a card, and whether the user is the one who does it. */
@Serializable
enum class BoardAction(val yours: Boolean) {
    FIND_CONTACT(true), ADD_EMAIL(true), START_SEQUENCE(true), REVIEW_EMAIL(true), FIX_EMAIL(true),
    SEND_LINKEDIN_NOTE(true), CHECK_FAILED_SEND(true), WAIT_DRAFT(false), WAIT_SEND(false), WAIT_REPLY(false), ANSWER_REPLY(true), NONE(false),
}

/** One card: an account, or an account and the person contacted there. The page finds the content of its panel by these ids. */
@Serializable
data class BoardCard(
    val stage: BoardStage, val action: BoardAction, val yours: Boolean,
    val accountId: AccountId?, val title: String, val score: Int?, val source: String?, val offer: Offer?,
    val contactId: ContactId? = null, val contactName: String? = null, val contactRole: String? = null,
    val draftId: Long? = null, val replyId: Long? = null, val replyIntent: ReplyIntent? = null,
    val step: Int? = null, val stepCount: Int? = null,
)

@Serializable
data class Board(val cards: List<BoardCard>)

/**
 * Where each account stands in the journey, from discovery to the meeting, and whose turn it is.
 * A stage is derived from facts (a contact, a draft, a reply, a deal), never set by hand.
 * Blacklisted accounts are missing from accountsByScore, so they never get a card.
 */
class GetBoard(
    private val accountRepo: AccountRepository,
    private val contactRepo: ContactRepository,
    private val sequenceRepo: SequenceRepository,
    private val draftRepo: DraftRepository,
    private val replyRepo: ReplyRepository,
    private val dealRepo: DealRepository,
    private val factRepo: FactRepository,
) {
    suspend operator fun invoke(): Board {
        val rows = accountRepo.accountsByScore(AccountRepository.ALL).associateBy { it.account.id }
        val contacts = contactRepo.contactsByAccount().values.flatten()
        val contactById = contacts.associateBy { it.id }
        val stepCounts = sequenceRepo.all().associate { it.id to it.steps.size }
        val cards = mutableListOf<BoardCard>()

        suspend fun card(row: AccountRow, stage: BoardStage, action: BoardAction, contact: Contact? = null) = BoardCard(
            stage = stage, action = action, yours = action.yours,
            accountId = row.account.id, title = row.account.name, score = row.score, source = row.account.source,
            offer = factRepo.dossier(row.account.id)?.offer ?: row.recommendedOffer,
            contactId = contact?.id, contactName = contact?.fullName, contactRole = contact?.role,
        )

        // 1. A meeting: one card for the account, and nothing else for it. It shows the offer of its most advanced deal
        val meetingDeals = dealRepo.deals().filter { it.stage in MEETING_STAGES }
            .groupBy { it.accountId }.mapValues { (_, deals) -> deals.maxBy { it.stage.ordinal } }
        val meetings = meetingDeals.keys
        meetingDeals.values.mapNotNull { deal -> rows[deal.accountId]?.let { it to deal } }.sortedByDescending { it.first.score }.take(MAX_MEETINGS)
            .forEach { (row, deal) -> cards += card(row, BoardStage.MEETING, BoardAction.NONE).copy(offer = deal.offer) }

        // 2. Open replies. The sequence of the person who replied is stopped, so it gets no other card
        val replied = mutableSetOf<ContactId>()
        for (r in replyRepo.openReplies()) {
            val contact = r.enrollmentId?.let { sequenceRepo.enrollment(it) }?.let { contactById[it.contactId] }
                ?: contactRepo.contactByEmail(r.from)
            if (contact == null) {
                cards += BoardCard(BoardStage.REPLIED, BoardAction.ANSWER_REPLY, true, null, r.from, null, null, null, replyId = r.id, replyIntent = r.intent)
                continue
            }
            replied += contact.id
            val row = rows[contact.accountId] ?: continue
            if (row.account.id in meetings) continue
            cards += card(row, BoardStage.REPLIED, BoardAction.ANSWER_REPLY, contact).copy(replyId = r.id, replyIntent = r.intent)
        }

        // 3. Running sequences: a draft to review, a LinkedIn note to send, a failed send to check, or Bloom at work
        val pending = draftRepo.draftsByStatus(DraftStatus.PENDING_REVIEW).groupBy { it.enrollmentId }
        // A failed draft counts as open, so the sequence waits on it until a human checks the sent folder
        val failed = draftRepo.draftsByStatus(DraftStatus.FAILED).groupBy { it.enrollmentId }
        val approved = (draftRepo.draftsByStatus(DraftStatus.APPROVED) + draftRepo.draftsByStatus(DraftStatus.SENDING)).groupBy { it.enrollmentId }
        for (e in sequenceRepo.activeEnrollments()) {
            if (e.contactId in replied) continue
            val contact = contactById[e.contactId] ?: continue
            val row = rows[contact.accountId] ?: continue
            if (row.account.id in meetings) continue
            val review = pending[e.id]?.firstOrNull()
            val note = approved[e.id]?.firstOrNull { it.channel != Channel.EMAIL }
            val email = approved[e.id]?.firstOrNull { it.channel == Channel.EMAIL }
            val failure = failed[e.id]?.firstOrNull()
            val draft = review ?: note ?: email ?: failure
            val (stage, action) = when {
                review != null -> BoardStage.TO_REVIEW to if (review.guardReport.any { it.startsWith(BLOCK) }) BoardAction.FIX_EMAIL else BoardAction.REVIEW_EMAIL
                note != null -> BoardStage.IN_SEQUENCE to BoardAction.SEND_LINKEDIN_NOTE
                email != null -> BoardStage.IN_SEQUENCE to BoardAction.WAIT_SEND
                failure != null -> BoardStage.IN_SEQUENCE to BoardAction.CHECK_FAILED_SEND
                e.nextStep == 0 -> BoardStage.IN_SEQUENCE to BoardAction.WAIT_DRAFT
                else -> BoardStage.IN_SEQUENCE to BoardAction.WAIT_REPLY
            }
            cards += card(row, stage, action, contact).copy(
                draftId = draft?.id,
                step = draft?.let { it.stepIndex + 1 } ?: e.nextStep.coerceAtLeast(1),
                stepCount = stepCounts[e.sequenceId],
            )
        }

        // 4. Not started: nobody of the account was ever enrolled. An address that opposed prospecting is never offered
        val enrolled = sequenceRepo.enrolledContactIds()
        val byAccount = contacts.groupBy { it.accountId }
        for ((accountId, people) in byAccount) {
            if (accountId in meetings || people.any { it.id in enrolled || it.id in replied }) continue
            val row = rows[accountId] ?: continue
            val reachable = people.firstOrNull { it.email != null && !replyRepo.isSuppressed(it.email) }
            val withoutEmail = people.firstOrNull { it.email == null }
            when {
                reachable != null -> cards += card(row, BoardStage.TO_START, BoardAction.START_SEQUENCE, reachable)
                withoutEmail != null -> cards += card(row, BoardStage.TO_START, BoardAction.ADD_EMAIL, withoutEmail)
            }
        }
        for (row in accountRepo.accountsToComplete()) {
            val id = row.account.id
            if (id in byAccount || id in meetings || factRepo.dossier(id)?.noFit == true) continue
            cards += card(row, BoardStage.TO_START, BoardAction.FIND_CONTACT)
        }

        return Board(cards.sortedWith(compareBy<BoardCard>({ it.stage.ordinal }, { !it.yours }, { -(it.score ?: 0) })))
    }

    private companion object {
        val MEETING_STAGES = setOf(DealStage.MEETING, DealStage.DISCOVERY_SOLD, DealStage.PROPOSAL)
        const val MAX_MEETINGS = 20
        /** Prefix of a blocking guard violation, see Violation.toString. */
        const val BLOCK = "[BLOCK]"
    }
}
