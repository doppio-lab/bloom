package lab.doppio.bloom.app.di

import lab.doppio.bloom.app.outreach.EmailGateway
import lab.doppio.bloom.app.outreach.ImapPoller
import lab.doppio.bloom.app.outreach.MailConfig
import lab.doppio.bloom.app.outreach.UnsubscribeTokens
import lab.doppio.bloom.core.port.Inbox
import lab.doppio.bloom.core.port.MailSender
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** Sending and receiving mail: unsubscribe tokens, SMTP gateway, IMAP poller. */
val mailModule: Module = module {
    single { UnsubscribeTokens(get<MailConfig>().unsubscribeSecret) }
    singleOf(::EmailGateway) { bind<MailSender>() }
    singleOf(::ImapPoller) { bind<Inbox>() }
}
