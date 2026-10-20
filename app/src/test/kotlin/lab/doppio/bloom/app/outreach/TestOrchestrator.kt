package lab.doppio.bloom.app.outreach

import lab.doppio.bloom.agents.Llm
import lab.doppio.bloom.agents.LlmConfig
import lab.doppio.bloom.agents.LlmProvider
import lab.doppio.bloom.agents.ReplyClassifierAgent
import lab.doppio.bloom.agents.ResearchAgent
import lab.doppio.bloom.agents.RunTracker
import lab.doppio.bloom.agents.WeeklyCoachAgent
import lab.doppio.bloom.agents.WriterAgent
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.data.db.Repository
import lab.doppio.bloom.data.discovery.DiscoveryService
import lab.doppio.bloom.data.sources.AppStoreSource
import lab.doppio.bloom.data.sources.CompanyRegistrySource
import lab.doppio.bloom.data.sources.WebPageSource
import lab.doppio.bloom.data.sources.defaultHttpClient
import java.util.UUID

/** A gateway that sends nothing and remembers what it was asked to send. */
class FakeSender : MailSender {
    data class Sent(val mailbox: String, val to: String, val subject: String, val messageId: String)
    val sent = mutableListOf<Sent>()
    var failWith: Exception? = null

    override fun newMessageId(mailbox: Mailbox): String = "<${UUID.randomUUID()}@${mailbox.address.substringAfter('@')}>"

    override suspend fun send(mailbox: Mailbox, to: String, subject: String, body: String, contactId: Long, language: String, inReplyTo: String?, messageId: String): String {
        failWith?.let { throw it }
        sent += Sent(mailbox.address, to, subject, messageId)
        return messageId
    }
}

/**
 * An orchestrator wired with the real agents (they do nothing at construction and are never called by
 * the tested paths), a fake gateway and the given mailboxes.
 */
object TestOrchestrator {
    fun mailConfig(vararg mailboxes: Mailbox) = MailConfig(
        smtpHost = "smtp", smtpPort = 587, imapHost = "imap", mailboxes = mailboxes.toList(), publicBaseUrl = "http://localhost:8080",
        unsubscribeSecret = "secret-long-enough-0", legalFooterFr = "fr", legalFooterEn = "en", dryRun = false,
    )

    fun build(repo: Repository, sender: MailSender, config: MailConfig, policy: SendingPolicy = SendingPolicy(), inbox: MailInbox = ImapPoller(config)): Orchestrator {
        val http = defaultHttpClient()
        val llm = Llm(LlmConfig(LlmProvider.OLLAMA))
        val tracker = RunTracker(repo)
        val appStore = AppStoreSource(http)
        val registry = CompanyRegistrySource(http)
        return Orchestrator(
            repo, WriterAgent(llm, repo, tracker, "test"), ResearchAgent(llm, repo, tracker, appStore, registry, null, WebPageSource(http)),
            ReplyClassifierAgent(llm, tracker), WeeklyCoachAgent(llm, repo, tracker),
            sender, inbox, config, policy, DiscoveryService(repo, appStore, registry, null),
            instanceLock = { true },
        )
    }
}
