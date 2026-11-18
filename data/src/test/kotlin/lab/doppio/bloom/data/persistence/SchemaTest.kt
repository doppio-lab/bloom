package lab.doppio.bloom.data.persistence

import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.account.AccountsTable
import lab.doppio.bloom.data.persistence.account.BlocklistTable
import lab.doppio.bloom.data.persistence.agentrun.AgentRunsTable
import lab.doppio.bloom.data.persistence.contact.ContactsTable
import lab.doppio.bloom.data.persistence.deal.DealsTable
import lab.doppio.bloom.data.persistence.discovery.AccountJobBoardsTable
import lab.doppio.bloom.data.persistence.discovery.DemandsTable
import lab.doppio.bloom.data.persistence.discovery.DiscoverySeenTable
import lab.doppio.bloom.data.persistence.draft.DraftsTable
import lab.doppio.bloom.data.persistence.event.EventsTable
import lab.doppio.bloom.data.persistence.fact.DossiersTable
import lab.doppio.bloom.data.persistence.fact.FactsTable
import lab.doppio.bloom.data.persistence.fact.SignalsTable
import lab.doppio.bloom.data.persistence.job.JobRunsTable
import lab.doppio.bloom.data.persistence.officer.OfficersTable
import lab.doppio.bloom.data.persistence.reply.RepliesTable
import lab.doppio.bloom.data.persistence.reply.SuppressionsTable
import lab.doppio.bloom.data.persistence.sequence.EnrollmentsTable
import lab.doppio.bloom.data.persistence.sequence.SequencesTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTest {
    private val tables: List<Table> = listOf(
        AccountsTable, BlocklistTable, OfficersTable, ContactsTable, FactsTable, SignalsTable, DossiersTable,
        SequencesTable, EnrollmentsTable, DraftsTable, RepliesTable, SuppressionsTable, DealsTable, JobRunsTable,
        EventsTable, AgentRunsTable, DiscoverySeenTable, DemandsTable, AccountJobBoardsTable,
    )

    /**
     * The migrations store every timestamp as TIMESTAMPTZ DEFAULT now(), which the table objects read as
     * Instant through timestamp(). Exposed would create TIMESTAMP DEFAULT CURRENT_TIMESTAMP instead and
     * reports the difference; the JDBC driver maps both to the same instant, so that one is accepted.
     * Anything else (a column, a type, a nullability or a default that differs) is drift.
     */
    private val acceptedTimestampDrift = Regex("""ALTER TABLE \w+ ALTER COLUMN \w+ TYPE TIMESTAMP, ALTER COLUMN \w+ SET DEFAULT CURRENT_TIMESTAMP""")

    @Test
    @Suppress("DEPRECATION")
    fun `les tables Exposed décrivent exactement le schéma des migrations`() {
        TestDatabase.reset()
        val statements = transaction { SchemaUtils.statementsRequiredToActualizeScheme(*tables.toTypedArray()) }
        assertEquals(emptyList(), statements.filterNot { acceptedTimestampDrift.matches(it) }, "an Exposed table differs from what the Flyway migrations create")
    }
}
