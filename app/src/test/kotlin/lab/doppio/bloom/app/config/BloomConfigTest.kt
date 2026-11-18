package lab.doppio.bloom.app.config

import lab.doppio.bloom.agents.LlmProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class BloomConfigTest {
    private val secret = "0123456789abcdef"

    /** The smallest environment that starts: the three required values. */
    private fun env(vararg extra: Pair<String, String>): Map<String, String> =
        mapOf("ADMIN_PASSWORD" to secret, "UNSUBSCRIBE_SECRET" to "fedcba9876543210", "ANTHROPIC_API_KEY" to "sk-test") + extra

    @Test
    fun `defaults apply when only the required values are set`() {
        val c = BloomConfig.from(env())
        assertEquals(8080, c.server.port)
        assertEquals(secret, c.server.adminPassword)
        assertEquals("jdbc:postgresql://localhost:5433/bloom", c.database.url)
        assertEquals("bloom", c.database.user)
        assertEquals("bloom", c.database.password)
        assertEquals(LlmProvider.ANTHROPIC, c.llm.client.provider)
        assertEquals(16_384L, c.llm.client.contextLength)
        assertEquals(0L, c.llm.dailyTokenBudget)
        assertEquals("smtp.gmail.com", c.mail.smtpHost)
        assertEquals(587, c.mail.smtpPort)
        assertEquals("imap.gmail.com", c.mail.imapHost)
        assertEquals("http://localhost:8080", c.mail.publicBaseUrl)
        assertTrue(c.mail.dryRun)
        assertTrue(c.mail.mailboxes.isEmpty())
        assertEquals(25, c.sending.policy.maxPerMailboxPerDay)
        assertEquals("l'équipe Doppio", c.sending.senderName)
        assertEquals(setOf("bodacc", "boamp", "ted", "decp", "codeur", "press", "github", "jobboards"), c.discovery.sources)
        assertEquals(400, c.discovery.appsPerRun)
        assertEquals(100_000L, c.discovery.bodaccMinCapitalEur)
        assertEquals(12L, c.discovery.decpRenewalMonths)
        assertEquals(1_000, c.discovery.codeurMinBudgetEur)
        assertTrue(c.discovery.extraExclusions.isEmpty())
        assertNull(c.discovery.githubToken)
        assertNull(c.discovery.franceTravail)
        assertNull(c.discovery.dropcontactApiKey)
    }

    @Test
    fun `the operating knobs default to the values the code used before they were configurable`() {
        val c = BloomConfig.from(env())
        assertEquals(25, c.research.batchSize)
        assertEquals(14L, c.research.staleDays)
        assertEquals(60, c.discovery.leadsPerSource)
        assertEquals(30, c.discovery.jobBoardLookupsPerRun)
        assertEquals(150, c.discovery.officerLookupsPerRun)
        assertEquals(30L, c.discovery.recheckAfterDays)
        assertEquals(7, c.discovery.jobsWithinDays)
        assertEquals(10.minutes, c.loops.plan)
        assertEquals(2.minutes, c.loops.send)
        assertEquals(5.minutes, c.loops.replies)
        assertEquals(30.minutes, c.loops.periodic)
        assertEquals(8, c.database.maxPool)
        assertEquals(20L, c.httpTimeoutSeconds)
    }

    @Test
    fun `the operating knobs can be overridden`() {
        val c = BloomConfig.from(
            env(
                "RESEARCH_BATCH_SIZE" to "5", "RESEARCH_STALE_DAYS" to "21", "DISCOVERY_LEADS_PER_SOURCE" to "10",
                "DISCOVERY_JOB_BOARD_LOOKUPS" to "3", "DISCOVERY_OFFICER_LOOKUPS" to "15", "DISCOVERY_RECHECK_DAYS" to "60",
                "DISCOVERY_JOBS_WITHIN_DAYS" to "14", "LOOP_PLAN_MINUTES" to "1", "LOOP_SEND_MINUTES" to "3",
                "LOOP_REPLIES_MINUTES" to "4", "LOOP_PERIODIC_MINUTES" to "60", "DB_MAX_POOL" to "20", "HTTP_TIMEOUT_SECONDS" to "45",
            ),
        )
        assertEquals(5, c.research.batchSize)
        assertEquals(21L, c.research.staleDays)
        assertEquals(10, c.discovery.leadsPerSource)
        assertEquals(3, c.discovery.jobBoardLookupsPerRun)
        assertEquals(15, c.discovery.officerLookupsPerRun)
        assertEquals(60L, c.discovery.recheckAfterDays)
        assertEquals(14, c.discovery.jobsWithinDays)
        assertEquals(listOf(1.minutes, 3.minutes, 4.minutes, 60.minutes), listOf(c.loops.plan, c.loops.send, c.loops.replies, c.loops.periodic))
        assertEquals(20, c.database.maxPool)
        assertEquals(45L, c.httpTimeoutSeconds)
    }

    private val knobs = listOf(
        "RESEARCH_BATCH_SIZE", "RESEARCH_STALE_DAYS", "DISCOVERY_LEADS_PER_SOURCE", "DISCOVERY_JOB_BOARD_LOOKUPS", "DISCOVERY_OFFICER_LOOKUPS",
        "DISCOVERY_RECHECK_DAYS", "DISCOVERY_JOBS_WITHIN_DAYS", "LOOP_PLAN_MINUTES", "LOOP_SEND_MINUTES", "LOOP_REPLIES_MINUTES",
        "LOOP_PERIODIC_MINUTES", "DB_MAX_POOL", "HTTP_TIMEOUT_SECONDS",
    )

    @Test
    fun `a malformed or non positive knob stops the parse`() {
        knobs.forEach { name ->
            assertFailsWith<NumberFormatException>(name) { BloomConfig.from(env(name to "many")) }
            assertFailsWith<NumberFormatException>(name) { BloomConfig.from(env(name to "")) }
            val zero = assertFailsWith<IllegalStateException>(name) { BloomConfig.from(env(name to "0")) }
            assertTrue(zero.message!!.contains(name), "the error names $name")
            assertFailsWith<IllegalStateException>(name) { BloomConfig.from(env(name to "-3")) }
        }
    }

    @Test
    fun `france travail only accepts some publication windows`() {
        listOf(1, 3, 7, 14, 31).forEach { assertEquals(it, BloomConfig.from(env("DISCOVERY_JOBS_WITHIN_DAYS" to "$it")).discovery.jobsWithinDays) }
        listOf("2", "5", "30", "365").forEach {
            val e = assertFailsWith<IllegalStateException> { BloomConfig.from(env("DISCOVERY_JOBS_WITHIN_DAYS" to it)) }
            assertTrue(e.message!!.contains("1, 3, 7, 14, 31"))
        }
    }

    @Test
    fun `explicit values override the defaults`() {
        val c = BloomConfig.from(
            env(
                "PORT" to "9090", "DB_USER" to "u", "SMTP_PORT" to "465", "DRY_RUN" to "false", "MAX_PER_MAILBOX_PER_DAY" to "10",
                "SENDER_NAME" to "Alex", "LLM_DAILY_TOKEN_BUDGET" to "500000", "DISCOVERY_SOURCES" to " BODACC , ted ",
                "DISCOVERY_EXCLUDE" to "Acme, ,Globex", "CODEUR_MIN_BUDGET" to "2500",
            ),
        )
        assertEquals(9090, c.server.port)
        assertEquals("u", c.database.user)
        assertEquals(465, c.mail.smtpPort)
        assertFalse(c.mail.dryRun)
        assertEquals(10, c.sending.policy.maxPerMailboxPerDay)
        assertEquals("Alex", c.sending.senderName)
        assertEquals(500_000L, c.llm.dailyTokenBudget)
        assertEquals(setOf("bodacc", "ted"), c.discovery.sources)
        assertEquals(setOf("Acme", "Globex"), c.discovery.extraExclusions)
        assertEquals(2_500, c.discovery.codeurMinBudgetEur)
    }

    @Test
    fun `blank optional values count as absent`() {
        val c = BloomConfig.from(
            env(
                "LLM_MODEL" to "", "LLM_MODEL_FAST" to "  ", "LLM_DAILY_TOKEN_BUDGET" to "", "GITHUB_TOKEN" to "",
                "DROPCONTACT_API_KEY" to " ", "FT_CLIENT_ID" to "",
            ),
        )
        assertNull(c.llm.client.model)
        assertNull(c.llm.client.fastModel)
        assertEquals(0L, c.llm.dailyTokenBudget)
        assertNull(c.discovery.githubToken)
        assertNull(c.discovery.dropcontactApiKey)
        assertNull(c.discovery.franceTravail)
    }

    @Test
    fun `required secrets are checked`() {
        assertFailsWith<IllegalArgumentException> { BloomConfig.from(env() - "ADMIN_PASSWORD") }
        assertFailsWith<IllegalArgumentException> { BloomConfig.from(env() - "UNSUBSCRIBE_SECRET") }
        assertFailsWith<IllegalArgumentException> { BloomConfig.from(env("ADMIN_PASSWORD" to "change-me")) }
        assertFailsWith<IllegalArgumentException> { BloomConfig.from(env("UNSUBSCRIBE_SECRET" to "short")) }
    }

    @Test
    fun `dry run must be true or false`() {
        assertFailsWith<IllegalStateException> { BloomConfig.from(env("DRY_RUN" to "yes")) }
        assertTrue(BloomConfig.from(env("DRY_RUN" to "")).mail.dryRun)
    }

    @Test
    fun `a malformed number stops the parse`() {
        assertFailsWith<NumberFormatException> { BloomConfig.from(env("PORT" to "eighty")) }
        assertFailsWith<NumberFormatException> { BloomConfig.from(env("SMTP_PORT" to "")) }
    }

    @Test
    fun `mailboxes are split on commas and the display name may contain colons`() {
        val c = BloomConfig.from(env("MAILBOXES" to "a@x.fr:pw1:Alex Martin, b@x.fr : pw2 : Sam: Lead ,,"))
        assertEquals(
            listOf("a@x.fr" to "Alex Martin", "b@x.fr" to "Sam: Lead"),
            c.mail.mailboxes.map { it.address to it.displayName },
        )
        assertEquals(listOf("pw1", "pw2"), c.mail.mailboxes.map { it.password })
        assertTrue(BloomConfig.from(env("MAILBOXES" to "")).mail.mailboxes.isEmpty())
        for (bad in listOf("a@x.fr:pw", "a@x.fr", "a@x.fr:pw:")) {
            val e = assertFailsWith<IllegalStateException>(bad) { BloomConfig.from(env("MAILBOXES" to bad)) }
            assertTrue("MAILBOXES" in e.message.orEmpty() && "adresse:mot-de-passe:Nom" in e.message.orEmpty(), e.message)
        }
    }

    @Test
    fun `anthropic needs its api key`() {
        val withoutKey = env() - "ANTHROPIC_API_KEY"
        assertFailsWith<IllegalStateException> { BloomConfig.from(withoutKey) }
        assertFailsWith<IllegalStateException> { BloomConfig.from(withoutKey + ("ANTHROPIC_API_KEY" to " ")) }
        assertEquals("sk-test", BloomConfig.from(env()).llm.client.apiKey)
    }

    @Test
    fun `ollama and openai need no api key and read their own base url`() {
        val ollama = BloomConfig.from(env("LLM_PROVIDER" to "OLLAMA", "OLLAMA_BASE_URL" to "http://host:11434", "OPENAI_BASE_URL" to "http://ignored") - "ANTHROPIC_API_KEY")
        assertEquals(LlmProvider.OLLAMA, ollama.llm.client.provider)
        assertNull(ollama.llm.client.apiKey)
        assertEquals("http://host:11434", ollama.llm.client.baseUrl)

        val openai = BloomConfig.from(env("LLM_PROVIDER" to "openai", "OPENAI_API_KEY" to "k", "OPENAI_BASE_URL" to "http://lm:1234", "OLLAMA_BASE_URL" to "http://ignored") - "ANTHROPIC_API_KEY")
        assertEquals(LlmProvider.OPENAI, openai.llm.client.provider)
        assertEquals("k", openai.llm.client.apiKey)
        assertEquals("http://lm:1234", openai.llm.client.baseUrl)

        val anthropic = BloomConfig.from(env("OPENAI_BASE_URL" to "http://ignored"))
        assertNull(anthropic.llm.client.baseUrl)
    }

    @Test
    fun `an unknown provider is rejected`() {
        assertFailsWith<IllegalArgumentException> { BloomConfig.from(env("LLM_PROVIDER" to "gemini")) }
    }

    @Test
    fun `france travail needs a secret as soon as the id is set`() {
        assertFailsWith<IllegalStateException> { BloomConfig.from(env("FT_CLIENT_ID" to "id")) }
        val ft = BloomConfig.from(env("FT_CLIENT_ID" to "id", "FT_CLIENT_SECRET" to "s")).discovery.franceTravail
        assertEquals(FranceTravailCredentials("id", "s"), ft)
    }
}
