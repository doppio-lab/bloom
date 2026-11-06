package lab.doppio.bloom.data.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lab.doppio.bloom.core.port.InstanceLock
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

data class DbConfig(val url: String, val user: String, val password: String, val maxPool: Int = 8)

object Db {
    private var config: DbConfig? = null
    private var lockConnection: java.sql.Connection? = null
    private var dataSource: HikariDataSource? = null

    /**
     * Only one instance may run the background loops, or emails would leave twice. Holds a Postgres
     * advisory lock on a connection of its own for the life of the process. False while another holds it.
     */
    @Synchronized
    fun tryHoldSingleInstanceLock(): Boolean {
        if (lockConnection?.isValid(2) == true) return true
        val c = config ?: error("Db.connect first")
        val conn = java.sql.DriverManager.getConnection(c.url, c.user, c.password)
        val held = conn.createStatement().use { st -> st.executeQuery("SELECT pg_try_advisory_lock($SINGLE_INSTANCE_LOCK)").use { it.next() && it.getBoolean(1) } }
        if (held) lockConnection = conn else conn.close()
        return held
    }

    private const val SINGLE_INSTANCE_LOCK = 0x626c6f6f6dL // "bloom"

    /** Opens the pool, runs the Flyway migrations and registers the default Exposed database. */
    fun connect(config: DbConfig) {
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
        dataSource = ds
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        Database.connect(ds)
    }

    /** Releases the single-instance lock and the pool, so a replacement instance can take over at once. */
    @Synchronized
    fun close() {
        runCatching { lockConnection?.close() }
        lockConnection = null
        dataSource?.close()
        dataSource = null
    }
}

/** [InstanceLock] backed by the Postgres advisory lock held by [Db]. */
class PostgresInstanceLock : InstanceLock {
    override fun tryHold(): Boolean = Db.tryHoldSingleInstanceLock()
}

/** Les accès JDBC sont bloquants : on les isole sur Dispatchers.IO. */
internal suspend fun <T> dbQuery(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }
