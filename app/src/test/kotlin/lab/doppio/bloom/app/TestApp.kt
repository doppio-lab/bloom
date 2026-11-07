package lab.doppio.bloom.app

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import lab.doppio.bloom.app.config.BloomConfig
import lab.doppio.bloom.app.di.configModule
import lab.doppio.bloom.app.di.schedulerModule
import lab.doppio.bloom.app.di.useCasesModule
import lab.doppio.bloom.app.di.webModule
import lab.doppio.bloom.app.outreach.UnsubscribeTokens
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.Coach
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.Discovery
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.Drafter
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.Inbox
import lab.doppio.bloom.core.port.InstanceLock
import lab.doppio.bloom.core.port.JobRunRepository
import lab.doppio.bloom.core.port.MailSender
import lab.doppio.bloom.core.port.OfficerRepository
import lab.doppio.bloom.core.port.ReplyClassifier
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.Researcher
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.core.usecase.FakeAccountRepository
import lab.doppio.bloom.core.usecase.FakeAgentRunRepository
import lab.doppio.bloom.core.usecase.FakeDealRepository
import lab.doppio.bloom.core.usecase.FakeInstanceLock
import lab.doppio.bloom.core.usecase.FakeClassifier
import lab.doppio.bloom.core.usecase.FakeCoach
import lab.doppio.bloom.core.usecase.FakeContactRepository
import lab.doppio.bloom.core.usecase.FakeDiscovery
import lab.doppio.bloom.core.usecase.FakeDiscoveryStateRepository
import lab.doppio.bloom.core.usecase.FakeDrafter
import lab.doppio.bloom.core.usecase.FakeDraftRepository
import lab.doppio.bloom.core.usecase.FakeEventRepository
import lab.doppio.bloom.core.usecase.FakeFactRepository
import lab.doppio.bloom.core.usecase.FakeInbox
import lab.doppio.bloom.core.usecase.FakeJobRunRepository
import lab.doppio.bloom.core.usecase.FakeMailSender
import lab.doppio.bloom.core.usecase.FakeOfficerRepository
import lab.doppio.bloom.core.usecase.FakeReplyRepository
import lab.doppio.bloom.core.usecase.FakeResearcher
import lab.doppio.bloom.core.usecase.FakeSequenceRepository
import lab.doppio.bloom.core.usecase.WEDNESDAY_MORNING
import lab.doppio.bloom.core.usecase.clockAt
import lab.doppio.bloom.core.usecase.emailSequence
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.Base64

const val ADMIN_PASSWORD = "0123456789abcdef"
const val UNSUBSCRIBE_SECRET = "fedcba9876543210"

fun testConfig(vararg extra: Pair<String, String>): BloomConfig = BloomConfig.from(
    mapOf("ADMIN_PASSWORD" to ADMIN_PASSWORD, "UNSUBSCRIBE_SECRET" to UNSUBSCRIBE_SECRET, "ANTHROPIC_API_KEY" to "sk-test") + extra,
)

/** In-memory ports: no database, no LLM, no SMTP, no IMAP, no network. */
class TestPorts {
    val accounts = FakeAccountRepository()
    val officers = FakeOfficerRepository()
    val contacts = FakeContactRepository()
    val facts = FakeFactRepository()
    val sequences = FakeSequenceRepository().apply { sequences += emailSequence() }
    val drafts = FakeDraftRepository()
    val replies = FakeReplyRepository()
    val deals = FakeDealRepository()
    val events = FakeEventRepository()
    val tokens = UnsubscribeTokens(UNSUBSCRIBE_SECRET)

    /** Binds every port the use cases and the routes need to its fake. Declared after the real modules, it wins. */
    fun module(): Module = module {
        single<AccountRepository> { accounts }
        single<OfficerRepository> { officers }
        single<ContactRepository> { contacts }
        single<FactRepository> { facts }
        single<SequenceRepository> { sequences }
        single<DraftRepository> { drafts }
        single<ReplyRepository> { replies }
        single<DealRepository> { deals }
        single<EventRepository> { events }
        single<AgentRunRepository> { FakeAgentRunRepository() }
        single<JobRunRepository> { FakeJobRunRepository() }
        single<DiscoveryStateRepository> { FakeDiscoveryStateRepository() }
        single<InstanceLock> { FakeInstanceLock() }
        single<Drafter> { FakeDrafter() }
        single<Researcher> { FakeResearcher() }
        single<ReplyClassifier> { FakeClassifier() }
        single<Coach> { FakeCoach() }
        single<MailSender> { FakeMailSender() }
        single<Inbox> { FakeInbox() }
        single<Discovery> { FakeDiscovery() }
        single { tokens }
    }
}

/**
 * Runs [block] against the real routes and use cases, wired by the real configModule, useCasesModule,
 * schedulerModule and webModule, with the ports replaced by [TestPorts].
 */
fun routeTest(block: suspend ApplicationTestBuilder.(TestPorts) -> Unit) = testApplication {
    val ports = TestPorts()
    application {
        install(Koin) {
            modules(configModule(testConfig(), clockAt(WEDNESDAY_MORNING)), ports.module(), useCasesModule, schedulerModule, webModule)
        }
        module()
    }
    block(ports)
}

fun HttpRequestBuilder.admin() {
    header(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString("doppio:$ADMIN_PASSWORD".toByteArray()))
}
