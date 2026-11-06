package lab.doppio.bloom.data.persistence.fact

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.scoring.Scoring
import lab.doppio.bloom.data.persistence.dbQuery
import lab.doppio.bloom.data.persistence.decodeList
import lab.doppio.bloom.data.persistence.encode
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

class ExposedFactRepository(private val clock: Clock = Clock.systemDefaultZone()) : FactRepository {

    override suspend fun saveFacts(facts: List<Fact>) = dbQuery {
        facts.forEach { f ->
            FactsTable.insertIgnore {
                it[id] = f.id; it[accountId] = f.accountId; it[kind] = f.kind.name; it[statement] = f.statement
                it[sourceUrl] = f.sourceUrl; it[excerpt] = f.excerpt; it[retrievedAt] = Instant.parse(f.retrievedAt)
            }
        }
    }

    override suspend fun factsOf(accountId: AccountId, maxAgeDays: Long): List<Fact> = dbQuery {
        val since = clock.instant().minus(maxAgeDays, ChronoUnit.DAYS)
        FactsTable.selectAll().where { (FactsTable.accountId eq accountId) and (FactsTable.retrievedAt greaterEq since) }
            .orderBy(FactsTable.retrievedAt, SortOrder.DESC)
            .map {
                Fact(it[FactsTable.id], it[FactsTable.accountId], FactKind.valueOf(it[FactsTable.kind]), it[FactsTable.statement], it[FactsTable.sourceUrl], it[FactsTable.retrievedAt].toString(), it[FactsTable.excerpt])
            }
    }

    override suspend fun saveSignals(signals: List<Signal>) = dbQuery {
        signals.forEach { s ->
            SignalsTable.insert {
                it[accountId] = s.accountId; it[type] = s.type.name; it[strength] = s.strength
                it[observedAt] = Instant.parse(s.observedAt); it[factIds] = s.factIds.encode()
            }
        }
    }

    override suspend fun signalsOf(accountId: AccountId, maxAgeDays: Long): List<Signal> = dbQuery {
        val since = clock.instant().minus(maxAgeDays, ChronoUnit.DAYS)
        SignalsTable.selectAll().where { (SignalsTable.accountId eq accountId) and (SignalsTable.observedAt greaterEq since) }.map {
            Signal(it[SignalsTable.accountId], SignalType.valueOf(it[SignalsTable.type]), it[SignalsTable.strength], it[SignalsTable.observedAt].toString(), it[SignalsTable.factIds].decodeList())
        }
    }

    override suspend fun dominantSignal(accountId: AccountId): SignalType? = signalsOf(accountId)
        .maxByOrNull { Scoring.rules.getValue(it.type).weight * it.strength }?.type

    override suspend fun saveDossier(d: Dossier) {
        dbQuery {
            DossiersTable.upsert {
                it[accountId] = d.accountId; it[summary] = d.summary; it[hypotheses] = d.hypotheses.encode()
                it[angle] = d.angle; it[offer] = d.offer.name; it[noFit] = d.noFit; it[updatedAt] = clock.instant()
            }
        }
    }

    override suspend fun dossier(accountId: AccountId): Dossier? = dbQuery {
        DossiersTable.selectAll().where { DossiersTable.accountId eq accountId }.firstOrNull()?.let {
            Dossier(accountId, it[DossiersTable.summary], it[DossiersTable.hypotheses].decodeList(), it[DossiersTable.angle], Offer.valueOf(it[DossiersTable.offer]), it[DossiersTable.noFit])
        }
    }
}
