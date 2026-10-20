package lab.doppio.bloom.app.di

import lab.doppio.bloom.app.config.BloomConfig
import lab.doppio.bloom.app.outreach.MailConfig
import lab.doppio.bloom.app.outreach.Scheduler
import lab.doppio.bloom.core.usecase.AddContact
import lab.doppio.bloom.core.usecase.BookMeeting
import lab.doppio.bloom.core.usecase.CompleteContactEmail
import lab.doppio.bloom.core.usecase.CompleteTask
import lab.doppio.bloom.core.usecase.ContactBook
import lab.doppio.bloom.core.usecase.DecideDraft
import lab.doppio.bloom.core.usecase.EnrollContact
import lab.doppio.bloom.core.usecase.GetBoard
import lab.doppio.bloom.core.usecase.GetOverview
import lab.doppio.bloom.core.usecase.ImportAccounts
import lab.doppio.bloom.core.usecase.ListToComplete
import lab.doppio.bloom.core.usecase.PlanDrafts
import lab.doppio.bloom.core.usecase.PostponeContactSequences
import lab.doppio.bloom.core.usecase.ProcessReplies
import lab.doppio.bloom.core.usecase.ResearchBatch
import lab.doppio.bloom.core.usecase.ReviewQueue
import lab.doppio.bloom.core.usecase.RunDiscovery
import lab.doppio.bloom.core.usecase.RunPeriodicJobs
import lab.doppio.bloom.core.usecase.SendNextApproved
import lab.doppio.bloom.core.usecase.StopContactSequences
import lab.doppio.bloom.core.usecase.UnsubscribeContact
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** What the application does. Free of Ktor and of the database, they only see ports. */
val useCasesModule: Module = module {
    singleOf(::StopContactSequences)
    singleOf(::PostponeContactSequences)
    singleOf(::UnsubscribeContact)
    singleOf(::PlanDrafts)
    single { SendNextApproved(get(), get(), get(), get(), get(), get(), get<MailConfig>().mailboxes, get(), get()) }
    singleOf(::ProcessReplies)
    single {
        val research = get<BloomConfig>().research
        ResearchBatch(get(), get(), get(), get(), budget = research.batchSize, staleAfterDays = research.staleDays)
    }
    singleOf(::RunDiscovery)
    singleOf(::RunPeriodicJobs)
    // A new enrollment wakes the planner. Resolved on use: the scheduler is declared in its own module.
    single { EnrollContact(get(), get(), get(), get(), get(), afterEnrolled = { get<Scheduler>().planSoon() }) }
    singleOf(::AddContact)
    singleOf(::DecideDraft)
    singleOf(::CompleteTask)
    singleOf(::ReviewQueue)
    single {
        val mail = get<MailConfig>()
        GetOverview(get(), get(), get(), get(), get(), get(), get(), get(), get(), mail.dryRun, mail.mailboxes.size)
    }
    singleOf(::ContactBook)
    singleOf(::ListToComplete)
    singleOf(::ImportAccounts)
    singleOf(::GetBoard)
    singleOf(::BookMeeting)
    singleOf(::CompleteContactEmail)
}
