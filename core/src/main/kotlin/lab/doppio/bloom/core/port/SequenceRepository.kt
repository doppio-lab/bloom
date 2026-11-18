package lab.doppio.bloom.core.port

import java.time.Instant
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

    /** Every contact that was ever enrolled, whatever the status of the enrollment. */
    suspend fun enrolledContactIds(): Set<ContactId>

    suspend fun enrollmentsOfContact(contactId: ContactId): List<Enrollment>

    suspend fun advance(enrollmentId: Long, nextStep: Int, threadMessageId: String? = null)

    suspend fun threadMessageId(enrollmentId: Long): String?

    suspend fun setEnrollmentStatus(enrollmentId: Long, status: EnrollmentStatus)

    /**
     * The contact is away until [until]: the enrollment stays (or goes back to) ACTIVE and its next step is counted
     * from that date, since the sequence engine anchors a step's delay on the last action.
     */
    suspend fun postponeEnrollment(enrollmentId: Long, until: Instant)
}
