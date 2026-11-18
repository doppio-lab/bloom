package lab.doppio.bloom.data.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

data class DbConfig(val url: String, val user: String, val password: String, val maxPool: Int = DEFAULT_MAX_POOL) {
    companion object {
        const val DEFAULT_MAX_POOL = 8
    }
}

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
        if (lockHeld()) return true
        val c = config ?: error("Db.connect first")
        val conn = java.sql.DriverManager.getConnection(c.url, c.user, c.password)
        val held = conn.createStatement().use { st -> st.executeQuery("SELECT pg_try_advisory_lock($SINGLE_INSTANCE_LOCK)").use { it.next() && it.getBoolean(1) } }
        if (held) lockConnection = conn else conn.close()
        return held
    }

    /**
     * Whether this process still holds the lock. The lock lives with its connection: when the server drops it
     * (restart, network cut) the lock is gone too, so the loops re-check this on every tick instead of trusting
     * the first answer. A dead connection is closed and forgotten so the next try reconnects.
     */
    @Synchronized
    fun lockHeld(): Boolean {
        val conn = lockConnection ?: return false
        if (runCatching { conn.isValid(2) }.getOrDefault(false)) return true
        runCatching { conn.close() }
        lockConnection = null
        return false
    }

    /** Gives the lock back (tests). Closing the connection releases the advisory lock. */
    @Synchronized
    fun releaseSingleInstanceLock() {
        runCatching { lockConnection?.close() }
        lockConnection = null
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

/** Les accès JDBC sont bloquants : on les isole sur Dispatchers.IO. */
internal suspend fun <T> dbQuery(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }
