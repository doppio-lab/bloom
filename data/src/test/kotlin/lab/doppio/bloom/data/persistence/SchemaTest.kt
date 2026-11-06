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
     * Exposed proposes ALTER COLUMN statements for every timestamptz column (it expects TIMESTAMP), for
     * defaults written as now() and for a few NOT NULL defaults. Those are comparison noise, not drift.
     * What matters is a table, column or index declared in Kotlin that the Flyway migrations do not create.
     */
    @Test
    @Suppress("DEPRECATION")
    fun `chaque table et colonne Exposed existe dans le schéma créé par Flyway`() {
        TestDatabase.reset()
        val statements = transaction { SchemaUtils.statementsRequiredToActualizeScheme(*tables.toTypedArray()) }
        val drift = statements.filterNot { it.contains(" ALTER COLUMN ") }
        assertEquals(emptyList(), drift, "an Exposed table declares something the Flyway migrations do not create")
    }
}
