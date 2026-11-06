package lab.doppio.bloom.app.di

import lab.doppio.bloom.agents.Llm
import lab.doppio.bloom.app.config.BloomConfig
import lab.doppio.bloom.app.outreach.Scheduler
import lab.doppio.bloom.app.testConfig
import lab.doppio.bloom.app.web.ApiRoutes
import lab.doppio.bloom.app.web.DiscoveryRoutes
import lab.doppio.bloom.app.web.UnsubscribeRoutes
import lab.doppio.bloom.app.config.DiscoverySettings
import lab.doppio.bloom.app.config.LlmSettings
import lab.doppio.bloom.app.config.SendingConfig
import lab.doppio.bloom.app.config.ServerConfig
import lab.doppio.bloom.core.model.Mailbox
import lab.doppio.bloom.data.persistence.DbConfig
import lab.doppio.bloom.core.port.EmailFinder
import lab.doppio.bloom.core.port.JobOfferFacts
import lab.doppio.bloom.core.usecase.FindContactEmail
import lab.doppio.bloom.data.discovery.DiscoveryConfig
import lab.doppio.bloom.data.sources.JobBoardSource
import lab.doppio.bloom.data.sources.CodeurSource
import lab.doppio.bloom.data.sources.FranceTravailSource
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graph is checked twice: `verify()` reads the constructors of every module and fails on a missing
 * binding, and starting the real modules resolves every definition, so a lambda that asks for something
 * nobody declared fails here too, not at the first request.
 */
@OptIn(KoinExperimentalAPI::class)
class ModulesTest {
    private val everything = arrayOf(
        "FT_CLIENT_ID" to "ft-id", "FT_CLIENT_SECRET" to "ft-secret", "DROPCONTACT_API_KEY" to "dc-key", "GITHUB_TOKEN" to "gh",
    )

    /** Constructor parameters that no module declares: plain values and parts of the configuration. */
    private val values = listOf(
        ServerConfig::class, DbConfig::class, LlmSettings::class, SendingConfig::class, DiscoverySettings::class,
        DiscoveryConfig::class, Mailbox::class, String::class, Boolean::class, Int::class, Long::class, List::class,
        // Built inline by the definition that needs them, from the configuration
        JobBoardSource::class, CodeurSource::class, FranceTravailSource::class,
    )

    private fun allModules(config: BloomConfig): List<Module> = bloomModules(config)

    /** verify() checks one module at a time, so the layers are included in a single module. */
    private fun verified(config: BloomConfig) = module { includes(allModules(config)) }.verify(extraTypes = values)

    @Test
    fun `no definition has a missing binding, with every optional component on`() = verified(testConfig(*everything))

    @Test
    fun `no definition has a missing binding, with every optional component off`() = verified(testConfig())

    private fun resolveEverything(config: BloomConfig) {
        val app = koinApplication { modules(allModules(config)) }
        try {
            val koin = app.koin
            // The roots: what the server and the scheduler ask for
            listOf(ApiRoutes::class, DiscoveryRoutes::class, UnsubscribeRoutes::class, Scheduler::class, Llm::class).forEach {
                assertNotNull(koin.getOrNull<Any>(it), "${it.simpleName} resolves")
            }
        } finally {
            app.close()
            stopKoin()
        }
    }

    @Test
    fun `the real modules resolve with every optional component on`() {
        resolveEverything(testConfig(*everything))
    }

    @Test
    fun `the real modules resolve with every optional component off`() {
        resolveEverything(testConfig())
    }

    @Test
    fun `optional components are declared only when configured`() {
        val on = koinApplication { modules(allModules(testConfig(*everything))) }.koin
        assertNotNull(on.getOrNull<FranceTravailSource>())
        assertNotNull(on.getOrNull<JobOfferFacts>())
        assertNotNull(on.getOrNull<EmailFinder>())
        assertNotNull(on.getOrNull<FindContactEmail>())

        val off = koinApplication { modules(allModules(testConfig())) }.koin
        assertNull(off.getOrNull<FranceTravailSource>())
        assertNull(off.getOrNull<JobOfferFacts>())
        assertNull(off.getOrNull<EmailFinder>())
        assertNull(off.getOrNull<FindContactEmail>())
        assertTrue(off.getOrNull<Scheduler>() != null)
    }

    @Test
    fun `closing Koin cancels the loops and the background scope`() {
        val app = koinApplication { modules(allModules(testConfig())) }
        val loops = app.koin.get<kotlinx.coroutines.CoroutineScope>(LOOPS)
        val background = app.koin.get<kotlinx.coroutines.CoroutineScope>(BACKGROUND)
        assertTrue(loops.isActive && background.isActive)
        app.close()
        assertTrue(!loops.isActive && !background.isActive)
    }
}
