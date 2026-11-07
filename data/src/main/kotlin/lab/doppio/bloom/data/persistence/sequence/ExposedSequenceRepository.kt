package lab.doppio.bloom.data.persistence.sequence

import java.time.Clock
import kotlinx.serialization.builtins.ListSerializer
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.data.persistence.dbQuery
import lab.doppio.bloom.data.persistence.json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ExposedSequenceRepository(private val clock: Clock = Clock.systemDefaultZone()) : SequenceRepository {

    private val stepsSerializer = ListSerializer(SequenceStep.serializer())

    override suspend fun ensureSequences(defaults: List<Sequence>) = dbQuery {
        defaults.forEach { s ->
            SequencesTable.insertIgnore { it[name] = s.name; it[offer] = s.offer.name; it[steps] = json.encodeToString(stepsSerializer, s.steps) }
        }
    }

    override suspend fun all(): List<Sequence> = dbQuery {
        SequencesTable.selectAll().map { Sequence(it[SequencesTable.id], it[SequencesTable.name], Offer.valueOf(it[SequencesTable.offer]), json.decodeFromString(stepsSerializer, it[SequencesTable.steps])) }
    }

    override suspend fun sequenceFor(offer: Offer): Sequence? = all().firstOrNull { it.offer == offer }

    override suspend fun enroll(contactId: ContactId, sequenceId: Long): Long = dbQuery {
        EnrollmentsTable.insert { it[this.contactId] = contactId; it[this.sequenceId] = sequenceId; it[status] = EnrollmentStatus.ACTIVE.name } get EnrollmentsTable.id
    }

    private fun ResultRow.toEnrollment() = Enrollment(
        this[EnrollmentsTable.id], this[EnrollmentsTable.contactId], this[EnrollmentsTable.sequenceId], EnrollmentStatus.valueOf(this[EnrollmentsTable.status]),
        this[EnrollmentsTable.nextStep], this[EnrollmentsTable.lastActionAt]?.toString(), this[EnrollmentsTable.enrolledAt].toString(),
    )

    override suspend fun enrollment(id: Long): Enrollment? = dbQuery { EnrollmentsTable.selectAll().where { EnrollmentsTable.id eq id }.firstOrNull()?.toEnrollment() }

    override suspend fun activeEnrollments(): List<Enrollment> = dbQuery {
        EnrollmentsTable.selectAll().where { EnrollmentsTable.status eq EnrollmentStatus.ACTIVE.name }.map { it.toEnrollment() }
    }

    override suspend fun enrollmentsOfContact(contactId: ContactId): List<Enrollment> = dbQuery {
        EnrollmentsTable.selectAll().where { EnrollmentsTable.contactId eq contactId }.map { it.toEnrollment() }
    }

    override suspend fun advance(enrollmentId: Long, nextStep: Int, threadMessageId: String?) {
        dbQuery {
            EnrollmentsTable.update({ EnrollmentsTable.id eq enrollmentId }) {
                it[this.nextStep] = nextStep
                it[lastActionAt] = clock.instant()
                if (threadMessageId != null) it[this.threadMessageId] = threadMessageId
            }
        }
    }

    override suspend fun threadMessageId(enrollmentId: Long): String? = dbQuery {
        EnrollmentsTable.selectAll().where { EnrollmentsTable.id eq enrollmentId }.firstOrNull()?.get(EnrollmentsTable.threadMessageId)
    }

    override suspend fun setEnrollmentStatus(enrollmentId: Long, status: EnrollmentStatus) {
        dbQuery { EnrollmentsTable.update({ EnrollmentsTable.id eq enrollmentId }) { it[this.status] = status.name } }
    }
}
