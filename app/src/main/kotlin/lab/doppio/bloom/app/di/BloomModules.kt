package lab.doppio.bloom.app.di

import lab.doppio.bloom.app.config.BloomConfig
import org.koin.core.module.Module
import org.koin.dsl.module
import java.time.Clock

/*
 * One Koin module per layer. They live in `app` only: core, data and agents are plain Kotlin and never see
 * the container. Implementations are bound to the port types, so a test replaces a port by declaring a
 * module after these ones (Koin lets the last definition win with allowOverride).
 *
 * Optional components (France Travail, Dropcontact, the sources chosen by DISCOVERY_SOURCES) are not
 * injected as nulls: the module functions take the immutable BloomConfig, so a component that is switched
 * off is simply not declared, and its single consumer asks for it with getOrNull. Koin has no nullable
 * definitions, and this keeps the graph honest: what is declared is what runs.
 */

/** Every module of the running application, in the order the layers depend on each other. */
fun bloomModules(config: BloomConfig, clock: Clock = Clock.systemDefaultZone()): List<Module> = listOf(
    configModule(config, clock), persistenceModule, sourcesModule(config), agentsModule, mailModule,
    useCasesModule, schedulerModule, webModule,
)
