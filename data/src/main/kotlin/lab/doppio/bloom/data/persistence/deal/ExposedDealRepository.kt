package lab.doppio.bloom.data.persistence.deal

import java.time.Clock
import java.time.LocalDate
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.data.persistence.dbQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ExposedDealRepository(private val clock: Clock = Clock.systemDefaultZone()) : DealRepository {

    override suspend fun upsertDeal(d: Deal): Long = dbQuery {
        if (d.id == 0L) {
            DealsTable.insert {
                it[accountId] = d.accountId; it[offer] = d.offer.name; it[stage] = d.stage.name; it[amountEur] = d.amountEur
                it[sourceSignal] = d.sourceSignal?.name; it[expectedClose] = d.expectedClose?.let(LocalDate::parse)
            } get DealsTable.id
        } else {
            DealsTable.update({ DealsTable.id eq d.id }) {
                it[stage] = d.stage.name; it[amountEur] = d.amountEur; it[offer] = d.offer.name
                it[expectedClose] = d.expectedClose?.let(LocalDate::parse); it[updatedAt] = clock.instant()
            }
            d.id
        }
    }

    override suspend fun deals(): List<Deal> = dbQuery {
        DealsTable.selectAll().map {
            Deal(it[DealsTable.id], it[DealsTable.accountId], Offer.valueOf(it[DealsTable.offer]), DealStage.valueOf(it[DealsTable.stage]), it[DealsTable.amountEur], it[DealsTable.sourceSignal]?.let(SignalType::valueOf), it[DealsTable.expectedClose]?.toString())
        }
    }
}
