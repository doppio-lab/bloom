package lab.doppio.bloom.app.di

import lab.doppio.bloom.app.config.BloomConfig
import lab.doppio.bloom.app.outreach.MailConfig
import lab.doppio.bloom.core.model.SendingPolicy
import org.koin.core.module.Module
import org.koin.dsl.module
import java.time.Clock

/** The configuration, cut into the pieces components ask for, and the clock. */
fun configModule(config: BloomConfig, clock: Clock = Clock.systemDefaultZone()): Module = module {
    single { config }
    single { clock }
    single<MailConfig> { config.mail }
    single<SendingPolicy> { config.sending.policy }
    single { config.loops }
}
