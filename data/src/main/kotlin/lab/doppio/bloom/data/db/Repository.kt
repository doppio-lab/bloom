package lab.doppio.bloom.data.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import lab.doppio.bloom.core.discovery.BoardProvider
import lab.doppio.bloom.core.discovery.BoardRef
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.scoring.ScoreBreakdown
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val stringList = ListSerializer(String.serializer())
private fun List<String>.encode() = json.encodeToString(stringList, this)
private fun String.decodeList(): List<String> = json.decodeFromString(stringList, this)

/** A request published by a buyer on a marketplace: answered on the platform, never by email. */
@Serializable
data class Demand(
    val key: String,
    val source: String,
    val title: String,
    val url: String,
    val budget: String? = null,
    val categories: List<String> = emptyList(),
    val publishedAt: String? = null,
)

/** One line of the blacklist: an excluded account (accountId set) or a name blocked before any account exists. */
@Serializable
data class Blocked(val id: Long, val accountId: AccountId? = null, val name: String, val reason: String? = null, val since: String? = null)

/** A person published as legal officer by the company registry. */
@Serializable
data class OfficerRow(val fullName: String, val role: String, val sourceUrl: String)

@Serializable
data class Dossier(
    val accountId: AccountId,
    val summary: String,
    val hypotheses: List<String>,
    val angle: String,
    val offer: Offer,
)

@Serializable
data class AccountRow(
    val account: Account, val score: Int, val fit: Int, val intent: Int, val warm: Int,
    val recommendedOffer: Offer?, val reasons: List<String>, val refreshedAt: String?,
)

@Serializable
data class ReplyRow(
    val id: Long, val enrollmentId: Long?, val from: String, val intent: ReplyIntent, val summary: String,
    val followUpOn: String?, val receivedAt: String, val handled: Boolean,
)

class Repository {

    // --- Comptes -------------------------------------------------------------------------------

    private fun findAccountIdTx(a: Account): AccountId? {
        val slug = CompanyNames.normalize(a.name)
        return Accounts.selectAll().where { Accounts.slug eq slug }.firstOrNull()?.get(Accounts.id)
            ?: a.domain?.let { d -> Accounts.selectAll().where { Accounts.domain eq d }.firstOrNull()?.get(Accounts.id) }
    }

    /** Création ou mise à jour (saisie manuelle, import) : les champs fournis écrasent les anciens. */
    suspend fun upsertAccount(a: Account): AccountId = dbQuery {
        val existing = findAccountIdTx(a)
        if (existing != null) {
            Accounts.update({ Accounts.id eq existing }) { it.fill(a) }
            existing
        } else {
            Accounts.insert { it.fill(a); it[origin] = a.source } get Accounts.id
        }
    }

    /**
     * Découverte : crée le compte s'il est nouveau, sinon complète seulement les champs vides.
     * Renvoie l'id et vrai si le compte vient d'être créé.
     */
    suspend fun mergeDiscovered(a: Account): Pair<AccountId, Boolean> = dbQuery {
        val existing = findAccountIdTx(a)
        if (existing == null) {
            (Accounts.insert { it.fill(a); it[origin] = a.source } get Accounts.id) to true
        } else {
            val row = Accounts.selectAll().where { Accounts.id eq existing }.first()
            val fillDomain = row[Accounts.domain] == null && a.domain != null
            val fillSiren = row[Accounts.siren] == null && a.siren != null
            val fillHeadcount = row[Accounts.headcount] == null && a.headcount != null
            val fillApp = row[Accounts.iosAppId] == null && a.iosAppId != null
            val fillSector = row[Accounts.sector] == "OTHER" && a.sector.name != "OTHER"
            // Exposed refuses an UPDATE that sets nothing: an account that is already complete is left alone
            if (fillDomain || fillSiren || fillHeadcount || fillApp || fillSector) {
                Accounts.update({ Accounts.id eq existing }) {
                    if (fillDomain) it[domain] = a.domain
                    if (fillSiren) it[siren] = a.siren
                    if (fillHeadcount) it[headcount] = a.headcount
                    if (fillApp) it[iosAppId] = a.iosAppId
                    if (fillSector) it[sector] = a.sector.name
                }
            }
            existing to false
        }
    }

    /**
     * Puts an account on the blacklist. It disappears from every list, its running sequences stop and
     * its drafts waiting for review are rejected. Discovery keeps finding it by name and leaves it excluded.
     */
    suspend fun excludeAccount(id: AccountId, reason: String) = dbQuery {
        Accounts.update({ Accounts.id eq id }) { it[excludedReason] = reason; it[excludedAt] = Instant.now() }
        val contactIds = Contacts.selectAll().where { Contacts.accountId eq id }.map { it[Contacts.id] }
        if (contactIds.isNotEmpty()) {
            val open = Enrollments.selectAll()
                .where { (Enrollments.contactId inList contactIds) and (Enrollments.status inList listOf(EnrollmentStatus.ACTIVE.name, EnrollmentStatus.PAUSED.name)) }
                .map { it[Enrollments.id] }
            if (open.isNotEmpty()) {
                Enrollments.update({ Enrollments.id inList open }) { it[status] = EnrollmentStatus.STOPPED_MANUAL.name }
                Drafts.update({ (Drafts.enrollmentId inList open) and (Drafts.status inList listOf(DraftStatus.PENDING_REVIEW.name, DraftStatus.APPROVED.name)) }) {
                    it[status] = DraftStatus.REJECTED.name
                }
            }
        }
    }

    /** Takes an account off the blacklist, and its name with it so discovery stops excluding it. Stopped sequences are not restarted. */
    suspend fun restoreAccount(id: AccountId) = dbQuery {
        Accounts.update({ Accounts.id eq id }) { it[excludedReason] = null; it[excludedAt] = null }
        val slug = Accounts.selectAll().where { Accounts.id eq id }.firstOrNull()?.get(Accounts.slug)
        if (slug != null) Blocklist.deleteWhere { Blocklist.slug eq slug }
    }

    /** Blocks a name for discovery and excludes the account that already carries it. Returns that account, if any. */
    suspend fun blockName(name: String, reason: String?): AccountId? {
        val slug = CompanyNames.normalize(name)
        require(slug.isNotEmpty()) { "Nom vide" }
        dbQuery { Blocklist.insertIgnore { it[this.name] = name.trim(); it[this.slug] = slug; it[this.reason] = reason } }
        val existing = dbQuery { Accounts.selectAll().where { (Accounts.slug eq slug) and Accounts.excludedReason.isNull() }.firstOrNull()?.get(Accounts.id) }
        if (existing != null) excludeAccount(existing, reason ?: "liste noire")
        return existing
    }

    suspend fun unblockName(id: Long) = dbQuery { Blocklist.deleteWhere { Blocklist.id eq id } }

    suspend fun blockedNames(): Set<String> = dbQuery { Blocklist.selectAll().map { it[Blocklist.name] }.toSet() }

    suspend fun blacklist(): List<Blocked> = dbQuery {
        val accounts = Accounts.selectAll().where { Accounts.excludedReason.isNotNull() }.orderBy(Accounts.excludedAt, SortOrder.DESC_NULLS_LAST).map {
            Blocked(it[Accounts.id], it[Accounts.id], it[Accounts.name], it[Accounts.excludedReason], it[Accounts.excludedAt]?.toString())
        }
        val excludedSlugs = Accounts.selectAll().where { Accounts.excludedReason.isNotNull() }.map { it[Accounts.slug] }.toSet()
        // A blocked name that already shows as an excluded account is listed once, as the account
        val names = Blocklist.selectAll().orderBy(Blocklist.createdAt, SortOrder.DESC).filter { it[Blocklist.slug] !in excludedSlugs }.map {
            Blocked(it[Blocklist.id], null, it[Blocklist.name], it[Blocklist.reason], it[Blocklist.createdAt].toString())
        }
        accounts + names
    }

    // --- Dirigeants publiés par l'annuaire des entreprises ---------------------------------------

    suspend fun saveOfficers(accountId: AccountId, officers: List<OfficerRow>) = dbQuery {
        officers.forEach { o ->
            Officers.insertIgnore { it[this.accountId] = accountId; it[fullName] = o.fullName; it[role] = o.role; it[sourceUrl] = o.sourceUrl }
        }
        Accounts.update({ Accounts.id eq accountId }) { it[officersCheckedAt] = Instant.now() }
    }

    suspend fun officersByAccount(): Map<AccountId, List<OfficerRow>> = dbQuery {
        Officers.selectAll().groupBy({ it[Officers.accountId] }) { OfficerRow(it[Officers.fullName], it[Officers.role], it[Officers.sourceUrl]) }
    }

    /** Accounts with a SIREN whose officers were never read, best score first. */
    suspend fun accountsWithoutOfficers(limit: Int): List<Pair<AccountId, String>> = dbQuery {
        Accounts.selectAll()
            .where { Accounts.siren.isNotNull() and Accounts.officersCheckedAt.isNull() and Accounts.excludedReason.isNull() }
            .orderBy(Accounts.score, SortOrder.DESC).limit(limit).map { it[Accounts.id] to it[Accounts.siren]!! }
    }

    suspend fun contactsByAccount(): Map<AccountId, List<Contact>> = dbQuery {
        Contacts.selectAll().orderBy(Contacts.createdAt, SortOrder.ASC).map { it.toContact() }.groupBy { it.accountId }
    }

    suspend fun wasSeen(key: String, withinDays: Long = 30): Boolean = dbQuery {
        val since = Instant.now().minus(withinDays, ChronoUnit.DAYS)
        DiscoverySeen.selectAll().where { (DiscoverySeen.key eq key) and (DiscoverySeen.checkedAt greaterEq since) }.any()
    }

    suspend fun markSeen(key: String, kept: Boolean, reason: String? = null) = dbQuery {
        DiscoverySeen.upsert { it[this.key] = key; it[this.kept] = kept; it[this.reason] = reason; it[checkedAt] = Instant.now() }
    }

    /** Saves a demand the first time it is seen. True when it is new. */
    suspend fun saveDemand(d: Demand): Boolean = dbQuery {
        Demands.insertIgnore {
            it[key] = d.key; it[origin] = d.source; it[title] = d.title; it[url] = d.url; it[budget] = d.budget
            it[categories] = d.categories.encode(); it[publishedAt] = d.publishedAt?.let(Instant::parse)
        }.insertedCount > 0
    }

    suspend fun openDemands(limit: Int = 30): List<Demand> = dbQuery {
        Demands.selectAll().where { Demands.dismissedAt.isNull() }.orderBy(Demands.publishedAt, SortOrder.DESC_NULLS_LAST).limit(limit).map {
            Demand(it[Demands.key], it[Demands.origin], it[Demands.title], it[Demands.url], it[Demands.budget],
                it[Demands.categories].decodeList(), it[Demands.publishedAt]?.toString())
        }
    }

    /** True when the demand exists. */
    suspend fun dismissDemand(key: String): Boolean = dbQuery {
        Demands.update({ Demands.key eq key }) { it[dismissedAt] = Instant.now() } > 0
    }

    /** Demands are only useful while the buyer is choosing: drop them after 90 days. */
    suspend fun purgeOldDemands(now: Instant = Instant.now()): Int = dbQuery {
        val limit = now.minus(90, ChronoUnit.DAYS)
        Demands.deleteWhere { Demands.createdAt.less(limit) }
    }

    /** Job board remembered for an account. Null: never looked. A null ref inside: looked, none found. */
    suspend fun jobBoard(accountId: AccountId): Pair<BoardRef?, Instant>? = dbQuery {
        AccountJobBoards.selectAll().where { AccountJobBoards.accountId eq accountId }.firstOrNull()?.let { row ->
            val provider = row[AccountJobBoards.provider]?.let { runCatching { BoardProvider.valueOf(it) }.getOrNull() }
            val token = row[AccountJobBoards.token]
            (if (provider != null && token != null) BoardRef(provider, token) else null) to row[AccountJobBoards.checkedAt]
        }
    }

    suspend fun saveJobBoard(accountId: AccountId, ref: BoardRef?) = dbQuery {
        AccountJobBoards.upsert {
            it[this.accountId] = accountId; it[provider] = ref?.provider?.name; it[token] = ref?.token; it[checkedAt] = Instant.now()
        }
    }

    /** Comptes intéressants (score >= 45) à qui il manque la personne à contacter. */
    suspend fun accountsToComplete(minScore: Int = 45, limit: Int = 30): List<AccountRow> {
        val withContact = dbQuery { Contacts.selectAll().map { it[Contacts.accountId] }.toSet() }
        return accountsByScore(500).filter { it.score >= minScore && it.account.id !in withContact }.take(limit)
    }

    /** Signal le plus fort observé sur un compte : sert à mesurer ce qui convertit. */
    suspend fun dominantSignal(accountId: AccountId): SignalType? = signalsOf(accountId)
        .maxByOrNull { lab.doppio.bloom.core.scoring.Scoring.rules.getValue(it.type).weight * it.strength }?.type

    private fun org.jetbrains.exposed.sql.statements.UpdateBuilder<*>.fill(a: Account) {
        this[Accounts.name] = a.name
        this[Accounts.slug] = CompanyNames.normalize(a.name)
        this[Accounts.domain] = a.domain
        this[Accounts.siren] = a.siren
        this[Accounts.sector] = a.sector.name
        this[Accounts.market] = a.market.name
        this[Accounts.headcount] = a.headcount
        this[Accounts.iosAppId] = a.iosAppId
        this[Accounts.androidPackage] = a.androidPackage
        this[Accounts.warmPath] = a.warmPath
        this[Accounts.ownerNote] = a.ownerNote
    }

    private fun ResultRow.toAccount() = Account(
        id = this[Accounts.id], name = this[Accounts.name], domain = this[Accounts.domain], siren = this[Accounts.siren],
        sector = Sector.valueOf(this[Accounts.sector]), market = Market.valueOf(this[Accounts.market]),
        headcount = this[Accounts.headcount], iosAppId = this[Accounts.iosAppId], androidPackage = this[Accounts.androidPackage],
        warmPath = this[Accounts.warmPath], ownerNote = this[Accounts.ownerNote], source = this[Accounts.origin],
    )

    suspend fun account(id: AccountId): Account? = dbQuery {
        Accounts.selectAll().where { Accounts.id eq id }.firstOrNull()?.toAccount()
    }

    suspend fun accountsByScore(limit: Int = 200): List<AccountRow> = dbQuery {
        Accounts.selectAll().where { Accounts.excludedReason.isNull() }.orderBy(Accounts.score, SortOrder.DESC).limit(limit).map {
            val detail = runCatching { json.decodeFromString(ScoreBreakdown.serializer(), it[Accounts.scoreDetail]) }.getOrNull()
            AccountRow(
                it.toAccount(), it[Accounts.score], detail?.fit ?: 0, detail?.intent ?: 0, detail?.warm ?: 0,
                it[Accounts.recommendedOffer]?.let(Offer::valueOf),
                detail?.reasons.orEmpty(), it[Accounts.refreshedAt]?.toString(),
            )
        }
    }

    suspend fun saveScore(s: ScoreBreakdown) = dbQuery {
        Accounts.update({ Accounts.id eq s.accountId }) {
            it[score] = s.total
            it[scoreDetail] = json.encodeToString(ScoreBreakdown.serializer(), s)
            it[recommendedOffer] = s.recommendedOffer?.name
            it[refreshedAt] = Instant.now()
        }
    }

    // --- Contacts ------------------------------------------------------------------------------

    suspend fun addContact(c: Contact): ContactId = dbQuery {
        Contacts.insert {
            it[accountId] = c.accountId; it[fullName] = c.fullName; it[role] = c.role; it[persona] = c.persona.name
            it[email] = c.email?.lowercase(); it[linkedinUrl] = c.linkedinUrl; it[dataSource] = c.dataSource; it[language] = c.language
        } get Contacts.id
    }

    private fun ResultRow.toContact() = Contact(
        id = this[Contacts.id], accountId = this[Contacts.accountId], fullName = this[Contacts.fullName], role = this[Contacts.role],
        persona = Persona.valueOf(this[Contacts.persona]), email = this[Contacts.email], linkedinUrl = this[Contacts.linkedinUrl],
        dataSource = this[Contacts.dataSource], language = this[Contacts.language],
    )

    suspend fun contact(id: ContactId): Contact? = dbQuery { Contacts.selectAll().where { Contacts.id eq id }.firstOrNull()?.toContact() }
    suspend fun contactsOf(accountId: AccountId): List<Contact> = dbQuery { Contacts.selectAll().where { Contacts.accountId eq accountId }.map { it.toContact() } }
    suspend fun contactByEmail(email: String): Contact? = dbQuery { Contacts.selectAll().where { Contacts.email eq email.lowercase() }.firstOrNull()?.toContact() }

    suspend fun updateContactEmail(contactId: ContactId, email: String, source: String) = dbQuery {
        Contacts.update({ Contacts.id eq contactId }) { it[this.email] = email.lowercase(); it[dataSource] = source }
    }

    suspend fun markInbound(contactId: ContactId) = dbQuery {
        Contacts.update({ Contacts.id eq contactId }) { it[lastInboundAt] = Instant.now() }
    }

    /** RGPD : purge des contacts sans échange depuis 3 ans (recommandation CNIL). Les oppositions restent. */
    suspend fun purgeStaleContacts(now: Instant = Instant.now()): Int = dbQuery {
        val limit = now.minus(3 * 365L, ChronoUnit.DAYS)
        Contacts.deleteWhere {
            (Contacts.createdAt.less(limit)) and (Contacts.lastInboundAt.isNull() or Contacts.lastInboundAt.less(limit))
        }
    }

    /** RGPD : officer names read from the registry more than 3 years ago are deleted, like contacts. */
    suspend fun purgeStaleOfficers(now: Instant = Instant.now()): Int = dbQuery {
        val limit = now.minus(3 * 365L, ChronoUnit.DAYS)
        Officers.deleteWhere { Officers.fetchedAt.less(limit) }
    }

    // --- Faits, signaux, dossiers --------------------------------------------------------------

    suspend fun saveFacts(facts: List<Fact>) = dbQuery {
        facts.forEach { f ->
            Facts.insertIgnore {
                it[id] = f.id; it[accountId] = f.accountId; it[kind] = f.kind.name; it[statement] = f.statement
                it[sourceUrl] = f.sourceUrl; it[excerpt] = f.excerpt; it[retrievedAt] = Instant.parse(f.retrievedAt)
            }
        }
    }

    suspend fun factsOf(accountId: AccountId, maxAgeDays: Long = 120): List<Fact> = dbQuery {
        val since = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS)
        Facts.selectAll().where { (Facts.accountId eq accountId) and (Facts.retrievedAt greaterEq since) }
            .orderBy(Facts.retrievedAt, SortOrder.DESC)
            .map {
                Fact(it[Facts.id], it[Facts.accountId], FactKind.valueOf(it[Facts.kind]), it[Facts.statement], it[Facts.sourceUrl], it[Facts.retrievedAt].toString(), it[Facts.excerpt])
            }
    }

    suspend fun saveSignals(signals: List<Signal>) = dbQuery {
        signals.forEach { s ->
            Signals.insert {
                it[accountId] = s.accountId; it[type] = s.type.name; it[strength] = s.strength
                it[observedAt] = Instant.parse(s.observedAt); it[factIds] = s.factIds.encode()
            }
        }
    }

    suspend fun signalsOf(accountId: AccountId, maxAgeDays: Long = 180): List<Signal> = dbQuery {
        val since = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS)
        Signals.selectAll().where { (Signals.accountId eq accountId) and (Signals.observedAt greaterEq since) }.map {
            Signal(it[Signals.accountId], SignalType.valueOf(it[Signals.type]), it[Signals.strength], it[Signals.observedAt].toString(), it[Signals.factIds].decodeList())
        }
    }

    suspend fun saveDossier(d: Dossier) = dbQuery {
        Dossiers.upsert {
            it[accountId] = d.accountId; it[summary] = d.summary; it[hypotheses] = d.hypotheses.encode()
            it[angle] = d.angle; it[offer] = d.offer.name; it[updatedAt] = Instant.now()
        }
    }

    suspend fun dossier(accountId: AccountId): Dossier? = dbQuery {
        Dossiers.selectAll().where { Dossiers.accountId eq accountId }.firstOrNull()?.let {
            Dossier(accountId, it[Dossiers.summary], it[Dossiers.hypotheses].decodeList(), it[Dossiers.angle], Offer.valueOf(it[Dossiers.offer]))
        }
    }

    // --- Séquences et inscriptions -------------------------------------------------------------

    private val stepsSerializer = ListSerializer(SequenceStep.serializer())

    suspend fun ensureSequences(defaults: List<Sequence>) = dbQuery {
        defaults.forEach { s ->
            Sequences.insertIgnore { it[name] = s.name; it[offer] = s.offer.name; it[steps] = json.encodeToString(stepsSerializer, s.steps) }
        }
    }

    suspend fun sequences(): List<Sequence> = dbQuery {
        Sequences.selectAll().map { Sequence(it[Sequences.id], it[Sequences.name], Offer.valueOf(it[Sequences.offer]), json.decodeFromString(stepsSerializer, it[Sequences.steps])) }
    }

    suspend fun sequenceFor(offer: Offer): Sequence? = sequences().firstOrNull { it.offer == offer }

    suspend fun enroll(contactId: ContactId, sequenceId: Long): Long = dbQuery {
        Enrollments.insert { it[this.contactId] = contactId; it[this.sequenceId] = sequenceId; it[status] = EnrollmentStatus.ACTIVE.name } get Enrollments.id
    }

    private fun ResultRow.toEnrollment() = Enrollment(
        this[Enrollments.id], this[Enrollments.contactId], this[Enrollments.sequenceId], EnrollmentStatus.valueOf(this[Enrollments.status]),
        this[Enrollments.nextStep], this[Enrollments.lastActionAt]?.toString(), this[Enrollments.enrolledAt].toString(),
    )

    suspend fun enrollment(id: Long): Enrollment? = dbQuery { Enrollments.selectAll().where { Enrollments.id eq id }.firstOrNull()?.toEnrollment() }

    suspend fun activeEnrollments(): List<Enrollment> = dbQuery {
        Enrollments.selectAll().where { Enrollments.status eq EnrollmentStatus.ACTIVE.name }.map { it.toEnrollment() }
    }

    suspend fun enrollmentsOfContact(contactId: ContactId): List<Enrollment> = dbQuery {
        Enrollments.selectAll().where { Enrollments.contactId eq contactId }.map { it.toEnrollment() }
    }

    suspend fun advance(enrollmentId: Long, nextStep: Int, threadMessageId: String? = null) = dbQuery {
        Enrollments.update({ Enrollments.id eq enrollmentId }) {
            it[this.nextStep] = nextStep
            it[lastActionAt] = Instant.now()
            if (threadMessageId != null) it[this.threadMessageId] = threadMessageId
        }
    }

    suspend fun threadMessageId(enrollmentId: Long): String? = dbQuery {
        Enrollments.selectAll().where { Enrollments.id eq enrollmentId }.firstOrNull()?.get(Enrollments.threadMessageId)
    }

    suspend fun setEnrollmentStatus(enrollmentId: Long, status: EnrollmentStatus) = dbQuery {
        Enrollments.update({ Enrollments.id eq enrollmentId }) { it[this.status] = status.name }
    }

    // --- Brouillons et tâches ------------------------------------------------------------------

    suspend fun hasOpenDraft(enrollmentId: Long, stepIndex: Int): Boolean = dbQuery {
        Drafts.selectAll().where {
            (Drafts.enrollmentId eq enrollmentId) and (Drafts.stepIndex eq stepIndex) and
                (Drafts.status inList listOf(DraftStatus.PENDING_REVIEW.name, DraftStatus.APPROVED.name, DraftStatus.SENT.name, DraftStatus.TASK_DONE.name))
        }.any()
    }

    suspend fun createDraft(d: Draft): Long = dbQuery {
        Drafts.insert {
            it[enrollmentId] = d.enrollmentId; it[stepIndex] = d.stepIndex; it[channel] = d.channel.name; it[subject] = d.subject
            it[body] = d.body; it[citedFactIds] = d.citedFactIds.encode(); it[referenceUsed] = d.referenceUsed
            it[rationale] = d.rationale; it[status] = d.status.name; it[guardReport] = d.guardReport.encode()
        } get Drafts.id
    }

    private fun ResultRow.toDraft() = Draft(
        this[Drafts.id], this[Drafts.enrollmentId], this[Drafts.stepIndex], Channel.valueOf(this[Drafts.channel]), this[Drafts.subject],
        this[Drafts.body], this[Drafts.citedFactIds].decodeList(), this[Drafts.referenceUsed], this[Drafts.rationale],
        DraftStatus.valueOf(this[Drafts.status]), this[Drafts.guardReport].decodeList(),
    )

    suspend fun draft(id: Long): Draft? = dbQuery { Drafts.selectAll().where { Drafts.id eq id }.firstOrNull()?.toDraft() }

    suspend fun draftsByStatus(status: DraftStatus, channel: Channel? = null): List<Draft> = dbQuery {
        Drafts.selectAll().where {
            if (channel == null) Drafts.status eq status.name else (Drafts.status eq status.name) and (Drafts.channel eq channel.name)
        }.orderBy(Drafts.createdAt).map { it.toDraft() }
    }

    suspend fun decideDraft(id: Long, status: DraftStatus, editedSubject: String? = null, editedBody: String? = null) = dbQuery {
        Drafts.update({ Drafts.id eq id }) {
            it[this.status] = status.name
            it[decidedAt] = Instant.now()
            if (editedBody != null) { it[body] = editedBody; it[editedByHuman] = true }
            if (editedSubject != null) { it[subject] = editedSubject; it[editedByHuman] = true }
        }
    }

    suspend fun markSent(id: Long, messageId: String, mailbox: String) = dbQuery {
        Drafts.update({ Drafts.id eq id }) {
            it[status] = DraftStatus.SENT.name; it[sentAt] = Instant.now(); it[this.messageId] = messageId; it[this.mailbox] = mailbox
        }
    }

    suspend fun sentTodayBy(mailbox: String, since: Instant): Int = dbQuery {
        Drafts.selectAll().where { (Drafts.mailbox eq mailbox) and (Drafts.sentAt greaterEq since) }.count().toInt()
    }

    suspend fun lastSentAt(mailbox: String): Instant? = dbQuery {
        Drafts.selectAll().where { Drafts.mailbox eq mailbox }.orderBy(Drafts.sentAt, SortOrder.DESC_NULLS_LAST).limit(1)
            .firstOrNull()?.get(Drafts.sentAt)
    }

    suspend fun enrollmentByMessageId(messageId: String): Long? = dbQuery {
        Drafts.selectAll().where { Drafts.messageId eq messageId }.firstOrNull()?.get(Drafts.enrollmentId)
    }

    /** Exemples de messages corrigés à la main : servent d'exemples de style à l'agent rédacteur. */
    suspend fun humanEditedExamples(limit: Int = 5): List<Draft> = dbQuery {
        Drafts.selectAll().where { (Drafts.editedByHuman eq true) and (Drafts.status eq DraftStatus.SENT.name) }
            .orderBy(Drafts.sentAt, SortOrder.DESC_NULLS_LAST).limit(limit).map { it.toDraft() }
    }

    // --- Réponses et opposition ----------------------------------------------------------------

    suspend fun saveReply(
        enrollmentId: Long?, from: String, subject: String?, body: String, intent: ReplyIntent,
        confidence: Double, followUpOn: LocalDate?, summary: String, receivedAt: Instant,
    ): Long = dbQuery {
        Replies.insert {
            it[this.enrollmentId] = enrollmentId; it[fromAddress] = from; it[this.subject] = subject; it[this.body] = body
            it[this.intent] = intent.name; it[this.confidence] = confidence; it[this.followUpOn] = followUpOn
            it[this.summary] = summary; it[this.receivedAt] = receivedAt
        } get Replies.id
    }

    suspend fun openReplies(): List<ReplyRow> = dbQuery {
        Replies.selectAll().where { Replies.handled eq false }.orderBy(Replies.receivedAt, SortOrder.DESC).map {
            ReplyRow(it[Replies.id], it[Replies.enrollmentId], it[Replies.fromAddress], ReplyIntent.valueOf(it[Replies.intent]), it[Replies.summary], it[Replies.followUpOn]?.toString(), it[Replies.receivedAt].toString(), it[Replies.handled])
        }
    }

    suspend fun markReplyHandled(id: Long) = dbQuery { Replies.update({ Replies.id eq id }) { it[handled] = true } }

    suspend fun isSuppressed(email: String): Boolean = dbQuery {
        val e = email.lowercase()
        val domain = "@" + e.substringAfter('@')
        Suppressions.selectAll().where { Suppressions.value inList listOf(e, domain) }.any()
    }

    suspend fun suppress(value: String, reason: String) = dbQuery {
        Suppressions.insertIgnore { it[this.value] = value.lowercase(); it[this.reason] = reason }
    }

    // --- Pipeline ------------------------------------------------------------------------------

    suspend fun upsertDeal(d: Deal): Long = dbQuery {
        if (d.id == 0L) {
            Deals.insert {
                it[accountId] = d.accountId; it[offer] = d.offer.name; it[stage] = d.stage.name; it[amountEur] = d.amountEur
                it[sourceSignal] = d.sourceSignal?.name; it[expectedClose] = d.expectedClose?.let(LocalDate::parse)
            } get Deals.id
        } else {
            Deals.update({ Deals.id eq d.id }) {
                it[stage] = d.stage.name; it[amountEur] = d.amountEur; it[offer] = d.offer.name
                it[expectedClose] = d.expectedClose?.let(LocalDate::parse); it[updatedAt] = Instant.now()
            }
            d.id
        }
    }

    suspend fun deals(): List<Deal> = dbQuery {
        Deals.selectAll().map {
            Deal(it[Deals.id], it[Deals.accountId], Offer.valueOf(it[Deals.offer]), DealStage.valueOf(it[Deals.stage]), it[Deals.amountEur], it[Deals.sourceSignal]?.let(SignalType::valueOf), it[Deals.expectedClose]?.toString())
        }
    }

    // --- Journal et observabilité ---------------------------------------------------------------

    suspend fun logEvent(type: String, accountId: AccountId? = null, contactId: ContactId? = null, draftId: Long? = null, payload: String = "{}") = dbQuery {
        Events.insert { it[this.type] = type; it[this.accountId] = accountId; it[this.contactId] = contactId; it[this.draftId] = draftId; it[this.payload] = payload }
    }

    /** Payload and time of the most recent event of a type. */
    suspend fun lastEvent(type: String): Pair<String, Instant>? = dbQuery {
        Events.selectAll().where { Events.type eq type }.orderBy(Events.occurredAt, SortOrder.DESC).limit(1)
            .firstOrNull()?.let { it[Events.payload] to it[Events.occurredAt] }
    }

    suspend fun countEvents(type: String, since: Instant): Int = dbQuery {
        Events.selectAll().where { (Events.type eq type) and (Events.occurredAt greaterEq since) }.count().toInt()
    }

    suspend fun eventsSince(since: Instant): List<Triple<String, Long?, Instant>> = dbQuery {
        Events.selectAll().where { Events.occurredAt greaterEq since }.map { Triple(it[Events.type], it[Events.accountId], it[Events.occurredAt]) }
    }

    suspend fun logAgentRun(agent: String, accountId: AccountId?, model: String, inTok: Int, outTok: Int, durationMs: Long, success: Boolean, error: String?) = dbQuery {
        AgentRuns.insert {
            it[this.agent] = agent; it[this.accountId] = accountId; it[this.model] = model; it[inputTokens] = inTok; it[outputTokens] = outTok
            it[this.durationMs] = durationMs; it[this.success] = success; it[this.error] = error?.take(2000)
        }
    }

    data class AgentStats(val agent: String, val runs: Int, val failures: Int, val inputTokens: Long, val outputTokens: Long, val avgMs: Long)

    suspend fun agentStats(since: Instant): List<AgentStats> = dbQuery {
        AgentRuns.selectAll().where { AgentRuns.createdAt greaterEq since }.toList()
            .groupBy { it[AgentRuns.agent] }
            .map { (agent, rows) ->
                AgentStats(
                    agent, rows.size, rows.count { !it[AgentRuns.success] },
                    rows.sumOf { it[AgentRuns.inputTokens].toLong() }, rows.sumOf { it[AgentRuns.outputTokens].toLong() },
                    rows.map { it[AgentRuns.durationMs] }.average().toLong(),
                )
            }
    }
}
