package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.Dispatchers
import lab.doppio.bloom.core.port.Transactions
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** One Exposed transaction around the block: the repositories it calls join it instead of opening their own. */
class ExposedTransactions : Transactions {
    override suspend fun <T> atomically(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }
}
