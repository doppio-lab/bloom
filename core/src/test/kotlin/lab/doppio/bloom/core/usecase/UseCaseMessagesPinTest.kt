package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.discovery.Exclusions
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.EmailFinder
import lab.doppio.bloom.core.port.ReplyRepository
import java.time.LocalDate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pins the validation errors, report lines, stored data-source labels and suppression reasons of the use cases. */
class UseCaseMessagesPinTest {
    private val contacts = FakeContactRepository()
    private val replies = FakeReplyRepository()
    private val sequences = FakeSequenceRepository().apply { sequences += emailSequence(Offer.DISCOVERY, 1) }
    private val enroll = EnrollContact(sequences, replies, FakeFactRepository(), FakeAccountRepository(), FakeEventRepository())
    private val add = AddContact(contacts, replies, enroll)
    private val account = Account(10, "Acme")

    @Test
    fun `AddContact validation errors and stored data sources`() = runTest {
        assertEquals("Nom et rôle sont obligatoires", assertFailsWith<IllegalArgumentException> { add(account, AddContact.Request("", "CTO")) }.message)
        replies.suppressed["jane@acme.test"] = "opposition"
        assertEquals(
            "Cette personne s'est opposée à la prospection",
            assertFailsWith<IllegalArgumentException> { add(account, AddContact.Request("Jane", "CTO", email = "jane@acme.test")) }.message,
        )
        assertEquals("saisie manuelle (profil public LinkedIn ou site de l'entreprise)", add(account, AddContact.Request("Jane", "CTO")).contact.dataSource)
        assertEquals(
            "Annuaire des entreprises (dirigeant publié au registre)",
            add(account, AddContact.Request("Jane", "CTO", fromRegistry = true)).contact.dataSource,
        )
    }

    @Test
    fun `FindContactEmail stores where the email came from`() = runTest {
        val sources = mutableListOf<String>()
        val repo = object : ContactRepository by contacts {
            override suspend fun updateContactEmail(contactId: ContactId, email: String, source: String) { sources += source }
        }
        val finder = object : EmailFinder {
            override suspend fun findEmail(fullName: String, company: String, website: String?) = EmailFinder.Found("jane@acme.test", "nominative@pro")
        }
        val contact = contacts.add()
        FindContactEmail(finder, repo, FakeEventRepository(), enroll)(contact, account, enroll = false)
        assertEquals(listOf("Dropcontact (nominative@pro) à partir du nom saisi"), sources)
    }

    @Test
    fun `ImportAccounts report lines and stored data source`() = runTest {
        replies.suppressed["out@acme.test"] = "opposition"
        val accounts = object : AccountRepository by FakeAccountRepository() {
            override suspend fun upsertAccount(a: Account): AccountId = 1
        }
        val import = ImportAccounts(accounts, FakeFactRepository(), contacts, replies, FakeEventRepository(), clockAt(WEDNESDAY_MORNING))
        val report = import(
            "name,contact_name,contact_email,contact_role\n" +
                ",x,,\n" +
                "Acme,Jane,jane@acme.test,\n" +
                "Beta,Joe,out@acme.test,CTO\n",
        )
        assertEquals(listOf("ligne 2 : nom manquant", "ligne 4 : out@acme.test s'est opposé à la prospection, contact ignoré"), report.errors)
        val stored = contacts.contacts.values.single()
        assertEquals("import de la liste Doppio", stored.dataSource)
        assertEquals("inconnu", stored.role)
    }

    @Test
    fun `suppression reasons`() = runTest {
        val stop = StopContactSequences(sequences)
        val contact = contacts.add()
        UnsubscribeContact(contacts, replies, FakeEventRepository(), stop)(contact.id)
        assertEquals("lien de désinscription", replies.suppressed[contact.email])

        val classifier = FakeClassifier(ReplyIntent.UNSUBSCRIBE)
        val inbox = FakeInbox().apply { mails = listOf(inbound(1)) }
        ProcessReplies(inbox, classifier, replies, FakeDraftRepository(), sequences, contacts, FakeEventRepository(), stop, PostponeContactSequences(sequences, FakeDraftRepository(), SendingPolicy()))()
        assertEquals("réponse : résumé", replies.suppressed[contact.email])
    }

    @Test
    fun `a bounce is stored with its summary and suppresses the address as rebond`() = runTest {
        val summaries = mutableListOf<String>()
        val repo = object : ReplyRepository by replies {
            override suspend fun saveReply(
                enrollmentId: Long?, from: String, subject: String?, body: String, intent: ReplyIntent,
                confidence: Double, followUpOn: LocalDate?, summary: String, receivedAt: Instant, inboundKey: String?,
            ): Long { summaries += summary; return 1 }
        }
        val contact = contacts.add(email = "bounced@client.test")
        val drafts = FakeDraftRepository()
        val enrollment = sequences.addEnrollment(contact.id)
        drafts.messageIdToEnrollment["<orig@doppio.test>"] = enrollment.id
        val inbox = FakeInbox().apply { mails = listOf(inbound(1, from = "mailer-daemon@mx.test", bounce = true, inReplyTo = listOf("<orig@doppio.test>"))) }
        ProcessReplies(inbox, FakeClassifier(), repo, drafts, sequences, contacts, FakeEventRepository(), StopContactSequences(sequences), PostponeContactSequences(sequences, drafts, SendingPolicy()))()
        assertEquals(listOf("Rebond : adresse invalide"), summaries)
        assertEquals("rebond", replies.suppressed["bounced@client.test"])
    }

    @Test
    fun `exclusion reasons`() {
        assertEquals("liste d'exclusion : capgemini", Exclusions.reason("Capgemini"))
        assertEquals("mot-clé : consulting", Exclusions.reason("Acme Consulting Partners"))
        assertEquals("code NAF 78.10Z (intérim ou conseil)", Exclusions.reason("Acme", naf = "78.10Z"))
        assertEquals(null, Exclusions.reason("Acme"))
    }

    @Test
    fun `overview signal labels`() {
        val expected = mapOf(
            SignalType.REFERRAL to "Lien chaud", SignalType.HIRING_STUCK to "Recrutement bloqué", SignalType.QUALITY_COMPLAINTS to "Plaintes qualité (avis)",
            SignalType.RATING_DROP to "Note en baisse", SignalType.HIRING_KMP to "Recrutement KMP", SignalType.NEW_TECH_LEADER to "Nouveau dirigeant tech",
            SignalType.FUNDING to "Levée de fonds", SignalType.HIRING_MOBILE to "Recrutement mobile", SignalType.PRODUCT_LAUNCH to "Lancement produit",
            SignalType.RELEASE_STALL to "App sans mise à jour", SignalType.CONTENT_ENGAGEMENT to "Interaction LinkedIn", SignalType.AI_ADOPTION to "Équipe LLM",
            SignalType.PUBLIC_TENDER to "Marché public", SignalType.KMP_ADOPTION to "Code KMP public",
            SignalType.CONTRACT_RENEWAL to "Marché à renouveler",
        )
        assertEquals(SignalType.entries.toSet(), expected.keys)
        expected.forEach { (type, label) -> assertEquals(label, OverviewLabels.signal(type), type.name) }
        assertEquals("Sans signal", OverviewLabels.signal(null))
    }
}
