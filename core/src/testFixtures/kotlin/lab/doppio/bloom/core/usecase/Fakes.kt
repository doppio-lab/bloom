package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.discovery.BoardRef
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.Demand
import lab.doppio.bloom.core.model.DiscoveryReport
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.InboundMail
import lab.doppio.bloom.core.model.Mailbox
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.model.ReplyClassification
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.AgentStats
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.InstanceLock
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.Blocked
import lab.doppio.bloom.core.port.Coach
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.Discovery
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.Drafter
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.Inbox
import lab.doppio.bloom.core.port.JobRunRepository
import lab.doppio.bloom.core.port.MailSender
import lab.doppio.bloom.core.port.OfficerRepository
import lab.doppio.bloom.core.port.OfficerRow
import lab.doppio.bloom.core.port.ReplyClassifier
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.ReplyRow
import lab.doppio.bloom.core.port.Researcher
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.core.scoring.ScoreBreakdown
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Wednesday 4 November 2026, 10:00 in Paris: inside the default sending window. */
val WEDNESDAY_MORNING: Instant = Instant.parse("2026-11-04T09:00:00Z")

fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

val mailboxA = Mailbox("a@doppio.test", "pw", "A")
val mailboxB = Mailbox("b@doppio.test", "pw", "B")

class FakeSequenceRepository : SequenceRepository {
    val sequences = mutableListOf<Sequence>()
    val enrollments = mutableMapOf<Long, Enrollment>()
    val threads = mutableMapOf<Long, String>()
    private var nextId = 1L

    fun addEnrollment(contactId: ContactId, status: EnrollmentStatus = EnrollmentStatus.ACTIVE, sequenceId: Long = 1, nextStep: Int = 0): Enrollment {
        val e = Enrollment(nextId++, contactId, sequenceId, status, nextStep, null, "2026-11-01T09:00:00Z")
        enrollments[e.id] = e
        return e
    }

    fun status(id: Long) = enrollments.getValue(id).status

    override suspend fun ensureSequences(defaults: List<Sequence>) { sequences += defaults }
    override suspend fun all(): List<Sequence> = sequences
    override suspend fun sequenceFor(offer: Offer): Sequence? = sequences.firstOrNull { it.offer == offer }
    override suspend fun enroll(contactId: ContactId, sequenceId: Long): Long = addEnrollment(contactId, sequenceId = sequenceId).id
    override suspend fun enrollment(id: Long): Enrollment? = enrollments[id]
    override suspend fun activeEnrollments(): List<Enrollment> = enrollments.values.filter { it.status == EnrollmentStatus.ACTIVE }
    override suspend fun enrollmentsOfContact(contactId: ContactId): List<Enrollment> = enrollments.values.filter { it.contactId == contactId }
    override suspend fun advance(enrollmentId: Long, nextStep: Int, threadMessageId: String?) {
        enrollments[enrollmentId] = enrollments.getValue(enrollmentId).copy(nextStep = nextStep)
    }
    override suspend fun threadMessageId(enrollmentId: Long): String? = threads[enrollmentId]
    override suspend fun setEnrollmentStatus(enrollmentId: Long, status: EnrollmentStatus) {
        enrollments[enrollmentId] = enrollments.getValue(enrollmentId).copy(status = status)
    }
}

class FakeContactRepository : ContactRepository {
    val contacts = mutableMapOf<ContactId, Contact>()
    val inbound = mutableListOf<ContactId>()
    private var nextId = 1L

    fun add(email: String? = "jane@client.test", accountId: AccountId = 10, name: String = "Jane Doe"): Contact {
        val c = Contact(nextId++, accountId, name, "CTO", Persona.CTO, email, null, "test")
        contacts[c.id] = c
        return c
    }

    override suspend fun addContact(c: Contact): ContactId { val id = nextId++; contacts[id] = c.copy(id = id); return id }
    override suspend fun contact(id: ContactId): Contact? = contacts[id]
    override suspend fun contactsOf(accountId: AccountId): List<Contact> = contacts.values.filter { it.accountId == accountId }
    override suspend fun contactsByAccount(): Map<AccountId, List<Contact>> = contacts.values.groupBy { it.accountId }
    override suspend fun contactByEmail(email: String): Contact? = contacts.values.firstOrNull { it.email.equals(email, ignoreCase = true) }
    override suspend fun updateContactEmail(contactId: ContactId, email: String, source: String) { contacts[contactId] = contacts.getValue(contactId).copy(email = email) }
    override suspend fun markInbound(contactId: ContactId) { inbound += contactId }
    override suspend fun purgeStaleContacts(now: Instant?): Int = 2
}

class FakeDraftRepository : DraftRepository {
    val drafts = mutableMapOf<Long, Draft>()
    val rejections = mutableMapOf<Pair<Long, Int>, Int>()
    val sentToday = mutableMapOf<String, Int>()
    val lastSent = mutableMapOf<String, Instant>()
    val messageIdToEnrollment = mutableMapOf<String, Long>()
    var claimWins = true
    var failureStatus = DraftStatus.APPROVED
    val claims = mutableListOf<Triple<Long, String, String>>()
    val sent = mutableListOf<Pair<Draft, String>>()
    val failures = mutableListOf<Pair<Long, String>>()
    private var nextId = 1L

    fun add(enrollmentId: Long, status: DraftStatus, step: Int = 0, channel: Channel = Channel.EMAIL, body: String = "Bonjour"): Draft {
        val d = Draft(nextId++, enrollmentId, step, channel, "Sujet", body, emptyList(), null, "", status)
        drafts[d.id] = d
        return d
    }

    fun status(id: Long) = drafts.getValue(id).status

    override suspend fun hasOpenDraft(enrollmentId: Long, stepIndex: Int): Boolean =
        drafts.values.any { it.enrollmentId == enrollmentId && it.stepIndex == stepIndex && it.status in setOf(DraftStatus.PENDING_REVIEW, DraftStatus.APPROVED, DraftStatus.SENDING) }
    override suspend fun rejectedDrafts(enrollmentId: Long, stepIndex: Int): Int = rejections[enrollmentId to stepIndex] ?: 0
    override suspend fun createDraft(d: Draft): Long { val id = nextId++; drafts[id] = d.copy(id = id); return id }
    override suspend fun draft(id: Long): Draft? = drafts[id]
    override suspend fun draftsByStatus(status: DraftStatus, channel: Channel?): List<Draft> =
        drafts.values.filter { it.status == status && (channel == null || it.channel == channel) }
    override suspend fun decideDraft(id: Long, status: DraftStatus, editedSubject: String?, editedBody: String?) {
        drafts[id] = drafts.getValue(id).copy(status = status, subject = editedSubject ?: drafts.getValue(id).subject, body = editedBody ?: drafts.getValue(id).body)
    }
    override suspend fun humanEditedExamples(limit: Int): List<Draft> = emptyList()
    override suspend fun nextToSend(now: Instant?): Draft? = drafts.values.firstOrNull { it.status == DraftStatus.APPROVED && it.channel == Channel.EMAIL }
    override suspend fun claimForSending(id: Long, messageId: String, mailbox: String): Boolean {
        if (!claimWins) return false
        claims += Triple(id, messageId, mailbox)
        drafts[id] = drafts.getValue(id).copy(status = DraftStatus.SENDING)
        return true
    }
    override suspend fun recordSent(draft: Draft, threadMessageId: String, now: Instant?) {
        sent += draft to threadMessageId
        drafts[draft.id] = draft.copy(status = DraftStatus.SENT)
    }
    override suspend fun recordSendFailure(id: Long, error: String, now: Instant?, maxAttempts: Int): DraftStatus {
        failures += id to error
        drafts[id] = drafts.getValue(id).copy(status = failureStatus)
        return failureStatus
    }
    override suspend fun reconcileInterruptedSends(): Int = 0
    override suspend fun sentTodayBy(mailbox: String, since: Instant): Int = sentToday[mailbox] ?: 0
    override suspend fun lastSentAt(mailbox: String): Instant? = lastSent[mailbox]
    override suspend fun enrollmentByMessageId(messageId: String): Long? = messageIdToEnrollment[messageId]
}

class FakeReplyRepository : ReplyRepository {
    val suppressed = mutableMapOf<String, String>()
    val saved = mutableListOf<ReplyIntent>()
    val recordedKeys = mutableSetOf<String>()

    override suspend fun saveReply(
        enrollmentId: Long?, from: String, subject: String?, body: String, intent: ReplyIntent,
        confidence: Double, followUpOn: LocalDate?, summary: String, receivedAt: Instant, inboundKey: String?,
    ): Long { saved += intent; inboundKey?.let { recordedKeys += it }; return saved.size.toLong() }
    override suspend fun replyRecorded(inboundKey: String): Boolean = inboundKey in recordedKeys
    override suspend fun openReplies(): List<ReplyRow> = emptyList()
    override suspend fun countOpenReplies(): Long = 0
    override suspend fun markReplyHandled(id: Long) {}
    override suspend fun isSuppressed(email: String): Boolean = email in suppressed
    override suspend fun suppress(value: String, reason: String) { suppressed[value] = reason }
}

class FakeEventRepository : EventRepository {
    val events = mutableListOf<Pair<String, String>>()
    val types: List<String> get() = events.map { it.first }

    override suspend fun logEvent(type: String, accountId: AccountId?, contactId: ContactId?, draftId: Long?, payload: String) { events += type to payload }
    override suspend fun lastEvent(type: String): Pair<String, Instant>? = null
    override suspend fun countEvents(type: String, since: Instant): Int = 0
    override suspend fun eventTimes(types: Set<String>, since: Instant): List<Pair<String, Instant>> = emptyList()
    override suspend fun eventsSince(since: Instant): List<Triple<String, Long?, Instant>> = emptyList()
}

class FakeJobRunRepository : JobRunRepository {
    val claimed = mutableSetOf<Pair<String, LocalDate>>()
    val finished = mutableListOf<Pair<String, String?>>()

    override suspend fun claimJob(name: String, period: LocalDate, now: Instant?): Boolean = claimed.add(name to period)
    override suspend fun finishJob(name: String, error: String?, now: Instant?) { finished += name to error }
}

class FakeFactRepository(var dossier: Dossier? = null) : FactRepository {
    var facts: List<Fact> = emptyList()
    var signals: List<Signal> = emptyList()
    override suspend fun saveFacts(facts: List<Fact>) {}
    override suspend fun factsOf(accountId: AccountId, maxAgeDays: Long): List<Fact> = facts.filter { it.accountId == accountId }
    override suspend fun saveSignals(signals: List<Signal>) {}
    override suspend fun signalsOf(accountId: AccountId, maxAgeDays: Long): List<Signal> = signals.filter { it.accountId == accountId }
    override suspend fun dominantSignal(accountId: AccountId): SignalType? = null
    override suspend fun saveDossier(d: Dossier) { dossier = d }
    override suspend fun dossier(accountId: AccountId): Dossier? = dossier
}

class FakeAccountRepository(var rows: List<AccountRow> = emptyList()) : AccountRepository {
    override suspend fun upsertAccount(a: Account): AccountId = TODO()
    override suspend fun mergeDiscovered(a: Account): Pair<AccountId, Boolean> = TODO()
    override suspend fun account(id: AccountId): Account? = rows.firstOrNull { it.account.id == id }?.account
    override suspend fun accountsByScore(limit: Int): List<AccountRow> = rows
    var toComplete: List<AccountRow> = emptyList()
    override suspend fun accountsToComplete(minScore: Int, limit: Int): List<AccountRow> = toComplete
    override suspend fun saveScore(s: ScoreBreakdown) {}
    override suspend fun excludeAccount(id: AccountId, reason: String) {}
    override suspend fun restoreAccount(id: AccountId) {}
    override suspend fun blockName(name: String, reason: String?): AccountId? = null
    override suspend fun unblockName(id: Long) {}
    override suspend fun blockedNames(): Set<String> = emptySet()
    override suspend fun blacklist(): List<Blocked> = emptyList()
}

class FakeOfficerRepository : OfficerRepository {
    override suspend fun saveOfficers(accountId: AccountId, officers: List<OfficerRow>) {}
    override suspend fun officersByAccount(): Map<AccountId, List<OfficerRow>> = emptyMap()
    override suspend fun accountsWithoutOfficers(limit: Int): List<Pair<AccountId, String>> = emptyList()
    override suspend fun purgeStaleOfficers(now: Instant?): Int = 1
}

class FakeDiscoveryStateRepository : DiscoveryStateRepository {
    override suspend fun wasSeen(key: String, withinDays: Long): Boolean = false
    override suspend fun markSeen(key: String, kept: Boolean, reason: String?) {}
    override suspend fun saveDemand(d: Demand): Boolean = true
    override suspend fun openDemands(limit: Int): List<Demand> = emptyList()
    override suspend fun dismissDemand(key: String): Boolean = true
    override suspend fun purgeOldDemands(now: Instant?): Int = 3
    override suspend fun jobBoard(accountId: AccountId): Pair<BoardRef?, Instant>? = null
    override suspend fun saveJobBoard(accountId: AccountId, ref: BoardRef?) {}
}

class FakeMailSender : MailSender {
    data class Sent(val mailbox: Mailbox, val to: String, val subject: String, val body: String, val inReplyTo: String?, val messageId: String)

    val sent = mutableListOf<Sent>()
    var failure: Exception? = null
    private var counter = 0

    override fun newMessageId(mailbox: Mailbox): String = "<id${++counter}@${mailbox.address.substringAfter('@')}>"
    override suspend fun send(mailbox: Mailbox, to: String, subject: String, body: String, contactId: Long, language: String, inReplyTo: String?, messageId: String): String {
        failure?.let { throw it }
        sent += Sent(mailbox, to, subject, body, inReplyTo, messageId)
        return messageId
    }
}

class FakeInbox(var mails: List<InboundMail> = emptyList()) : Inbox {
    val seen = mutableMapOf<String, List<Long>>()

    override suspend fun poll(): List<InboundMail> = mails
    override suspend fun markSeen(mailboxAddress: String, uids: List<Long>) { seen[mailboxAddress] = (seen[mailboxAddress].orEmpty()) + uids }
}

fun inbound(uid: Long, from: String = "jane@client.test", body: String = "Merci", bounce: Boolean = false, inReplyTo: List<String> = emptyList()) =
    InboundMail("a@doppio.test", from, "Re: Sujet", body, inReplyTo, WEDNESDAY_MORNING, bounce, uid, "<m$uid@client.test>")

class FakeClassifier(var intent: ReplyIntent = ReplyIntent.NOT_INTERESTED, var failure: Exception? = null) : ReplyClassifier {
    override suspend fun classify(subject: String?, body: String, accountId: Long?): ReplyClassification {
        failure?.let { throw it }
        return ReplyClassification(intent, 0.9, null, null, "résumé", null)
    }
}

class FakeDrafter(var failure: Exception? = null) : Drafter {
    val calls = mutableListOf<Pair<Long, Int>>()

    override suspend fun draft(enrollment: Enrollment, sequence: Sequence, step: SequenceStep): Long {
        failure?.let { throw it }
        calls += enrollment.id to step.index
        return calls.size.toLong()
    }
}

class FakeResearcher : Researcher {
    val researched = mutableListOf<Long>()

    override suspend fun research(accountId: Long): ScoreBreakdown { researched += accountId; return ScoreBreakdown(accountId, 0, 0, 0, 0, null, emptyList()) }
}

class FakeCoach : Coach {
    override suspend fun weeklyMemo(): String = "note du lundi"
}

class FakeDiscovery : Discovery {
    var runs = 0
    var polls = 0

    override suspend fun run(): DiscoveryReport { runs++; return DiscoveryReport() }
    override suspend fun pollDemands(): Int { polls++; return 0 }
}

fun emailSequence(offer: Offer = Offer.DISCOVERY, id: Long = 1) = Sequence(id, "test", offer, listOf(SequenceStep(0, Channel.EMAIL, 0, "intent"), SequenceStep(1, Channel.EMAIL, 2, "relance")))

class FakeDealRepository : DealRepository {
    val deals = mutableListOf<Deal>()

    override suspend fun upsertDeal(d: Deal): Long {
        val id = if (d.id == 0L) deals.size + 1L else d.id
        deals.removeAll { it.id == id }
        deals += d.copy(id = id)
        return id
    }
    override suspend fun deals(): List<Deal> = deals
}

class FakeAgentRunRepository : AgentRunRepository {
    override suspend fun logAgentRun(agent: String, accountId: AccountId?, model: String, inTok: Int, outTok: Int, durationMs: Long, success: Boolean, error: String?) {}
    override suspend fun tokensSince(since: Instant): Long = 0
    override suspend fun agentStats(since: Instant): List<AgentStats> = emptyList()
}

class FakeInstanceLock(var held: Boolean = true) : InstanceLock {
    override fun tryHold(): Boolean = held
}
