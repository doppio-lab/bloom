package lab.doppio.bloom.agents

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.AgentStats
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.scoring.ScoreBreakdown
import lab.doppio.bloom.core.references.ReferenceLibrary
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the texts the agents send to the model (user messages, repair feedback) and the replies their tools give
 * back, character for character, so they can be moved out of the logic without changing a word.
 */
class AgentTextsPinTest {
    // --- Writer ---------------------------------------------------------------------------------------
    private val facts = listOf(
        Fact("a2", 1, FactKind.APP_REVIEW, "11 avis sur 30 depuis la version 5.2 mentionnent un crash au paiement", "https://itunes.apple.com/x", "2026-10-10T08:00:00Z"),
    )
    private val step = SequenceStep(index = 0, channel = Channel.EMAIL, delayBusinessDays = 0, intent = "Premier contact")
    private val sequence = Sequence(id = 1, name = "Découverte", offer = Offer.DISCOVERY, steps = listOf(step, step.copy(index = 1)))
    private val enrollment = Enrollment(id = 7, contactId = 3, sequenceId = 1, status = EnrollmentStatus.ACTIVE, nextStep = 0, lastActionAt = null, enrolledAt = "2026-11-01T08:00:00Z")
    private val sent = Draft(id = 1, enrollmentId = 7, stepIndex = 0, channel = Channel.EMAIL, subject = "Crash", body = "Bonjour [F:a2] merci", citedFactIds = emptyList(), referenceUsed = null, rationale = "r", status = DraftStatus.SENT)
    private val example = sent.copy(id = 2, enrollmentId = 99, body = "Salut [F:a2], on parle ?")

    private fun drafts(): DraftRepository {
        val base = FakeDraftRepository()
        return object : DraftRepository by base {
            override suspend fun draftsByStatus(status: DraftStatus, channel: Channel?): List<Draft> = if (status == DraftStatus.SENT) listOf(sent) else emptyList()
            override suspend fun humanEditedExamples(limit: Int): List<Draft> = listOf(example)
        }
    }

    private fun writer(executor: CannedExecutor, sector: Sector, dossier: Dossier?, draftRepo: DraftRepository = FakeDraftRepository()) = WriterAgent(
        llm = fakeLlm(executor),
        accountRepo = FakeAccountRepository(listOf(Account(id = 1, name = "Acme", sector = sector))),
        contactRepo = FakeContactRepository(listOf(Contact(id = 3, accountId = 1, fullName = "Claire Martin", role = "CTO", persona = Persona.CTO, email = "claire@acme.test", dataSource = "annuaire"))),
        draftRepo = draftRepo,
        eventRepo = FakeEventRepository(),
        factRepo = FakeFactRepository(facts, dossier),
        tracker = RunTracker(FakeAgentRunRepository(), clock = fixedClock),
        senderName = "Renaud",
    )

    private val bad = """{"subject":"Apps","body":"Bonjour, on fait des apps. Un échange ?","citedFactIds":[],"referenceKey":"","rationale":"angle"}"""

    /** One line per eligible reference: the proof when its details are public, "nom seul, sans détail" otherwise. */
    private fun eligibleLines(sector: Sector, offer: Offer = Offer.DISCOVERY) = ReferenceLibrary.eligible(sector, offer).joinToString("") {
        "${it.key} : ${it.client}, ${if (it.detailsPublic) it.proof else "nom seul, sans détail"}\n"
    }

    @Test
    fun `the writer context with a dossier, references, previous messages and examples`() = runTest {
        val executor = CannedExecutor(bad)
        val dossier = Dossier(1, "Résumé du dossier", listOf("Hypothèse un [F:a2]"), "Angle choisi", Offer.DISCOVERY)
        writer(executor, Sector.BANK_INSURANCE, dossier, drafts()).draft(enrollment, sequence, step)
        assertEquals(
            "Destinataire : Claire Martin, CTO chez Acme. Langue : fr.\n" +
                "Canal : EMAIL. Étape 1 sur 2. Offre : ${Offer.DISCOVERY.label}.\n" +
                "Consigne de l'étape : Premier contact\n" +
                "\nDossier :\nRésumé du dossier\nAngle : Angle choisi\n" +
                "Hypothèse : Hypothèse un [F:a2]\n" +
                "\nFaits disponibles (cite-les avec [F:id]) :\n" +
                "[a2] 11 avis sur 30 depuis la version 5.2 mentionnent un crash au paiement\n" +
                "\nRéférences autorisées (clé : preuve) :\n" +
                eligibleLines(Sector.BANK_INSURANCE) +
                "\nMessages déjà envoyés dans ce fil (ne pas répéter) :\n" +
                "- Crash : Bonjour merci\n" +
                "\nExemples de messages corrigés et validés par l'équipe (imite ce ton) :\n" +
                "---\nSalut, on parle ?\n",
            executor.userText(0),
        )
    }

    @Test
    fun `the writer context without dossier nor references, and the repair feedback`() = runTest {
        val executor = CannedExecutor(bad)
        // Every sector has a reference, so the "none" sentence is pinned on its own in the text object's test
        writer(executor, Sector.OTHER, null).draft(enrollment, sequence.copy(offer = Offer.TRAINING_ANTHROPIC), step)
        val first = "Destinataire : Claire Martin, CTO chez Acme. Langue : fr.\n" +
            "Canal : EMAIL. Étape 1 sur 2. Offre : ${Offer.TRAINING_ANTHROPIC.label}.\n" +
            "Consigne de l'étape : Premier contact\n" +
            "\nFaits disponibles (cite-les avec [F:id]) :\n" +
            "[a2] 11 avis sur 30 depuis la version 5.2 mentionnent un crash au paiement\n" +
            "\nRéférences autorisées (clé : preuve) :\n" +
            eligibleLines(Sector.OTHER, Offer.TRAINING_ANTHROPIC)
        assertEquals(first, executor.userText(0))
        assertEquals(
            first + "\n\nTon brouillon précédent :\nBonjour, on fait des apps. Un échange ?\n\nIl a été refusé par le contrôle qualité :\n" +
                "- [BLOCK] sans-preuve : le premier email doit s'appuyer sur au moins un fait sourcé\nCorrige uniquement ces points.",
            executor.userText(1),
        )
    }

    // --- Research ------------------------------------------------------------------------------------
    private fun research(executor: CannedExecutor, account: Account, factRepo: FactRepository): ResearchAgent {
        val accounts = object : AccountRepository by FakeAccountRepository(listOf(account)) {
            override suspend fun saveScore(s: ScoreBreakdown) {}
        }
        return ResearchAgent(
            llm = fakeLlm(executor), accountRepo = accounts, eventRepo = FakeEventRepository(), factRepo = factRepo,
            tracker = RunTracker(FakeAgentRunRepository(), clock = fixedClock),
            appStore = object : lab.doppio.bloom.core.port.AppStoreFacts { override suspend fun analyze(accountId: Long, appId: Long, now: Instant) = TODO() },
            registry = object : lab.doppio.bloom.core.port.CompanyRegistryFacts { override suspend fun facts(accountId: Long, siren: String) = TODO() },
            jobs = null, web = FakeWebPages(emptyMap()), clock = fixedClock,
        )
    }

    private fun recordingFacts(all: List<Fact>): FactRepository = object : FactRepository by FakeFactRepository(all) {
        override suspend fun saveFacts(facts: List<Fact>) {}
        override suspend fun saveSignals(signals: List<Signal>) {}
        override suspend fun signalsOf(accountId: Long, maxAgeDays: Long): List<Signal> = emptyList()
        override suspend fun saveDossier(d: Dossier) {}
    }

    @Test
    fun `the exploration task and the synthesis input`() = runTest {
        val explore = CannedExecutor("""{"summary":"s","hypotheses":[],"angle":"a","offer":"NONE","disqualifiers":[]}""")
        research(explore, Account(id = 1, name = "Acme", domain = "acme.fr", sector = Sector.RETAIL), recordingFacts(facts)).research(1)
        assertEquals(
            "Entreprise : Acme, site https://acme.fr. Commence par la page d'accueil, puis cherche une page carrières et un blog tech.",
            explore.userText(0),
        )
        val excerpt = facts + Fact("a3", 1, FactKind.WEB_PAGE, "Page lue", "https://acme.fr", "2026-10-10T08:00:00Z", excerpt = "extrait de page")
        val synth = CannedExecutor("""{"summary":"s","hypotheses":[],"angle":"a","offer":"NONE","disqualifiers":[]}""")
        research(synth, Account(id = 1, name = "Acme", sector = Sector.RETAIL), recordingFacts(excerpt)).research(1)
        assertEquals(
            "Compte : Acme (site inconnu), secteur RETAIL, marché FR.\nFaits :\n" +
                "[a2] (APP_REVIEW, lu le 2026-10-10) 11 avis sur 30 depuis la version 5.2 mentionnent un crash au paiement\n" +
                "[a3] (WEB_PAGE, lu le 2026-10-10) Page lue\n    extrait : extrait de page",
            synth.userText(0),
        )
    }

    @Test
    fun `the research tools answer with these sentences`() = runTest {
        val web = FakeWebPages(mapOf("https://acme.fr/" to "Notre équipe mobile recrute.", "https://acme.fr/a" to "A", "https://acme.fr/b" to "B", "https://acme.fr/c" to "C", "https://acme.fr/d" to "D", "https://acme.fr/e" to "E", "https://acme.fr/f" to "F"))
        val tools = SiteResearchTools(1, "acme.fr", web, fixedClock)
        assertEquals("Refusé : seules les pages de acme.fr sont autorisées.", tools.readPage("https://evil.com/"))
        assertEquals("Page illisible : https://acme.fr/missing", tools.readPage("https://acme.fr/missing"))
        assertEquals("Refusé : lis d'abord la page avec readPage.", tools.recordQuote("https://acme.fr/", "Notre équipe mobile recrute", "m", "OTHER"))
        tools.readPage("https://acme.fr/")
        assertEquals("Refusé : la citation doit faire entre 12 et 300 caractères.", tools.recordQuote("https://acme.fr/", "court", "m", "OTHER"))
        assertEquals("Refusé : citation introuvable mot pour mot sur la page. Copie le texte exact.", tools.recordQuote("https://acme.fr/", "Notre équipe mobile compte douze", "m", "OTHER"))
        val ok = tools.recordQuote("https://acme.fr/", "Notre équipe mobile recrute", "Recrutement mobile", "HIRING")
        assertEquals("Enregistré sous l'identifiant ${tools.recorded.single().id}.", ok)
        assertEquals("Recrutement mobile (« Notre équipe mobile recrute »)", tools.recorded.single().statement)
        listOf("a", "b", "c", "d", "e").forEach { tools.readPage("https://acme.fr/$it") }
        assertEquals("Limite de 6 pages atteinte. Termine ton analyse.", tools.readPage("https://acme.fr/f"))
    }

    // --- Reply and coach -----------------------------------------------------------------------------
    @Test
    fun `the reply classifier input and its keyword stop summary`() = runTest {
        val executor = CannedExecutor("""{"intent":"OTHER","confidence":0.5,"followUpOn":"","referral":"","summary":"s","suggestedReply":""}""")
        val agent = ReplyClassifierAgent(fakeLlm(executor), RunTracker(FakeAgentRunRepository(), clock = fixedClock), fixedClock)
        agent.classify("Re: Sujet", "Pas maintenant.\n> citation", null)
        assertEquals("Objet : Re: Sujet\n\nPas maintenant.", executor.userText(0))
        assertEquals("Demande d'arrêt détectée par mot-clé", agent.classify(null, "unsubscribe", null).summary)
    }

    @Test
    fun `the weekly coach task`() = runTest {
        val executor = CannedExecutor("note")
        val noRuns = object : AgentRunRepository by FakeAgentRunRepository() {}
        val agent = WeeklyCoachAgent(
            fakeLlm(executor), FakeEventRepository(), FakeAccountRepository(emptyList()), deals(emptyList()), noRuns,
            RunTracker(FakeAgentRunRepository(), clock = fixedClock), fixedClock,
        )
        agent.weeklyMemo()
        assertEquals("Prépare la note du lundi 2026-11-06.", executor.userText(0))
    }

    // --- Coach metrics tools ----------------------------------------------------------------------------
    private fun deals(all: List<Deal>) = object : DealRepository {
        override suspend fun upsertDeal(d: Deal): Long = TODO()
        override suspend fun deals(): List<Deal> = all
    }

    private fun metrics(rows: List<AccountRow> = emptyList(), deals: List<Deal> = emptyList(), stats: List<AgentStats> = emptyList()): MetricsTools {
        val events = object : EventRepository by FakeEventRepository() {
            override suspend fun countEvents(type: EventType, since: Instant): Int = 7
            override suspend fun eventsSince(since: Instant) = listOf(
                Triple(EventType.EMAIL_SENT, 1L, FIXED_NOW), Triple(EventType.EMAIL_SENT, 2L, FIXED_NOW), Triple(EventType.REPLY_POSITIVE, 1L, FIXED_NOW),
            )
        }
        val accounts = object : AccountRepository by FakeAccountRepository(emptyList()) {
            override suspend fun accountsByScore(limit: Int): List<AccountRow> = rows
        }
        val runs = object : AgentRunRepository by FakeAgentRunRepository() {
            override suspend fun agentStats(since: Instant): List<AgentStats> = stats
        }
        return MetricsTools(events, accounts, deals(deals), runs, fixedClock)
    }

    private fun row(id: Long, name: String, offer: Offer?) = AccountRow(Account(id, name), 60, 0, 0, 0, offer, listOf("+4 marché France", "+12 app mobile en production", "+8 x", "+2 y"), null)

    @Test
    fun `the coach tools answer with these sentences`() = runTest {
        assertEquals("0", metrics().countEvents("NOPE", 7))
        assertEquals("7", metrics().countEvents(" email_sent ", 7))
        assertEquals("Pas encore de données.", metrics().replyRateBySignal())
        val rows = listOf(row(1, "Acme", Offer.DISCOVERY), row(2, "Beta", null))
        assertEquals("Acme : 60 pts, offre DISCOVERY, +4 marché France; +12 app mobile en production; +8 x\nBeta : 60 pts, offre aucune, +4 marché France; +12 app mobile en production; +8 x", metrics(rows).topAccounts())
        assertEquals("DISCOVERY : 1 comptes contactés, 1 réponses positives\nsans offre : 1 comptes contactés, 0 réponses positives", metrics(rows).replyRateBySignal())
        val deals = listOf(Deal(1, 1, Offer.DISCOVERY, DealStage.MEETING, 4000, null, null), Deal(2, 2, Offer.FORFAIT, DealStage.MEETING, 6000, null, null))
        assertEquals("MEETING : 2 affaires, 10000 euros, offres {DISCOVERY=1, FORFAIT=1}", metrics(deals = deals).pipeline())
        assertEquals(
            "writer : 3 exécutions, 1 échecs, 100 tokens en entrée, 50 en sortie, 1200 ms en moyenne",
            metrics(stats = listOf(AgentStats("writer", 3, 1, 100, 50, 1200))).agentCosts(),
        )
    }

    @Test
    fun `texts that no scenario above reaches`() {
        val contact = Contact(id = 3, accountId = 1, fullName = "Claire Martin", role = "CTO", persona = Persona.CTO, email = null, dataSource = "x")
        assertEquals(
            "Destinataire : Claire Martin, CTO chez Acme. Langue : fr.\n" +
                "Canal : EMAIL. Étape 1 sur 2. Offre : ${Offer.DISCOVERY.label}.\n" +
                "Consigne de l'étape : Premier contact\n" +
                "\nFaits disponibles (cite-les avec [F:id]) :\n" +
                "\nRéférences autorisées (clé : preuve) :\n" +
                "aucune, n'en cite pas\n",
            Prompts.writerContext(contact, Account(id = 1, name = "Acme"), sequence, step, null, emptyList(), emptyList(), emptyList(), emptyList()),
        )
        assertEquals("Résumé", OutputTexts.disqualifiedSummary("Résumé", emptyList()))
        assertEquals("Résumé\nÀ ne pas contacter si : déjà équipé; trop petit", OutputTexts.disqualifiedSummary("Résumé", listOf("déjà équipé", "trop petit")))
        assertEquals("Demande d'arrêt détectée par mot-clé", OutputTexts.KEYWORD_STOP_SUMMARY)
    }
}
