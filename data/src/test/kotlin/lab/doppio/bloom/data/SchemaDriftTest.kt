package lab.doppio.bloom.data

import lab.doppio.bloom.data.db.AccountJobBoards
import lab.doppio.bloom.data.db.Accounts
import lab.doppio.bloom.data.db.AgentRuns
import lab.doppio.bloom.data.db.Blocklist
import lab.doppio.bloom.data.db.Contacts
import lab.doppio.bloom.data.db.Deals
import lab.doppio.bloom.data.db.Demands
import lab.doppio.bloom.data.db.DiscoverySeen
import lab.doppio.bloom.data.db.Dossiers
import lab.doppio.bloom.data.db.Drafts
import lab.doppio.bloom.data.db.Enrollments
import lab.doppio.bloom.data.db.Events
import lab.doppio.bloom.data.db.Facts
import lab.doppio.bloom.data.db.JobRuns
import lab.doppio.bloom.data.db.Officers
import lab.doppio.bloom.data.db.Replies
import lab.doppio.bloom.data.db.Sequences
import lab.doppio.bloom.data.db.Signals
import lab.doppio.bloom.data.db.Suppressions
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The schema is written by the Flyway migrations and read through the Exposed table objects: the two
 * must describe the same columns, types, nullability and defaults, or a query fails at runtime.
 */
class SchemaDriftTest {
    private val tables = arrayOf(
        Accounts, DiscoverySeen, Demands, JobRuns, Blocklist, Officers, AccountJobBoards, Contacts, Facts, Signals,
        Dossiers, Sequences, Enrollments, Drafts, Replies, Suppressions, Deals, AgentRuns, Events,
    )

    /**
     * The migrations store every timestamp as TIMESTAMPTZ DEFAULT now(), which the table objects read as
     * Instant through timestamp(). Exposed would create TIMESTAMP DEFAULT CURRENT_TIMESTAMP instead and
     * reports the difference; the JDBC driver maps both to the same instant, so that one is accepted.
     */
    private val acceptedTimestampDrift = Regex("""ALTER TABLE \w+ ALTER COLUMN \w+ TYPE TIMESTAMP, ALTER COLUMN \w+ SET DEFAULT CURRENT_TIMESTAMP""")

    @Test
    fun `les tables Exposed décrivent exactement le schéma des migrations`() {
        TestDatabase.reset()
        // The replacement lives in the exposed-migration artifact; not worth a dependency for one check.
        @Suppress("DEPRECATION")
        val statements = transaction { SchemaUtils.statementsRequiredToActualizeScheme(*tables, withLogs = false) }
        assertEquals(emptyList(), statements.filterNot { acceptedTimestampDrift.matches(it) })
    }
}
