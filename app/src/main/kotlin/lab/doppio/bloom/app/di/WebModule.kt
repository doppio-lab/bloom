package lab.doppio.bloom.app.di

import lab.doppio.bloom.app.web.ApiRoutes
import lab.doppio.bloom.app.web.DiscoveryRoutes
import lab.doppio.bloom.app.web.UnsubscribeRoutes
import lab.doppio.bloom.core.usecase.FindContactEmail
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** HTTP routes. Each group declares what it needs in its constructor, so a missing binding fails at startup. */
val webModule: Module = module {
    single {
        ApiRoutes(
            overview = get(), accountRepo = get(), contactRepo = get(), factRepo = get(), enrollContact = get(), createAccount = get(),
            reviewQueue = get(), decideDraft = get(), completeTask = get(),
            getBoard = get(), bookMeeting = get(), completeContactEmail = get(),
            replyRepo = get(), dealRepo = get(),
            eventRepo = get(), research = get(), background = get(BACKGROUND),
        )
    }
    single {
        DiscoveryRoutes(
            runDiscovery = get(), leader = get(), researchBatch = get(), eventRepo = get(), toComplete = get(), addContact = get(),
            accountRepo = get(), discoveryState = get(), contactBook = get(), importAccounts = get(),
            // Declared only when a Dropcontact key is configured
            findContactEmail = getOrNull<FindContactEmail>(),
            background = get(BACKGROUND),
        )
    }
    singleOf(::UnsubscribeRoutes)
}
