package lab.doppio.bloom.data.persistence

import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** V10 on a database at schema version 9 holding the duplicates the old write rules left behind. */
class CleanupMigrationTest {
    private val container = PostgreSQLContainer("postgres:16-alpine")

    private fun flyway(target: String?) = Flyway.configure()
        .dataSource(container.jdbcUrl, container.username, container.password)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(target) }
        .load()

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private val rating = "Acme : note moyenne App Store"
    private fun fact(conn: Connection, id: String, account: Int, kind: String, statement: String, url: String, at: String) =
        conn.run("INSERT INTO facts (id, account_id, kind, statement, source_url, retrieved_at) VALUES ('$id', $account, '$kind', '${statement.replace("'", "''")}', '$url', '$at')")

    private fun signal(conn: Connection, account: Int, type: String, at: String, ids: String = "[]") =
        conn.run("INSERT INTO signals (account_id, type, strength, observed_at, fact_ids) VALUES ($account, '$type', 0.7, '$at', '$ids')")

    @Test
    fun `removes superseded state signals and unreferenced older readings, keeps everything else, and runs twice safely`() {
        container.start()
        try {
            flyway("9").migrate()
            val migration = checkNotNull(javaClass.getResource("/db/migration/V10__cleanup_repeated_readings.sql")).readText()
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                c.autoCommit = true
                c.run("INSERT INTO accounts (id, name, slug) VALUES (1, 'Acme', 'acme'), (2, 'Beta', 'beta')")
                c.run("INSERT INTO contacts (id, account_id, full_name, role, persona, data_source) VALUES (1, 1, 'A B', 'CTO', 'CTO', 'x')")
                c.run("INSERT INTO sequences (id, name, offer, steps) VALUES (1, 's', 'AUDIT', '[]')")
                c.run("INSERT INTO enrollments (id, contact_id, sequence_id, status) VALUES (1, 1, 1, 'ACTIVE')")

                // Signals. Three nightly readings of a state signal, the last one made of two rows (two job offers)
                signal(c, 1, "HIRING_MOBILE", "2026-11-01T02:00:00Z")
                signal(c, 1, "HIRING_MOBILE", "2026-11-01T02:00:00Z")
                signal(c, 1, "HIRING_MOBILE", "2026-11-02T02:00:00Z")
                signal(c, 1, "HIRING_MOBILE", "2026-11-09T02:00:00Z", "[\"fJob1\"]")
                signal(c, 1, "HIRING_MOBILE", "2026-11-09T02:03:00Z", "[\"fJob2\"]")
                // Another account is its own key
                signal(c, 2, "HIRING_MOBILE", "2026-11-05T02:00:00Z")
                // Event signals accumulate and are never touched
                signal(c, 1, "PUBLIC_TENDER", "2026-10-01T02:00:00Z")
                signal(c, 1, "PUBLIC_TENDER", "2026-11-01T02:00:00Z")

                // Facts. A rating restated nightly: three readings, the middle one cited by a draft, the oldest by nothing
                val url = "https://apps.apple.com/fr/app/acme/id1"
                fact(c, "r1", 1, "APP_METADATA", "$rating 4,1 sur 1200 notes (fr)", url, "2026-10-01T02:00:00Z")
                fact(c, "r2", 1, "APP_METADATA", "$rating 4,2 sur 1210 notes (fr)", url, "2026-10-15T02:00:00Z")
                fact(c, "r3", 1, "APP_METADATA", "$rating 4,3 sur 1234 notes (fr)", url, "2026-11-09T02:00:00Z")
                // Cited only in a dossier hypothesis marker
                fact(c, "h1", 1, "JOB_POSTING", "Offre iOS : publiée il y a 10 jours", "https://jobs/1", "2026-10-20T02:00:00Z")
                fact(c, "h2", 1, "JOB_POSTING", "Offre iOS : publiée il y a 12 jours", "https://jobs/1", "2026-10-22T02:00:00Z")
                // Cited only by a remaining signal
                fact(c, "s1", 1, "JOB_POSTING", "Offre Android : publiée il y a 3 jours", "https://jobs/2", "2026-10-20T02:00:00Z")
                fact(c, "s2", 1, "JOB_POSTING", "Offre Android : publiée il y a 5 jours", "https://jobs/2", "2026-10-22T02:00:00Z")
                // Same page read together: seconds apart, different numbers, two distinct facts. Never merged
                fact(c, "t1", 1, "PRESS", "Levée de 2 M€ en 2023", "https://press/a", "2026-11-09T02:00:00Z")
                fact(c, "t2", 1, "PRESS", "Levée de 5 M€ en 2025", "https://press/a", "2026-11-09T02:00:03Z")
                // Different source URL, different kind, different account with the same statement: other topics
                fact(c, "u1", 1, "APP_METADATA", "$rating 4,0 sur 100 notes (fr)", "https://play/acme", "2026-10-01T02:00:00Z")
                fact(c, "k1", 1, "APP_REVIEW", "$rating 4,1 sur 1200 notes (fr)", url, "2026-10-01T02:00:00Z")
                fact(c, "a1", 2, "APP_METADATA", "$rating 4,1 sur 1200 notes (fr)", url, "2026-10-01T02:00:00Z")
                // A fact with no digit is its own topic
                fact(c, "n1", 1, "WEB_PAGE", "Le site propose une démo", "https://acme.fr", "2026-10-01T02:00:00Z")

                c.run("INSERT INTO drafts (enrollment_id, step_index, channel, body, rationale, status, cited_fact_ids) VALUES (1, 0, 'EMAIL', 'Bonjour', 'why', 'REJECTED', '[\"r2\"]')")
                c.run("INSERT INTO dossiers (account_id, summary, hypotheses, angle, offer) VALUES (1, 'sum', '[\"Recrute en iOS [F:h1]\"]', 'angle', 'AUDIT')")
                // An event signal citing s1: it stays, so s1 stays
                signal(c, 1, "PUBLIC_CONTRACT", "2026-11-01T02:00:00Z", "[\"s1\"]")

                c.run(migration)
                c.run(migration)

                assertEquals(
                    listOf("2026-11-09T02:00:00Z", "2026-11-09T02:03:00Z"),
                    c.strings("SELECT to_char(observed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM signals WHERE account_id = 1 AND type = 'HIRING_MOBILE' ORDER BY observed_at"),
                    "the latest reading of the state signal survives whole, older nights go",
                )
                assertEquals(listOf("1"), c.strings("SELECT count(*)::text FROM signals WHERE account_id = 2 AND type = 'HIRING_MOBILE'"))
                assertEquals(listOf("2"), c.strings("SELECT count(*)::text FROM signals WHERE type = 'PUBLIC_TENDER'"))

                assertEquals(
                    listOf("a1", "h1", "h2", "k1", "n1", "r2", "r3", "s1", "s2", "t1", "t2", "u1"),
                    c.strings("SELECT id FROM facts ORDER BY id"),
                    "only r1 is superseded and unreferenced; h1 is cited by a dossier, r2 by a draft, s1 by a signal",
                )
            }
            // A third run on an empty database, as on a fresh install
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                c.autoCommit = true
                c.run("TRUNCATE accounts CASCADE")
                c.run(javaClass.getResource("/db/migration/V10__cleanup_repeated_readings.sql")!!.readText())
                assertEquals(listOf("0"), c.strings("SELECT count(*)::text FROM facts"))
            }
            flyway(null).migrate()
        } finally {
            container.stop()
        }
    }
}
