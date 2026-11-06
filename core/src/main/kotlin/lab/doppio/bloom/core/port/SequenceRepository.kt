package lab.doppio.bloom.core.port

import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sequence

/** Outreach sequences and the enrollments of contacts in them. */
interface SequenceRepository {
    suspend fun ensureSequences(defaults: List<Sequence>)

    suspend fun all(): List<Sequence>

    suspend fun sequenceFor(offer: Offer): Sequence?

    suspend fun enroll(contactId: ContactId, sequenceId: Long): Long

    suspend fun enrollment(id: Long): Enrollment?

    suspend fun activeEnrollments(): List<Enrollment>

    suspend fun enrollmentsOfContact(contactId: ContactId): List<Enrollment>

    suspend fun advance(enrollmentId: Long, nextStep: Int, threadMessageId: String? = null)

    suspend fun threadMessageId(enrollmentId: Long): String?

    suspend fun setEnrollmentStatus(enrollmentId: Long, status: EnrollmentStatus)
}
