package lab.doppio.bloom.data

import lab.doppio.bloom.data.db.Db
import lab.doppio.bloom.data.db.DbConfig
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * One Postgres container for the whole test run, migrated by Flyway exactly like production.
 * Tests call [reset] to start from empty tables.
 */
object TestDatabase {
    private val container = PostgreSQLContainer("postgres:16-alpine").apply { start() }

    init {
        Db.connect(DbConfig(container.jdbcUrl, container.username, container.password, maxPool = 4))
    }

    fun reset() = transaction {
        val tables = TransactionManager.current().exec(
            "SELECT string_agg(quote_ident(tablename), ', ') FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
        ) { rs -> if (rs.next()) rs.getString(1) else null }
        if (tables != null) exec("TRUNCATE $tables RESTART IDENTITY CASCADE")
    }

    /** What a second instance would get: tries the advisory lock from another session. */
    fun otherSessionGetsInstanceLock(): Boolean =
        java.sql.DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { st -> st.executeQuery("SELECT pg_try_advisory_lock(${0x626c6f6f6dL})").use { it.next() && it.getBoolean(1) } }
        }
}
