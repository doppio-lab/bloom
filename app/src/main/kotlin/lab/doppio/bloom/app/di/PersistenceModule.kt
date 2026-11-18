package lab.doppio.bloom.app.di

import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.InstanceLock
import lab.doppio.bloom.core.port.JobRunRepository
import lab.doppio.bloom.core.port.OfficerRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.Transactions
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.data.persistence.PostgresInstanceLock
import lab.doppio.bloom.data.persistence.ExposedTransactions
import lab.doppio.bloom.data.persistence.account.ExposedAccountRepository
import lab.doppio.bloom.data.persistence.agentrun.ExposedAgentRunRepository
import lab.doppio.bloom.data.persistence.contact.ExposedContactRepository
import lab.doppio.bloom.data.persistence.deal.ExposedDealRepository
import lab.doppio.bloom.data.persistence.discovery.ExposedDiscoveryStateRepository
import lab.doppio.bloom.data.persistence.draft.ExposedDraftRepository
import lab.doppio.bloom.data.persistence.event.ExposedEventRepository
import lab.doppio.bloom.data.persistence.fact.ExposedFactRepository
import lab.doppio.bloom.data.persistence.job.ExposedJobRunRepository
import lab.doppio.bloom.data.persistence.officer.ExposedOfficerRepository
import lab.doppio.bloom.data.persistence.reply.ExposedReplyRepository
import lab.doppio.bloom.data.persistence.sequence.ExposedSequenceRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Exposed repositories and the instance lock, each bound to its core port. */
val persistenceModule: Module = module {
    singleOf(::ExposedAccountRepository) { bind<AccountRepository>() }
    singleOf(::ExposedOfficerRepository) { bind<OfficerRepository>() }
    singleOf(::ExposedContactRepository) { bind<ContactRepository>() }
    singleOf(::ExposedFactRepository) { bind<FactRepository>() }
    singleOf(::ExposedSequenceRepository) { bind<SequenceRepository>() }
    singleOf(::ExposedDraftRepository) { bind<DraftRepository>() }
    singleOf(::ExposedReplyRepository) { bind<ReplyRepository>() }
    singleOf(::ExposedDealRepository) { bind<DealRepository>() }
    singleOf(::ExposedJobRunRepository) { bind<JobRunRepository>() }
    singleOf(::ExposedEventRepository) { bind<EventRepository>() }
    singleOf(::ExposedAgentRunRepository) { bind<AgentRunRepository>() }
    singleOf(::ExposedDiscoveryStateRepository) { bind<DiscoveryStateRepository>() }
    singleOf(::PostgresInstanceLock) { bind<InstanceLock>() }
    singleOf(::ExposedTransactions) { bind<Transactions>() }
}
