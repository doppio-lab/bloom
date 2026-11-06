package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.AgentStats
import lab.doppio.bloom.core.port.Blocked
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.WebPages
import lab.doppio.bloom.core.scoring.ScoreBreakdown
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Instant as KInstant

val FIXED_NOW: Instant = Instant.parse("2026-11-06T10:00:00Z")
val fixedClock: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

val testModel = LLModel(provider = LLMProvider.Ollama, id = "test-model", capabilities = emptyList(), contextLength = 8_192)

/**
 * A [PromptExecutor] that answers from a queue of canned texts (the last one repeats once the queue is empty)
 * and keeps every prompt it was given. No network, no provider client.
 */
class CannedExecutor(vararg answers: String) : PromptExecutor() {
    private val queue = ArrayDeque(answers.toList())
    val prompts = mutableListOf<Prompt>()
    val calls: Int get() = prompts.size

    /** Text of the agent's own user message in the n-th call (Koog appends a format instruction as a later user message). */
    fun userText(call: Int): String = prompts[call].messages.first { it is Message.User }.textContent()

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        prompts += prompt
        val text = if (queue.size > 1) queue.removeFirst() else queue.first()
        return Message.Assistant(text, ResponseMetaInfo(KInstant.fromEpochSeconds(0), inputTokensCount = 10, outputTokensCount = 5))
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = emptyFlow()
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
    override fun close() {}
}

fun fakeLlm(executor: PromptExecutor) = Llm("test", executor, research = testModel, fast = testModel)

class FakeAgentRunRepository(var tokensToday: Long = 0) : AgentRunRepository {
    data class Run(val agent: String, val accountId: AccountId?, val model: String, val inTok: Int, val outTok: Int, val success: Boolean, val error: String?)

    val runs = mutableListOf<Run>()
    var lastTokensSince: Instant? = null

    override suspend fun logAgentRun(agent: String, accountId: AccountId?, model: String, inTok: Int, outTok: Int, durationMs: Long, success: Boolean, error: String?) {
        runs += Run(agent, accountId, model, inTok, outTok, success, error)
    }

    override suspend fun tokensSince(since: Instant): Long = tokensToday.also { lastTokensSince = since }
    override suspend fun agentStats(since: Instant): List<AgentStats> = TODO()
}

class FakeAccountRepository(private val accounts: List<Account>) : AccountRepository {
    override suspend fun account(id: AccountId): Account? = accounts.firstOrNull { it.id == id }
    override suspend fun upsertAccount(a: Account): AccountId = TODO()
    override suspend fun mergeDiscovered(a: Account): Pair<AccountId, Boolean> = TODO()
    override suspend fun accountsByScore(limit: Int): List<AccountRow> = TODO()
    override suspend fun accountsToComplete(minScore: Int, limit: Int): List<AccountRow> = TODO()
    override suspend fun saveScore(s: ScoreBreakdown) = TODO()
    override suspend fun excludeAccount(id: AccountId, reason: String) = TODO()
    override suspend fun restoreAccount(id: AccountId) = TODO()
    override suspend fun blockName(name: String, reason: String?): AccountId? = TODO()
    override suspend fun unblockName(id: Long) = TODO()
    override suspend fun blockedNames(): Set<String> = TODO()
    override suspend fun blacklist(): List<Blocked> = TODO()
}

class FakeContactRepository(private val contacts: List<Contact>) : ContactRepository {
    override suspend fun contact(id: ContactId): Contact? = contacts.firstOrNull { it.id == id }
    override suspend fun addContact(c: Contact): ContactId = TODO()
    override suspend fun contactsOf(accountId: AccountId): List<Contact> = TODO()
    override suspend fun contactsByAccount(): Map<AccountId, List<Contact>> = TODO()
    override suspend fun contactByEmail(email: String): Contact? = TODO()
    override suspend fun updateContactEmail(contactId: ContactId, email: String, source: String) = TODO()
    override suspend fun markInbound(contactId: ContactId) = TODO()
    override suspend fun purgeStaleContacts(now: Instant?): Int = TODO()
}

class FakeDraftRepository : DraftRepository {
    val created = mutableListOf<Draft>()

    override suspend fun createDraft(d: Draft): Long { created += d; return created.size.toLong() }
    override suspend fun draftsByStatus(status: DraftStatus, channel: Channel?): List<Draft> = emptyList()
    override suspend fun humanEditedExamples(limit: Int): List<Draft> = emptyList()
    override suspend fun hasOpenDraft(enrollmentId: Long, stepIndex: Int): Boolean = TODO()
    override suspend fun rejectedDrafts(enrollmentId: Long, stepIndex: Int): Int = TODO()
    override suspend fun draft(id: Long): Draft? = TODO()
    override suspend fun decideDraft(id: Long, status: DraftStatus, editedSubject: String?, editedBody: String?) = TODO()
    override suspend fun nextToSend(now: Instant?): Draft? = TODO()
    override suspend fun claimForSending(id: Long, messageId: String, mailbox: String): Boolean = TODO()
    override suspend fun recordSent(draft: Draft, threadMessageId: String, now: Instant?) = TODO()
    override suspend fun recordSendFailure(id: Long, error: String, now: Instant?, maxAttempts: Int): DraftStatus = TODO()
    override suspend fun reconcileInterruptedSends(): Int = TODO()
    override suspend fun sentTodayBy(mailbox: String, since: Instant): Int = TODO()
    override suspend fun lastSentAt(mailbox: String): Instant? = TODO()
    override suspend fun enrollmentByMessageId(messageId: String): Long? = TODO()
}

class FakeEventRepository : EventRepository {
    data class Event(val type: String, val accountId: AccountId?, val payload: String)

    val events = mutableListOf<Event>()

    override suspend fun logEvent(type: String, accountId: AccountId?, contactId: ContactId?, draftId: Long?, payload: String) {
        events += Event(type, accountId, payload)
    }

    override suspend fun lastEvent(type: String): Pair<String, Instant>? = TODO()
    override suspend fun countEvents(type: String, since: Instant): Int = TODO()
    override suspend fun eventTimes(types: Set<String>, since: Instant): List<Pair<String, Instant>> = TODO()
    override suspend fun eventsSince(since: Instant): List<Triple<String, Long?, Instant>> = TODO()
}

class FakeFactRepository(private val facts: List<Fact>, private val dossier: Dossier? = null) : FactRepository {
    override suspend fun factsOf(accountId: AccountId, maxAgeDays: Long): List<Fact> = facts.filter { it.accountId == accountId }
    override suspend fun dossier(accountId: AccountId): Dossier? = dossier
    override suspend fun saveFacts(facts: List<Fact>) = TODO()
    override suspend fun saveSignals(signals: List<Signal>) = TODO()
    override suspend fun signalsOf(accountId: AccountId, maxAgeDays: Long): List<Signal> = TODO()
    override suspend fun dominantSignal(accountId: AccountId): SignalType? = TODO()
    override suspend fun saveDossier(d: Dossier) = TODO()
}

/** Serves canned pages by URL and counts the reads. */
class FakeWebPages(private val pages: Map<String, String>) : WebPages {
    val reads = mutableListOf<String>()

    override suspend fun read(accountId: AccountId, url: String, maxChars: Int): Pair<Fact, String>? {
        reads += url
        val text = pages[url] ?: return null
        return lab.doppio.bloom.core.model.fact(accountId, lab.doppio.bloom.core.model.FactKind.WEB_PAGE, "Page lue : $url", url) to text
    }
}
