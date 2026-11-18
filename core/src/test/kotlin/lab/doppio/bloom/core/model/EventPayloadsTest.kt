package lab.doppio.bloom.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The payload column keeps the exact strings written before the payloads were typed. */
class EventPayloadsTest {
    @Test
    fun `import report`() = assertEquals("""{"created":3,"updated":1,"contacts":7}""", ImportPayload(3, 1, 7).toJson())

    @Test
    fun `reply intent is written as its name`() {
        assertEquals("""{"intent":"INTERESTED"}""", ReplyReceivedPayload(ReplyIntent.INTERESTED).toJson())
        assertEquals("""{"intent":"MEETING_REQUEST"}""", ReplyReceivedPayload(ReplyIntent.MEETING_REQUEST).toJson())
    }

    @Test
    fun `gdpr purge counts`() = assertEquals(
        """{"contacts":2,"officers":1,"demands":3,"replyBodies":4,"signals":5,"seenKeys":6}""",
        GdprPurgePayload(2, 1, 3, 4, 5, 6).toJson(),
    )

    @Test
    fun `discovery from job offers`() = assertEquals("""{"source":"jobs","offers":4}""", AccountDiscoveredPayload("jobs", offers = 4).toJson())

    @Test
    fun `discovery from the app store`() =
        assertEquals("""{"source":"appstore","appId":1234567890123}""", AccountDiscoveredPayload("appstore", appId = 1234567890123).toJson())

    @Test
    fun `discovery from a source key`() = assertEquals("""{"source":"codeur"}""", AccountDiscoveredPayload("codeur").toJson())

    @Test
    fun `research score`() = assertEquals("""{"score":72}""", AccountResearchedPayload(72).toJson())

    @Test
    fun `draft creation`() {
        assertEquals("""{"attempts":1,"publishable":true}""", DraftCreatedPayload(1, true).toJson())
        assertEquals("""{"attempts":3,"publishable":false}""", DraftCreatedPayload(3, false).toJson())
    }
}

class EventTypeTest {
    @Test
    fun `stored names are the enum names`() {
        val stored = listOf(
            "ACCOUNT_DISCOVERED", "ACCOUNT_EXCLUDED", "ACCOUNT_RESEARCHED", "ACCOUNT_RESTORED", "BOUNCED", "DISCOVERY_RUN",
            "DOSSIER_NO_FIT", "DRAFT_APPROVED", "DRAFT_CREATED", "DRAFT_EDITED_APPROVED", "DRAFT_REJECTED", "EMAIL_FAILED",
            "EMAIL_FOUND", "EMAIL_NOT_FOUND", "EMAIL_SENT", "ENROLLED", "ENROLLMENT_STOPPED_REJECTIONS", "GDPR_PURGE", "IMPORT",
            "LINKEDIN_TASK_DONE", "MEETING_BOOKED", "PHONE_TASK_DONE", "REPLY_POSITIVE", "REPLY_RECEIVED", "UNSUBSCRIBED", "WEEKLY_MEMO",
        )
        assertEquals(stored, EventType.entries.map { it.name })
    }

    @Test
    fun `task done follows the channel`() {
        assertEquals(EventType.LINKEDIN_TASK_DONE, EventType.taskDone(Channel.LINKEDIN))
        assertEquals(EventType.PHONE_TASK_DONE, EventType.taskDone(Channel.PHONE))
        assertFailsWith<IllegalArgumentException> { EventType.taskDone(Channel.EMAIL) }
    }

    @Test
    fun `unknown names are not parsed`() {
        assertEquals(EventType.EMAIL_SENT, EventType.fromName("EMAIL_SENT"))
        assertEquals(null, EventType.fromName("SOMETHING_ELSE"))
    }
}
