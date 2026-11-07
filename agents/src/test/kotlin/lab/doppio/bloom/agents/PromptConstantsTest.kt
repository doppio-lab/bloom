package lab.doppio.bloom.agents

import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.ReplyIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lists the LLM reads match the enums the code parses its answers with. */
class PromptConstantsTest {
    @Test
    fun `reply intents in the annotation list are the enum`() =
        assertEquals(ReplyIntent.entries.joinToString(", ") { it.name }, REPLY_INTENT_LIST)

    @Test
    fun `offers in the annotation list are the enum`() =
        assertEquals(Offer.entries.joinToString(", ") { it.name }, OFFER_LIST)

    @Test
    fun `coach event types are all known events`() {
        val names = COACH_EVENT_LIST.split(", ")
        assertTrue(names.all { EventType.fromName(it) != null }, names.toString())
    }

    @Test
    fun `reply classifier prompt keeps its intent lines`() {
        val lines = Prompts.replyClassifier.lines()
        assertEquals("Intentions possibles : INTERESTED, MEETING_REQUEST, NOT_NOW, WRONG_PERSON, REFERRAL, NOT_INTERESTED,", lines[1])
        assertEquals("UNSUBSCRIBE, OUT_OF_OFFICE, BOUNCE, OTHER.", lines[2])
    }
}
