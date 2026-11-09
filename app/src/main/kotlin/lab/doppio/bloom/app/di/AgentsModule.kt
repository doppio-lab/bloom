package lab.doppio.bloom.app.di

import lab.doppio.bloom.agents.Llm
import lab.doppio.bloom.agents.ReplyClassifierAgent
import lab.doppio.bloom.agents.ResearchAgent
import lab.doppio.bloom.agents.RunTracker
import lab.doppio.bloom.agents.WeeklyCoachAgent
import lab.doppio.bloom.agents.WriterAgent
import lab.doppio.bloom.app.config.BloomConfig
import lab.doppio.bloom.core.port.Coach
import lab.doppio.bloom.core.port.Drafter
import lab.doppio.bloom.core.port.JobOfferFacts
import lab.doppio.bloom.core.port.ReplyClassifier
import lab.doppio.bloom.core.port.Researcher
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** The LLM, the run tracker and the Koog agents, bound to their role ports. */
val agentsModule: Module = module {
    single { Llm.from(get<BloomConfig>().llm.client) }
    single { RunTracker(get(), dailyTokenBudget = get<BloomConfig>().llm.dailyTokenBudget, clock = get()) }
    single {
        ResearchAgent(
            get(), get(), get(), get(), get(),
            appStore = get(), registry = get(), jobs = getOrNull<JobOfferFacts>(), web = get(), clock = get(),
        )
    } bind Researcher::class
    single { WriterAgent(get(), get(), get(), get(), get(), get(), get(), senderName = get<BloomConfig>().sending.senderName) } bind Drafter::class
    singleOf(::WeeklyCoachAgent) { bind<Coach>() }
    singleOf(::ReplyClassifierAgent) { bind<ReplyClassifier>() }
}
