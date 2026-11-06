package lab.doppio.bloom.data.persistence.draft

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.data.persistence.dbQuery
import lab.doppio.bloom.data.persistence.decodeList
import lab.doppio.bloom.data.persistence.encode
import lab.doppio.bloom.data.persistence.sequence.EnrollmentsTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ExposedDraftRepository(private val clock: Clock = Clock.systemDefaultZone()) : DraftRepository {

    override suspend fun hasOpenDraft(enrollmentId: Long, stepIndex: Int): Boolean = dbQuery {
        DraftsTable.selectAll().where {
            (DraftsTable.enrollmentId eq enrollmentId) and (DraftsTable.stepIndex eq stepIndex) and
                (DraftsTable.status inList listOf(DraftStatus.PENDING_REVIEW, DraftStatus.APPROVED, DraftStatus.SENDING, DraftStatus.SENT, DraftStatus.TASK_DONE, DraftStatus.FAILED).map { it.name })
        }.any()
    }

    override suspend fun rejectedDrafts(enrollmentId: Long, stepIndex: Int): Int = dbQuery {
        DraftsTable.selectAll().where {
            (DraftsTable.enrollmentId eq enrollmentId) and (DraftsTable.stepIndex eq stepIndex) and (DraftsTable.status eq DraftStatus.REJECTED.name)
        }.count().toInt()
    }

    override suspend fun createDraft(d: Draft): Long = dbQuery {
        DraftsTable.insert {
            it[enrollmentId] = d.enrollmentId; it[stepIndex] = d.stepIndex; it[channel] = d.channel.name; it[subject] = d.subject
            it[body] = d.body; it[citedFactIds] = d.citedFactIds.encode(); it[referenceUsed] = d.referenceUsed
            it[rationale] = d.rationale; it[status] = d.status.name; it[guardReport] = d.guardReport.encode()
        } get DraftsTable.id
    }

    private fun ResultRow.toDraft() = Draft(
        this[DraftsTable.id], this[DraftsTable.enrollmentId], this[DraftsTable.stepIndex], Channel.valueOf(this[DraftsTable.channel]), this[DraftsTable.subject],
        this[DraftsTable.body], this[DraftsTable.citedFactIds].decodeList(), this[DraftsTable.referenceUsed], this[DraftsTable.rationale],
        DraftStatus.valueOf(this[DraftsTable.status]), this[DraftsTable.guardReport].decodeList(),
    )

    override suspend fun draft(id: Long): Draft? = dbQuery { DraftsTable.selectAll().where { DraftsTable.id eq id }.firstOrNull()?.toDraft() }

    override suspend fun draftsByStatus(status: DraftStatus, channel: Channel?): List<Draft> = dbQuery {
        DraftsTable.selectAll().where {
            if (channel == null) DraftsTable.status eq status.name else (DraftsTable.status eq status.name) and (DraftsTable.channel eq channel.name)
        }.orderBy(DraftsTable.createdAt).map { it.toDraft() }
    }

    override suspend fun decideDraft(id: Long, status: DraftStatus, editedSubject: String?, editedBody: String?) {
        dbQuery {
            DraftsTable.update({ DraftsTable.id eq id }) {
                it[this.status] = status.name
                it[decidedAt] = clock.instant()
                if (editedBody != null) { it[body] = editedBody; it[editedByHuman] = true }
                if (editedSubject != null) { it[subject] = editedSubject; it[editedByHuman] = true }
            }
        }
    }

    override suspend fun humanEditedExamples(limit: Int): List<Draft> = dbQuery {
        DraftsTable.selectAll().where { (DraftsTable.editedByHuman eq true) and (DraftsTable.status eq DraftStatus.SENT.name) }
            .orderBy(DraftsTable.sentAt, SortOrder.DESC_NULLS_LAST).limit(limit).map { it.toDraft() }
    }

    // --- Envoi : réserver, envoyer, constater. Un brouillon réservé n'est jamais renvoyé. ------------

    override suspend fun nextToSend(now: Instant?): Draft? = dbQuery {
        val at = now ?: clock.instant()
        DraftsTable.selectAll().where {
            (DraftsTable.status eq DraftStatus.APPROVED.name) and (DraftsTable.channel eq Channel.EMAIL.name) and
                (DraftsTable.nextAttemptAt.isNull() or DraftsTable.nextAttemptAt.lessEq(at))
        }.orderBy(DraftsTable.createdAt).limit(1).firstOrNull()?.toDraft()
    }

    override suspend fun claimForSending(id: Long, messageId: String, mailbox: String): Boolean = dbQuery {
        DraftsTable.update({ (DraftsTable.id eq id) and (DraftsTable.status eq DraftStatus.APPROVED.name) }) {
            it[status] = DraftStatus.SENDING.name; it[this.messageId] = messageId; it[this.mailbox] = mailbox
        } > 0
    }

    override suspend fun recordSent(draft: Draft, threadMessageId: String, now: Instant?) {
        val at = now ?: clock.instant()
        dbQuery {
            DraftsTable.update({ (DraftsTable.id eq draft.id) and (DraftsTable.status eq DraftStatus.SENDING.name) }) {
                it[status] = DraftStatus.SENT.name; it[sentAt] = at; it[nextAttemptAt] = null; it[lastError] = null
            }
            EnrollmentsTable.update({ EnrollmentsTable.id eq draft.enrollmentId }) {
                it[nextStep] = draft.stepIndex + 1
                it[lastActionAt] = at
                it[this.threadMessageId] = threadMessageId
            }
        }
    }

    override suspend fun recordSendFailure(id: Long, error: String, now: Instant?, maxAttempts: Int): DraftStatus = dbQuery {
        val at = now ?: clock.instant()
        val attempts = (DraftsTable.selectAll().where { DraftsTable.id eq id }.firstOrNull()?.get(DraftsTable.sendAttempts) ?: 0) + 1
        val next = if (attempts >= maxAttempts) DraftStatus.FAILED else DraftStatus.APPROVED
        DraftsTable.update({ (DraftsTable.id eq id) and (DraftsTable.status eq DraftStatus.SENDING.name) }) {
            it[status] = next.name
            it[sendAttempts] = attempts
            it[lastError] = error.take(500)
            it[messageId] = null
            it[nextAttemptAt] = if (next == DraftStatus.APPROVED) at.plus(15L * attempts, ChronoUnit.MINUTES) else null
        }
        next
    }

    override suspend fun reconcileInterruptedSends(): Int = dbQuery {
        DraftsTable.update({ DraftsTable.status eq DraftStatus.SENDING.name }) {
            it[status] = DraftStatus.FAILED.name
            it[lastError] = "Interrompu pendant l'envoi : vérifier la boîte d'envoi avant de relancer"
        }
    }

    override suspend fun sentTodayBy(mailbox: String, since: Instant): Int = dbQuery {
        DraftsTable.selectAll().where { (DraftsTable.mailbox eq mailbox) and (DraftsTable.sentAt greaterEq since) }.count().toInt()
    }

    override suspend fun lastSentAt(mailbox: String): Instant? = dbQuery {
        DraftsTable.selectAll().where { DraftsTable.mailbox eq mailbox }.orderBy(DraftsTable.sentAt, SortOrder.DESC_NULLS_LAST).limit(1)
            .firstOrNull()?.get(DraftsTable.sentAt)
    }

    override suspend fun enrollmentByMessageId(messageId: String): Long? = dbQuery {
        DraftsTable.selectAll().where { DraftsTable.messageId eq messageId }.firstOrNull()?.get(DraftsTable.enrollmentId)
    }
}
