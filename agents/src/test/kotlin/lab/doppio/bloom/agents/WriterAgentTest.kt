package lab.doppio.bloom.agents

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriterAgentTest {
    private val facts = listOf(
        Fact("a2", 1, FactKind.APP_REVIEW, "11 avis sur 30 depuis la version 5.2 mentionnent un crash au paiement", "https://itunes.apple.com/fr/rss/customerreviews/id=123/json", "2026-10-10T08:00:00Z"),
    )
    private val step = SequenceStep(index = 0, channel = Channel.EMAIL, delayBusinessDays = 0, intent = "Premier contact")
    private val sequence = Sequence(id = 1, name = "Découverte", offer = Offer.DISCOVERY, steps = listOf(step))
    private val enrollment = Enrollment(id = 7, contactId = 3, sequenceId = 1, status = EnrollmentStatus.ACTIVE, nextStep = 0, lastActionAt = null, enrolledAt = "2026-11-01T08:00:00Z")

    private val draftRepo = FakeDraftRepository()
    private val eventRepo = FakeEventRepository()
    private val runRepo = FakeAgentRunRepository()

    private fun json(subject: String, body: String, cited: String = "[]") =
        """{"subject":"$subject","body":"$body","citedFactIds":$cited,"referenceKey":"","rationale":"angle"}"""

    private val bad = json("Apps natives", "Bonjour, on fait des apps natives. Un échange ?")
    private val good = json(
        "Crash au paiement",
        "Bonjour Claire, depuis la version 5.2, 11 avis sur 30 parlent d'un crash au paiement [F:a2]. On peut auditer l'app en 2 jours, à prix fixe. Ça vaut un échange de 15 minutes ?",
        cited = """["a2"]""",
    )

    private fun writer(executor: CannedExecutor, maxRepairs: Int = 2) = WriterAgent(
        llm = fakeLlm(executor),
        accountRepo = FakeAccountRepository(listOf(Account(id = 1, name = "Acme", sector = Sector.OTHER))),
        contactRepo = FakeContactRepository(listOf(Contact(id = 3, accountId = 1, fullName = "Claire Martin", role = "CTO", persona = Persona.CTO, email = "claire@acme.test", dataSource = "annuaire"))),
        draftRepo = draftRepo,
        eventRepo = eventRepo,
        factRepo = FakeFactRepository(facts),
        tracker = RunTracker(runRepo, clock = fixedClock),
        senderName = "Renaud",
        maxRepairs = maxRepairs,
    )

    @Test
    fun `a publishable first draft costs one call and no repair`() = runTest {
        val executor = CannedExecutor(good)
        val id = writer(executor).draft(enrollment, sequence, step)

        assertEquals(1, executor.calls)
        val draft = draftRepo.created.single()
        assertEquals(1L, id)
        assertEquals(DraftStatus.PENDING_REVIEW, draft.status)
        assertEquals("Crash au paiement", draft.subject)
        assertEquals(listOf("a2"), draft.citedFactIds)
        assertTrue(draft.guardReport.none { it.startsWith("[BLOCK]") }, draft.guardReport.toString())
        assertEquals("""{"attempts":1,"publishable":true}""", eventRepo.events.single().payload)
    }

    @Test
    fun `the repair loop stops after maxRepairs and files the blocked draft for review`() = runTest {
        val executor = CannedExecutor(bad)
        writer(executor, maxRepairs = 2).draft(enrollment, sequence, step)

        // One draft plus two repairs
        assertEquals(3, executor.calls)
        val draft = draftRepo.created.single()
        assertEquals(DraftStatus.PENDING_REVIEW, draft.status)
        assertTrue(draft.guardReport.any { "sans-preuve" in it && it.startsWith("[BLOCK]") }, draft.guardReport.toString())
        assertEquals("""{"attempts":3,"publishable":false}""", eventRepo.events.single().payload)
        // Every call is tracked as a writer run
        assertEquals(3, runRepo.runs.size)
        assertTrue(runRepo.runs.all { it.agent == "writer" && it.success && it.accountId == 1L })
    }

    @Test
    fun `maxRepairs of zero means a single attempt`() = runTest {
        val executor = CannedExecutor(bad)
        writer(executor, maxRepairs = 0).draft(enrollment, sequence, step)
        assertEquals(1, executor.calls)
    }

    @Test
    fun `the guard rejection is fed back to the model and the repaired draft is kept`() = runTest {
        val executor = CannedExecutor(bad, good)
        writer(executor).draft(enrollment, sequence, step)

        assertEquals(2, executor.calls)
        val retry = executor.userText(1)
        assertTrue("Ton brouillon précédent" in retry)
        assertTrue("on fait des apps natives" in retry)
        assertTrue("sans-preuve" in retry, "the violation must be quoted back to the model")
        assertFalse("Ton brouillon précédent" in executor.userText(0))

        val draft = draftRepo.created.single()
        assertEquals("Crash au paiement", draft.subject)
        assertEquals("""{"attempts":2,"publishable":true}""", eventRepo.events.single().payload)
    }

    @Test
    fun `a missing contact fails before any model call`() = runTest {
        val executor = CannedExecutor(good)
        val orphan = enrollment.copy(contactId = 99)
        val error = runCatching { writer(executor).draft(orphan, sequence, step) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(0, executor.calls)
    }
}
