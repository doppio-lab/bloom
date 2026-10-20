package lab.doppio.bloom.data.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

data class DbConfig(val url: String, val user: String, val password: String, val maxPool: Int = 8)

object Db {
    private var config: DbConfig? = null
    private var lockConnection: java.sql.Connection? = null

    /**
     * Only one instance may run the background loops, or emails would leave twice. Holds a Postgres
     * advisory lock on a connection of its own for the life of the process. False while another holds it.
     */
    @Synchronized
    fun tryHoldSingleInstanceLock(): Boolean {
        if (lockHeld()) return true
        val c = config ?: error("Db.connect first")
        val conn = java.sql.DriverManager.getConnection(c.url, c.user, c.password)
        val held = conn.createStatement().use { st -> st.executeQuery("SELECT pg_try_advisory_lock($SINGLE_INSTANCE_LOCK)").use { it.next() && it.getBoolean(1) } }
        if (held) lockConnection = conn else conn.close()
        return held
    }

    /**
     * Whether this process still holds the lock. The lock lives with its connection: when the server drops
     * it (restart, network cut) the lock is gone too, so the loops must re-check this on every tick instead
     * of trusting the first answer. A dead connection is closed and forgotten so the next try reconnects.
     */
    @Synchronized
    fun lockHeld(): Boolean {
        val conn = lockConnection ?: return false
        if (runCatching { conn.isValid(2) }.getOrDefault(false)) return true
        runCatching { conn.close() }
        lockConnection = null
        return false
    }

    /** Gives the lock back (shutdown, tests). Closing the connection releases the advisory lock. */
    @Synchronized
    fun releaseSingleInstanceLock() {
        runCatching { lockConnection?.close() }
        lockConnection = null
    }

    private const val SINGLE_INSTANCE_LOCK = 0x626c6f6f6dL // "bloom"

    fun connect(config: DbConfig): Database {
        this.config = config
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPool
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            },
        )
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        return Database.connect(ds)
    }
}

/** Les accès JDBC sont bloquants : on les isole sur Dispatchers.IO. */
suspend fun <T> dbQuery(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }

object Accounts : Table("accounts") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val domain = text("domain").nullable()
    val slug = text("slug")
    // Named origin: Table already exposes a `source` member (ColumnSet.source)
    val origin = text("source").default("manual")
    val excludedReason = text("excluded_reason").nullable()
    val excludedAt = timestamp("excluded_at").nullable()
    val officersCheckedAt = timestamp("officers_checked_at").nullable()
    val siren = text("siren").nullable()
    val sector = text("sector").default("OTHER")
    val market = text("market").default("FR")
    val headcount = integer("headcount").nullable()
    val iosAppId = long("ios_app_id").nullable()
    val androidPackage = text("android_package").nullable()
    val warmPath = text("warm_path").nullable()
    val ownerNote = text("owner_note").nullable()
    val score = integer("score").default(0)
    val scoreDetail = text("score_detail").default("{}")
    val recommendedOffer = text("recommended_offer").nullable()
    val refreshedAt = timestamp("refreshed_at").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object DiscoverySeen : Table("discovery_seen") {
    val key = text("key")
    val kept = bool("kept")
    val reason = text("reason").nullable()
    val checkedAt = timestamp("checked_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(key)
}

object Demands : Table("demands") {
    val key = text("key")
    // Named origin: Table already exposes a `source` member (ColumnSet.source)
    val origin = text("source")
    val title = text("title")
    val url = text("url")
    val budget = text("budget").nullable()
    val categories = text("categories").default("[]")
    val publishedAt = timestamp("published_at").nullable()
    val dismissedAt = timestamp("dismissed_at").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(key)
}

object JobRuns : Table("job_runs") {
    val name = text("name")
    val period = date("period")
    val startedAt = timestamp("started_at").defaultExpression(CurrentTimestamp)
    val finishedAt = timestamp("finished_at").nullable()
    val error = text("error").nullable()
    override val primaryKey = PrimaryKey(name)
}

object Blocklist : Table("blocklist") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val slug = text("slug")
    val reason = text("reason").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Officers : Table("officers") {
    val accountId = long("account_id")
    val fullName = text("full_name")
    val role = text("role")
    val sourceUrl = text("source_url")
    val fetchedAt = timestamp("fetched_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(accountId, fullName, role)
}

object AccountJobBoards : Table("job_boards") {
    val accountId = long("account_id")
    val provider = text("provider").nullable()
    val token = text("token").nullable()
    val checkedAt = timestamp("checked_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(accountId)
}

object Contacts : Table("contacts") {
    val id = long("id").autoIncrement()
    val accountId = long("account_id")
    val fullName = text("full_name")
    val role = text("role")
    val persona = text("persona")
    val email = text("email").nullable()
    val linkedinUrl = text("linkedin_url").nullable()
    val dataSource = text("data_source")
    val language = text("language").default("fr")
    val lastInboundAt = timestamp("last_inbound_at").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Facts : Table("facts") {
    val id = text("id")
    val accountId = long("account_id")
    val kind = text("kind")
    val statement = text("statement")
    val sourceUrl = text("source_url")
    val excerpt = text("excerpt").nullable()
    val retrievedAt = timestamp("retrieved_at")
    override val primaryKey = PrimaryKey(id)
}

object Signals : Table("signals") {
    val id = long("id").autoIncrement()
    val accountId = long("account_id")
    val type = text("type")
    val strength = double("strength")
    val observedAt = timestamp("observed_at")
    val factIds = text("fact_ids").default("[]")
    override val primaryKey = PrimaryKey(id)
}

object Dossiers : Table("dossiers") {
    val accountId = long("account_id")
    val summary = text("summary")
    val hypotheses = text("hypotheses").default("[]")
    val angle = text("angle")
    val offer = text("offer")
    val noFit = bool("no_fit").default(false)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(accountId)
}

object Sequences : Table("sequences") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val offer = text("offer")
    val steps = text("steps")
    override val primaryKey = PrimaryKey(id)
}

object Enrollments : Table("enrollments") {
    val id = long("id").autoIncrement()
    val contactId = long("contact_id")
    val sequenceId = long("sequence_id")
    val status = text("status")
    val nextStep = integer("next_step").default(0)
    val lastActionAt = timestamp("last_action_at").nullable()
    val enrolledAt = timestamp("enrolled_at").defaultExpression(CurrentTimestamp)
    val threadMessageId = text("thread_message_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Drafts : Table("drafts") {
    val id = long("id").autoIncrement()
    val enrollmentId = long("enrollment_id")
    val stepIndex = integer("step_index")
    val channel = text("channel")
    val subject = text("subject").nullable()
    val body = text("body")
    val citedFactIds = text("cited_fact_ids").default("[]")
    val referenceUsed = text("reference_used").nullable()
    val rationale = text("rationale")
    val status = text("status")
    val guardReport = text("guard_report").default("[]")
    val editedByHuman = bool("edited_by_human").default(false)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val decidedAt = timestamp("decided_at").nullable()
    val sentAt = timestamp("sent_at").nullable()
    val messageId = text("message_id").nullable()
    val mailbox = text("mailbox").nullable()
    val sendAttempts = integer("send_attempts").default(0)
    val nextAttemptAt = timestamp("next_attempt_at").nullable()
    val lastError = text("last_error").nullable()
    val sendAfter = timestamp("send_after").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Replies : Table("replies") {
    val id = long("id").autoIncrement()
    val enrollmentId = long("enrollment_id").nullable()
    val fromAddress = text("from_address")
    val subject = text("subject").nullable()
    val body = text("body")
    val intent = text("intent")
    val confidence = double("confidence")
    val followUpOn = date("follow_up_on").nullable()
    val summary = text("summary")
    val receivedAt = timestamp("received_at")
    val handled = bool("handled").default(false)
    val inboundKey = text("inbound_key").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Suppressions : Table("suppressions") {
    val value = text("value")
    val reason = text("reason")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(value)
}

object Deals : Table("deals") {
    val id = long("id").autoIncrement()
    val accountId = long("account_id")
    val offer = text("offer")
    val stage = text("stage")
    val amountEur = long("amount_eur").default(0)
    val sourceSignal = text("source_signal").nullable()
    val expectedClose = date("expected_close").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object AgentRuns : Table("agent_runs") {
    val id = long("id").autoIncrement()
    val agent = text("agent")
    val accountId = long("account_id").nullable()
    val model = text("model")
    val inputTokens = integer("input_tokens").default(0)
    val outputTokens = integer("output_tokens").default(0)
    val durationMs = long("duration_ms")
    val success = bool("success")
    val error = text("error").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Events : Table("events") {
    val id = long("id").autoIncrement()
    val type = text("type")
    val accountId = long("account_id").nullable()
    val contactId = long("contact_id").nullable()
    val draftId = long("draft_id").nullable()
    val payload = text("payload").default("{}")
    val occurredAt = timestamp("occurred_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}
