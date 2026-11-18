package lab.doppio.bloom.core.port

/** Several repository calls that must be saved together or not at all. */
interface Transactions {
    /** Runs [block] in one transaction: when it throws, nothing it wrote is kept. */
    suspend fun <T> atomically(block: suspend () -> T): T
}
