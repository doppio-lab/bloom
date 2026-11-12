# Parcours kanban Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the À faire and Prospects views of the Bloom dashboard by a kanban board, Parcours, whose cards say where each account stands and whose turn it is, with a side panel to act on a card without leaving the board.

**Architecture:** The server computes the board in a new `core` use case, `GetBoard`, from the existing ports (one port method added). Two small use cases give the board its missing actions: `BookMeeting` (record a meeting from a reply) and `CompleteContactEmail` (add the missing email and start the sequence). The page (`index.html`, one file, no framework) fetches `/api/board`, draws five columns, and opens a panel that reuses today's card renderers. The Claude Design project is updated before the page.

**Tech Stack:** Kotlin, Ktor, Koin, Exposed, PostgreSQL (Testcontainers in `:data:test`), `kotlin.test`; vanilla HTML, CSS and JS in `app/src/main/resources/static/index.html`; Claude artifact "Bloom" for the design.

**Spec:** `docs/superpowers/specs/2026-11-12-parcours-kanban-design.md`. Read it first.

**Rules of the repo that apply to every task** (from `AGENTS.md` and the global rules):

- No em dash character (U+2014) anywhere (code, comments, copy, commit messages). Check with `grep -n "$(printf '\342\200\224')" <file>` before committing.
- Code comments and commit messages in English. Interface copy in French, addressing the user as "vous".
- Test names are French sentences in backticks describing the behaviour.
- No Claude sign-off or `Co-Authored-By` trailer in commits.
- Colours, fonts and radii come from the Thermos tokens already in `index.html`. `ProjectRulesTest` fails on a colour literal or a font outside them.
- Interface copy must stay inside the claims marked proven in `marketing/claims-audit.md`. `ProjectRulesTest` fails on a sentence marked False.
- Do not run `mise run dev`, `up`, `db` or `backup`, and do not trigger discovery or research: they start the real database or call the paid LLM.
- Never read or print `.env`.

**Commands:**

- Fast loop (about 10 s): `./gradlew :core:test :agents:test :app:test`
- Data tests (Docker or OrbStack must be running): `./gradlew :data:test`
- Everything CI runs: `./gradlew build`
- Interface with example data: `scripts/ui-demo.sh` then `http://localhost:8099/?demo=1`. The script copies the page at start: restart it after each edit of `index.html`.
- Screenshots without a browser extension:
  `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars --window-size=1440,1600 --virtual-time-budget=4000 --screenshot=<out.png> "http://localhost:8099/?demo=1#parcours"`

## File structure

| File | Change | Responsibility |
| --- | --- | --- |
| `core/src/main/kotlin/lab/doppio/bloom/core/port/SequenceRepository.kt` | modify | add `enrolledContactIds()` |
| `data/src/main/kotlin/lab/doppio/bloom/data/persistence/sequence/ExposedSequenceRepository.kt` | modify | implement it |
| `data/src/test/kotlin/lab/doppio/bloom/data/persistence/SequenceRepositoryTest.kt` | create | test it on PostgreSQL |
| `core/src/testFixtures/kotlin/lab/doppio/bloom/core/usecase/Fakes.kt` | modify | `FakeSequenceRepository.enrolledContactIds`, open replies in `FakeReplyRepository` |
| `core/src/main/kotlin/lab/doppio/bloom/core/usecase/GetBoard.kt` | create | board model and placement rules |
| `core/src/test/kotlin/lab/doppio/bloom/core/usecase/GetBoardTest.kt` | create | one test per rule |
| `core/src/main/kotlin/lab/doppio/bloom/core/usecase/BookMeeting.kt` | create | record a meeting from a reply |
| `core/src/test/kotlin/lab/doppio/bloom/core/usecase/BookMeetingTest.kt` | create | |
| `core/src/main/kotlin/lab/doppio/bloom/core/usecase/CompleteContactEmail.kt` | create | add a missing email, then enroll |
| `core/src/test/kotlin/lab/doppio/bloom/core/usecase/CompleteContactEmailTest.kt` | create | |
| `app/src/main/kotlin/lab/doppio/bloom/app/web/Routes.kt` | modify | `GET /api/board`, `POST /api/accounts/{id}/meeting`, `POST /api/contacts/{id}/email` |
| `app/src/main/kotlin/lab/doppio/bloom/app/web/Dtos.kt` | modify | `MeetingRequest`, `EmailRequest` |
| `app/src/main/kotlin/lab/doppio/bloom/app/di/Modules.kt` | modify | bind the three use cases, pass them to `ApiRoutes` |
| `app/src/test/kotlin/lab/doppio/bloom/app/web/RoutesTest.kt` | modify | tests of the three routes |
| Claude artifact Bloom, `project/Bloom.dc.html` and `project/README.md` | modify | design first |
| `app/src/main/resources/static/index.html` | modify | the board, the panel, the navigation, the example data |

---

### Task 1: Port method and fakes

**Files:**
- Modify: `core/src/main/kotlin/lab/doppio/bloom/core/port/SequenceRepository.kt`
- Modify: `data/src/main/kotlin/lab/doppio/bloom/data/persistence/sequence/ExposedSequenceRepository.kt`
- Create: `data/src/test/kotlin/lab/doppio/bloom/data/persistence/SequenceRepositoryTest.kt`
- Modify: `core/src/testFixtures/kotlin/lab/doppio/bloom/core/usecase/Fakes.kt`

- [ ] **Step 1: Write the failing data test**

Create `data/src/test/kotlin/lab/doppio/bloom/data/persistence/SequenceRepositoryTest.kt`:

```kotlin
package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.account.ExposedAccountRepository
import lab.doppio.bloom.data.persistence.contact.ExposedContactRepository
import lab.doppio.bloom.data.persistence.sequence.ExposedSequenceRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SequenceRepositoryTest {
    private val accounts = ExposedAccountRepository()
    private val contacts = ExposedContactRepository()
    private val sequences = ExposedSequenceRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `les contacts déjà inscrits le restent, même après la fin de leur séquence`() = runTest {
        sequences.ensureSequences(DefaultSequences.all())
        val accountId = accounts.upsertAccount(Account(name = "Velomoov"))
        fun person(name: String) = Contact(accountId = accountId, fullName = name, role = "CTO", persona = Persona.CTO, email = "$name@velomoov.fr", dataSource = "test")
        val running = contacts.addContact(person("alex"))
        val finished = contacts.addContact(person("sam"))
        contacts.addContact(person("lou"))
        val seq = sequences.all().first().id
        sequences.enroll(running, seq)
        sequences.setEnrollmentStatus(sequences.enroll(finished, seq), EnrollmentStatus.COMPLETED)

        assertEquals(setOf(running, finished), sequences.enrolledContactIds())
    }
}
```

- [ ] **Step 2: Add the port method and the fake**

In `SequenceRepository.kt`, after `activeEnrollments()`:

```kotlin
    /** Every contact that was ever enrolled, whatever the status of the enrollment. */
    suspend fun enrolledContactIds(): Set<ContactId>
```

In `Fakes.kt`, in `FakeSequenceRepository`, after `activeEnrollments`:

```kotlin
    override suspend fun enrolledContactIds(): Set<ContactId> = enrollments.values.map { it.contactId }.toSet()
```

- [ ] **Step 3: Run the data test to verify it fails**

Run: `./gradlew :data:test --tests '*SequenceRepositoryTest*'`
Expected: compilation FAIL, `ExposedSequenceRepository` does not implement `enrolledContactIds`.

- [ ] **Step 4: Implement it**

In `ExposedSequenceRepository.kt`, after `activeEnrollments()`:

```kotlin
    override suspend fun enrolledContactIds(): Set<ContactId> = dbQuery {
        EnrollmentsTable.select(EnrollmentsTable.contactId).withDistinct().map { it[EnrollmentsTable.contactId] }.toSet()
    }
```

If `EnrollmentsTable.contactId` is a reference column, `it[EnrollmentsTable.contactId]` may be an `EntityID`: use `.value` then. Check how `toEnrollment()` reads it in the same file and do the same.

- [ ] **Step 5: Give `FakeReplyRepository` open replies**

In `Fakes.kt`, replace the three lines of `FakeReplyRepository` for `openReplies`, `countOpenReplies` and `markReplyHandled` by:

```kotlin
    /** Replies waiting for the user; a test adds rows here. */
    val open = mutableListOf<ReplyRow>()
    val handled = mutableListOf<Long>()

    override suspend fun openReplies(): List<ReplyRow> = open.filter { it.id !in handled }
    override suspend fun countOpenReplies(): Long = openReplies().size.toLong()
    override suspend fun markReplyHandled(id: Long) { handled += id }
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :data:test --tests '*SequenceRepositoryTest*' :core:test :app:test`
Expected: PASS (the existing tests do not depend on the fake returning no reply: the list starts empty).

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/lab/doppio/bloom/core/port/SequenceRepository.kt core/src/testFixtures/kotlin/lab/doppio/bloom/core/usecase/Fakes.kt data/src/main/kotlin/lab/doppio/bloom/data/persistence/sequence/ExposedSequenceRepository.kt data/src/test/kotlin/lab/doppio/bloom/data/persistence/SequenceRepositoryTest.kt
git commit -m "List every contact ever enrolled, and let the reply fake hold open replies"
```

---

### Task 2: `GetBoard`

**Files:**
- Create: `core/src/main/kotlin/lab/doppio/bloom/core/usecase/GetBoard.kt`
- Create: `core/src/test/kotlin/lab/doppio/bloom/core/usecase/GetBoardTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/kotlin/lab/doppio/bloom/core/usecase/GetBoardTest.kt`:

```kotlin
package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.ReplyRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetBoardTest {
    private val accounts = FakeAccountRepository()
    private val contacts = FakeContactRepository()
    private val sequences = FakeSequenceRepository().apply { sequences += emailSequence() }
    private val drafts = FakeDraftRepository()
    private val replies = FakeReplyRepository()
    private val deals = FakeDealRepository()
    private val facts = FakeFactRepository()
    private val board = GetBoard(accounts, contacts, sequences, drafts, replies, deals, facts)

    private fun row(id: Long, name: String, score: Int = 70, offer: Offer? = Offer.DISCOVERY) =
        AccountRow(Account(id, name, source = "BODACC"), score, 0, 0, 0, offer, emptyList(), null)
    private fun reply(id: Long, from: String, enrollmentId: Long? = null, intent: ReplyIntent = ReplyIntent.MEETING_REQUEST) =
        ReplyRow(id, enrollmentId, from, intent, "résumé", null, "2026-11-04T09:00:00Z", false)

    @Test
    fun `un compte sans contact attend qu'on trouve la personne`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        accounts.toComplete = accounts.rows
        val card = board().cards.single()
        assertEquals(BoardStage.TO_START, card.stage)
        assertEquals(BoardAction.FIND_CONTACT, card.action)
        assertTrue(card.yours)
        assertEquals("Acme", card.title)
        assertEquals(Offer.DISCOVERY, card.offer)
    }

    @Test
    fun `un compte dont le dossier ne trouve aucune offre n'est pas à démarrer`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        accounts.toComplete = accounts.rows
        facts.dossier = Dossier(10, "", emptyList(), "", Offer.DISCOVERY, noFit = true)
        assertTrue(board().cards.isEmpty())
    }

    @Test
    fun `un contact sans email attend son email, avec un email il attend le lancement de la séquence`() = runTest {
        accounts.rows = listOf(row(10, "Acme"), row(11, "Globex"))
        contacts.add(email = null, accountId = 10, name = "Jane")
        contacts.add(email = "max@globex.test", accountId = 11, name = "Max")
        val cards = board().cards.associateBy { it.title }
        assertEquals(BoardAction.ADD_EMAIL, cards.getValue("Acme").action)
        assertEquals("Jane", cards.getValue("Acme").contactName)
        assertEquals(BoardAction.START_SEQUENCE, cards.getValue("Globex").action)
        assertTrue(cards.values.all { it.stage == BoardStage.TO_START })
    }

    @Test
    fun `un brouillon en attente place la carte dans Email à valider, à corriger quand le garde-fou bloque`() = runTest {
        accounts.rows = listOf(row(10, "Acme"), row(11, "Globex"))
        val jane = contacts.add(accountId = 10, name = "Jane")
        val max = contacts.add(email = "max@globex.test", accountId = 11, name = "Max")
        val ok = drafts.add(sequences.addEnrollment(jane.id).id, DraftStatus.PENDING_REVIEW)
        val blocked = drafts.add(sequences.addEnrollment(max.id).id, DraftStatus.PENDING_REVIEW)
        drafts.drafts[blocked.id] = blocked.copy(guardReport = listOf("[BLOCK] vocabulaire : expression interdite"))
        val cards = board().cards.associateBy { it.title }
        assertEquals(BoardStage.TO_REVIEW, cards.getValue("Acme").stage)
        assertEquals(BoardAction.REVIEW_EMAIL, cards.getValue("Acme").action)
        assertEquals(ok.id, cards.getValue("Acme").draftId)
        assertEquals(BoardAction.FIX_EMAIL, cards.getValue("Globex").action)
        assertEquals(1, cards.getValue("Acme").step)
        assertEquals(2, cards.getValue("Acme").stepCount)
    }

    @Test
    fun `une séquence en cours dit qui doit agir`() = runTest {
        accounts.rows = listOf(row(10, "Note"), row(11, "Envoi"), row(12, "Rédaction"), row(13, "Attente"))
        val note = sequences.addEnrollment(contacts.add(accountId = 10).id)
        drafts.add(note.id, DraftStatus.APPROVED, channel = Channel.LINKEDIN)
        drafts.add(sequences.addEnrollment(contacts.add(accountId = 11).id).id, DraftStatus.APPROVED)
        sequences.addEnrollment(contacts.add(accountId = 12).id, nextStep = 0)
        sequences.addEnrollment(contacts.add(accountId = 13).id, nextStep = 1)
        val cards = board().cards.associateBy { it.title }
        assertTrue(cards.values.all { it.stage == BoardStage.IN_SEQUENCE })
        assertEquals(BoardAction.SEND_LINKEDIN_NOTE, cards.getValue("Note").action)
        assertTrue(cards.getValue("Note").yours)
        assertEquals(BoardAction.WAIT_SEND, cards.getValue("Envoi").action)
        assertEquals(BoardAction.WAIT_DRAFT, cards.getValue("Rédaction").action)
        assertEquals(BoardAction.WAIT_REPLY, cards.getValue("Attente").action)
        assertTrue(listOf("Envoi", "Rédaction", "Attente").none { cards.getValue(it).yours })
    }

    @Test
    fun `une séquence terminée ne donne aucune carte`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        sequences.addEnrollment(contacts.add(accountId = 10).id, EnrollmentStatus.COMPLETED)
        assertTrue(board().cards.isEmpty())
    }

    @Test
    fun `une réponse ouverte place la carte dans A répondu, sans carte de séquence en double`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        val jane = contacts.add(accountId = 10, name = "Jane")
        val e = sequences.addEnrollment(jane.id)
        replies.open += reply(7, "jane@client.test", e.id)
        val card = board().cards.single()
        assertEquals(BoardStage.REPLIED, card.stage)
        assertEquals(BoardAction.ANSWER_REPLY, card.action)
        assertEquals(7L, card.replyId)
        assertEquals(ReplyIntent.MEETING_REQUEST, card.replyIntent)
        assertEquals(10L, card.accountId)
    }

    @Test
    fun `une réponse sans contact connu porte l'adresse de l'expéditeur`() = runTest {
        replies.open += reply(8, "inconnu@ailleurs.test")
        val card = board().cards.single()
        assertEquals("inconnu@ailleurs.test", card.title)
        assertNull(card.accountId)
    }

    @Test
    fun `un compte avec un rendez-vous n'a qu'une carte, dans Rendez-vous`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        val jane = contacts.add(accountId = 10)
        val e = sequences.addEnrollment(jane.id)
        drafts.add(e.id, DraftStatus.PENDING_REVIEW)
        replies.open += reply(9, "jane@client.test", e.id)
        deals.deals += Deal(1, 10, Offer.DISCOVERY, DealStage.MEETING, 0, null, null)
        val card = board().cards.single()
        assertEquals(BoardStage.MEETING, card.stage)
        assertEquals(BoardAction.NONE, card.action)
        assertTrue(!card.yours)
    }

    @Test
    fun `un compte en liste noire n'apparaît pas, même avec une réponse`() = runTest {
        // A blacklisted account is missing from accountsByScore
        val jane = contacts.add(accountId = 10)
        replies.open += reply(9, "jane@client.test", sequences.addEnrollment(jane.id).id)
        assertTrue(board().cards.isEmpty())
    }

    @Test
    fun `les colonnes suivent le parcours, et dans une colonne ce qui vous attend passe devant, puis le score`() = runTest {
        accounts.rows = listOf(row(10, "Bas", score = 50), row(11, "Haut", score = 90), row(12, "Attente", score = 99))
        accounts.toComplete = listOf(accounts.rows[0])
        sequences.addEnrollment(contacts.add(accountId = 12).id, nextStep = 1)
        drafts.add(sequences.addEnrollment(contacts.add(accountId = 11).id).id, DraftStatus.APPROVED, channel = Channel.LINKEDIN)
        assertEquals(listOf("Bas", "Haut", "Attente"), board().cards.map { it.title })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test --tests '*GetBoardTest*'`
Expected: compilation FAIL, `GetBoard`, `BoardStage` and `BoardAction` are unresolved.

- [ ] **Step 3: Implement `GetBoard`**

Create `core/src/main/kotlin/lab/doppio/bloom/core/usecase/GetBoard.kt`:

```kotlin
package lab.doppio.bloom.core.usecase

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.SequenceRepository

/** The columns of the board, in the order of the journey. */
@Serializable
enum class BoardStage { TO_START, TO_REVIEW, IN_SEQUENCE, REPLIED, MEETING }

/** The next thing that happens to a card, and whether the user is the one who does it. */
@Serializable
enum class BoardAction(val yours: Boolean) {
    FIND_CONTACT(true), ADD_EMAIL(true), START_SEQUENCE(true), REVIEW_EMAIL(true), FIX_EMAIL(true),
    SEND_LINKEDIN_NOTE(true), WAIT_DRAFT(false), WAIT_SEND(false), WAIT_REPLY(false), ANSWER_REPLY(true), NONE(false),
}

/** One card: an account, or an account and the person contacted there. The page finds the content of its panel by these ids. */
@Serializable
data class BoardCard(
    val stage: BoardStage, val action: BoardAction, val yours: Boolean,
    val accountId: AccountId?, val title: String, val score: Int?, val source: String?, val offer: Offer?,
    val contactId: ContactId? = null, val contactName: String? = null, val contactRole: String? = null,
    val draftId: Long? = null, val replyId: Long? = null, val replyIntent: ReplyIntent? = null,
    val step: Int? = null, val stepCount: Int? = null,
)

@Serializable
data class Board(val cards: List<BoardCard>)

/**
 * Where each account stands in the journey, from discovery to the meeting, and whose turn it is.
 * A stage is derived from facts (a contact, a draft, a reply, a deal), never set by hand.
 * Blacklisted accounts are missing from accountsByScore, so they never get a card.
 */
class GetBoard(
    private val accountRepo: AccountRepository,
    private val contactRepo: ContactRepository,
    private val sequenceRepo: SequenceRepository,
    private val draftRepo: DraftRepository,
    private val replyRepo: ReplyRepository,
    private val dealRepo: DealRepository,
    private val factRepo: FactRepository,
) {
    suspend operator fun invoke(): Board {
        val rows = accountRepo.accountsByScore(500).associateBy { it.account.id }
        val contacts = contactRepo.contactsByAccount().values.flatten()
        val contactById = contacts.associateBy { it.id }
        val stepCounts = sequenceRepo.all().associate { it.id to it.steps.size }
        val cards = mutableListOf<BoardCard>()

        suspend fun card(row: AccountRow, stage: BoardStage, action: BoardAction, contact: Contact? = null) = BoardCard(
            stage = stage, action = action, yours = action.yours,
            accountId = row.account.id, title = row.account.name, score = row.score, source = row.account.source,
            offer = factRepo.dossier(row.account.id)?.offer ?: row.recommendedOffer,
            contactId = contact?.id, contactName = contact?.fullName, contactRole = contact?.role,
        )

        // 1. A meeting: one card for the account, and nothing else for it
        val meetings = dealRepo.deals().filter { it.stage in MEETING_STAGES }.map { it.accountId }.toSet()
        meetings.mapNotNull { rows[it] }.sortedByDescending { it.score }.take(MAX_MEETINGS)
            .forEach { cards += card(it, BoardStage.MEETING, BoardAction.NONE) }

        // 2. Open replies. The sequence of the person who replied is stopped, so it gets no other card
        val replied = mutableSetOf<ContactId>()
        for (r in replyRepo.openReplies()) {
            val contact = r.enrollmentId?.let { sequenceRepo.enrollment(it) }?.let { contactById[it.contactId] }
                ?: contactRepo.contactByEmail(r.from)
            if (contact == null) {
                cards += BoardCard(BoardStage.REPLIED, BoardAction.ANSWER_REPLY, true, null, r.from, null, null, null, replyId = r.id, replyIntent = r.intent)
                continue
            }
            replied += contact.id
            val row = rows[contact.accountId] ?: continue
            if (row.account.id in meetings) continue
            cards += card(row, BoardStage.REPLIED, BoardAction.ANSWER_REPLY, contact).copy(replyId = r.id, replyIntent = r.intent)
        }

        // 3. Running sequences: a draft to review, a LinkedIn note to send, or Bloom at work
        val pending = draftRepo.draftsByStatus(DraftStatus.PENDING_REVIEW).groupBy { it.enrollmentId }
        val approved = (draftRepo.draftsByStatus(DraftStatus.APPROVED) + draftRepo.draftsByStatus(DraftStatus.SENDING)).groupBy { it.enrollmentId }
        for (e in sequenceRepo.activeEnrollments()) {
            if (e.contactId in replied) continue
            val contact = contactById[e.contactId] ?: continue
            val row = rows[contact.accountId] ?: continue
            if (row.account.id in meetings) continue
            val review = pending[e.id]?.firstOrNull()
            val note = approved[e.id]?.firstOrNull { it.channel != Channel.EMAIL }
            val email = approved[e.id]?.firstOrNull { it.channel == Channel.EMAIL }
            val draft = review ?: note ?: email
            val (stage, action) = when {
                review != null -> BoardStage.TO_REVIEW to if (review.guardReport.any { it.startsWith(BLOCK) }) BoardAction.FIX_EMAIL else BoardAction.REVIEW_EMAIL
                note != null -> BoardStage.IN_SEQUENCE to BoardAction.SEND_LINKEDIN_NOTE
                email != null -> BoardStage.IN_SEQUENCE to BoardAction.WAIT_SEND
                e.nextStep == 0 -> BoardStage.IN_SEQUENCE to BoardAction.WAIT_DRAFT
                else -> BoardStage.IN_SEQUENCE to BoardAction.WAIT_REPLY
            }
            cards += card(row, stage, action, contact).copy(
                draftId = draft?.id,
                step = draft?.let { it.stepIndex + 1 } ?: e.nextStep.coerceAtLeast(1),
                stepCount = stepCounts[e.sequenceId],
            )
        }

        // 4. Not started: nobody of the account was ever enrolled
        val enrolled = sequenceRepo.enrolledContactIds()
        val byAccount = contacts.groupBy { it.accountId }
        for ((accountId, people) in byAccount) {
            if (accountId in meetings || people.any { it.id in enrolled || it.id in replied }) continue
            val row = rows[accountId] ?: continue
            val contact = people.firstOrNull { it.email != null } ?: people.first()
            cards += card(row, BoardStage.TO_START, if (contact.email != null) BoardAction.START_SEQUENCE else BoardAction.ADD_EMAIL, contact)
        }
        for (row in accountRepo.accountsToComplete()) {
            val id = row.account.id
            if (id in byAccount || id in meetings || factRepo.dossier(id)?.noFit == true) continue
            cards += card(row, BoardStage.TO_START, BoardAction.FIND_CONTACT)
        }

        return Board(cards.sortedWith(compareBy<BoardCard>({ it.stage.ordinal }, { !it.yours }, { -(it.score ?: 0) })))
    }

    private companion object {
        val MEETING_STAGES = setOf(DealStage.MEETING, DealStage.DISCOVERY_SOLD, DealStage.PROPOSAL)
        const val MAX_MEETINGS = 20
        /** Prefix of a blocking guard violation, see Violation.toString. */
        const val BLOCK = "[BLOCK]"
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:test --tests '*GetBoardTest*'`
Expected: PASS, 11 tests. If `les colonnes suivent le parcours...` fails, check the comparator: stage ordinal first, then `yours` (false sorts before true, so `!it.yours`), then score descending.

- [ ] **Step 5: Run the fast loop and commit**

Run: `./gradlew :core:test`
Expected: PASS.

```bash
git add core/src/main/kotlin/lab/doppio/bloom/core/usecase/GetBoard.kt core/src/test/kotlin/lab/doppio/bloom/core/usecase/GetBoardTest.kt
git commit -m "Compute the Parcours board: one card per account and person, with whose turn it is"
```

---

### Task 3: `BookMeeting`

**Files:**
- Create: `core/src/main/kotlin/lab/doppio/bloom/core/usecase/BookMeeting.kt`
- Create: `core/src/test/kotlin/lab/doppio/bloom/core/usecase/BookMeetingTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.port.AccountRow
import kotlin.test.Test
import kotlin.test.assertEquals

class BookMeetingTest {
    private val accounts = FakeAccountRepository(listOf(AccountRow(Account(10, "Acme"), 70, 0, 0, 0, Offer.REGIE, emptyList(), null)))
    private val deals = FakeDealRepository()
    private val replies = FakeReplyRepository()
    private val events = FakeEventRepository()
    private val facts = FakeFactRepository()
    private val book = BookMeeting(accounts, deals, replies, events, facts)

    @Test
    fun `noter un rendez-vous crée l'affaire, compte le rendez-vous et classe la réponse`() = runTest {
        assertEquals(BookMeeting.Result.Booked, book(10, replyId = 7))
        val deal = deals.deals.single()
        assertEquals(DealStage.MEETING, deal.stage)
        assertEquals(Offer.REGIE, deal.offer)
        assertEquals(0L, deal.amountEur)
        assertEquals(listOf("MEETING_BOOKED"), events.types)
        assertEquals(listOf(7L), replies.handled)
    }

    @Test
    fun `l'offre du dossier passe avant l'offre recommandée`() = runTest {
        facts.dossier = Dossier(10, "", emptyList(), "", Offer.TRAINING_KMP)
        book(10, replyId = null)
        assertEquals(Offer.TRAINING_KMP, deals.deals.single().offer)
    }

    @Test
    fun `une affaire qualifiée passe au rendez-vous au lieu d'en créer une autre`() = runTest {
        deals.deals += Deal(1, 10, Offer.FORFAIT, DealStage.QUALIFIED, 12000, null, null)
        book(10, replyId = null)
        assertEquals(listOf(DealStage.MEETING), deals.deals.map { it.stage })
        assertEquals(12000L, deals.deals.single().amountEur)
    }

    @Test
    fun `un rendez-vous déjà noté n'est pas compté deux fois, la réponse est quand même classée`() = runTest {
        deals.deals += Deal(1, 10, Offer.FORFAIT, DealStage.PROPOSAL, 40000, null, null)
        assertEquals(BookMeeting.Result.AlreadyBooked, book(10, replyId = 7))
        assertEquals(1, deals.deals.size)
        assertEquals(emptyList(), events.types)
        assertEquals(listOf(7L), replies.handled)
    }

    @Test
    fun `un compte inconnu ne crée rien`() = runTest {
        assertEquals(BookMeeting.Result.NotFound, book(99, replyId = 7))
        assertEquals(emptyList(), deals.deals)
        assertEquals(emptyList(), replies.handled)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests '*BookMeetingTest*'`
Expected: compilation FAIL, `BookMeeting` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.ReplyRepository

/**
 * Records a meeting from the board: the account gets a deal at the MEETING stage, the meeting counts towards the
 * objective once, and the reply that led to it leaves the queue. Later stages (proposal, amount) stay outside Bloom.
 */
class BookMeeting(
    private val accountRepo: AccountRepository,
    private val dealRepo: DealRepository,
    private val replyRepo: ReplyRepository,
    private val eventRepo: EventRepository,
    private val factRepo: FactRepository,
) {
    sealed interface Result {
        data object Booked : Result
        data object AlreadyBooked : Result
        data object NotFound : Result
    }

    suspend operator fun invoke(accountId: AccountId, replyId: Long?): Result {
        accountRepo.account(accountId) ?: return Result.NotFound
        val deals = dealRepo.deals().filter { it.accountId == accountId }
        val result = if (deals.any { it.stage in BOOKED }) Result.AlreadyBooked else {
            val deal = deals.firstOrNull { it.stage == DealStage.QUALIFIED }?.copy(stage = DealStage.MEETING) ?: Deal(
                accountId = accountId,
                offer = factRepo.dossier(accountId)?.offer
                    ?: accountRepo.accountsByScore(500).firstOrNull { it.account.id == accountId }?.recommendedOffer
                    ?: Offer.DISCOVERY,
                stage = DealStage.MEETING, amountEur = 0, sourceSignal = factRepo.dominantSignal(accountId), expectedClose = null,
            )
            dealRepo.upsertDeal(deal)
            eventRepo.logEvent("MEETING_BOOKED", accountId)
            Result.Booked
        }
        replyId?.let { replyRepo.markReplyHandled(it) }
        return result
    }

    private companion object {
        val BOOKED = setOf(DealStage.MEETING, DealStage.DISCOVERY_SOLD, DealStage.PROPOSAL, DealStage.WON)
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:test --tests '*BookMeetingTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/lab/doppio/bloom/core/usecase/BookMeeting.kt core/src/test/kotlin/lab/doppio/bloom/core/usecase/BookMeetingTest.kt
git commit -m "Record a meeting from a reply, counted once"
```

---

### Task 4: `CompleteContactEmail`

**Files:**
- Create: `core/src/main/kotlin/lab/doppio/bloom/core/usecase/CompleteContactEmail.kt`
- Create: `core/src/test/kotlin/lab/doppio/bloom/core/usecase/CompleteContactEmailTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Offer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompleteContactEmailTest {
    private val contacts = FakeContactRepository()
    private val replies = FakeReplyRepository()
    private val sequences = FakeSequenceRepository().apply { sequences += emailSequence() }
    private val facts = FakeFactRepository()
    private val enroll = EnrollContact(sequences, replies, facts, FakeAccountRepository(), FakeEventRepository())
    private val complete = CompleteContactEmail(contacts, replies, enroll)

    @Test
    fun `l'email ajouté est enregistré et la séquence démarre`() = runTest {
        val jane = contacts.add(email = null)
        val result = complete(jane.id, "  jane@acme.test ")
        assertTrue(result is CompleteContactEmail.Result.Done && result.enrollment is EnrollContact.Result.Enrolled)
        assertEquals("jane@acme.test", contacts.contacts.getValue(jane.id).email)
        assertEquals(1, sequences.enrollments.size)
    }

    @Test
    fun `une adresse invalide est refusée et rien ne change`() = runTest {
        val jane = contacts.add(email = null)
        assertFailsWith<IllegalArgumentException> { complete(jane.id, "jane at acme") }
        assertNull(contacts.contacts.getValue(jane.id).email)
    }

    @Test
    fun `une personne qui s'est opposée n'est jamais enregistrée`() = runTest {
        val jane = contacts.add(email = null)
        replies.suppressed["jane@acme.test"] = "opposition"
        assertFailsWith<IllegalArgumentException> { complete(jane.id, "jane@acme.test") }
        assertTrue(sequences.enrollments.isEmpty())
    }

    @Test
    fun `quand le dossier ne trouve aucune offre, l'email est gardé mais la séquence attend votre choix`() = runTest {
        val jane = contacts.add(email = null)
        facts.dossier = Dossier(10, "", emptyList(), "", Offer.DISCOVERY, noFit = true)
        val result = complete(jane.id, "jane@acme.test")
        assertEquals(CompleteContactEmail.Result.Done(EnrollContact.Result.NoFit), result)
        assertEquals("jane@acme.test", contacts.contacts.getValue(jane.id).email)
    }

    @Test
    fun `un contact inconnu répond introuvable`() = runTest {
        assertEquals(CompleteContactEmail.Result.NotFound, complete(99, "jane@acme.test"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests '*CompleteContactEmailTest*'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

```kotlin
package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.ReplyRepository

/** The email of a contact typed on the board: stored, then the contact is enrolled as if a human had asked. */
class CompleteContactEmail(
    private val contactRepo: ContactRepository,
    private val replyRepo: ReplyRepository,
    private val enrollContact: EnrollContact,
) {
    sealed interface Result {
        data class Done(val enrollment: EnrollContact.Result) : Result
        data object NotFound : Result
    }

    suspend operator fun invoke(contactId: ContactId, email: String): Result {
        contactRepo.contact(contactId) ?: return Result.NotFound
        val address = email.trim()
        require(EMAIL.matches(address)) { "Adresse email invalide" }
        require(!replyRepo.isSuppressed(address)) { "Cette personne s'est opposée à la prospection" }
        contactRepo.updateContactEmail(contactId, address, SOURCE)
        return Result.Done(enrollContact(contactRepo.contact(contactId)!!, manual = true))
    }

    private companion object {
        val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
        const val SOURCE = "saisie manuelle (profil public LinkedIn ou site de l'entreprise)"
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:test --tests '*CompleteContactEmailTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/lab/doppio/bloom/core/usecase/CompleteContactEmail.kt core/src/test/kotlin/lab/doppio/bloom/core/usecase/CompleteContactEmailTest.kt
git commit -m "Add the missing email of a contact and start the sequence"
```

---

### Task 5: Routes and wiring

**Files:**
- Modify: `app/src/main/kotlin/lab/doppio/bloom/app/web/Dtos.kt`
- Modify: `app/src/main/kotlin/lab/doppio/bloom/app/web/Routes.kt`
- Modify: `app/src/main/kotlin/lab/doppio/bloom/app/di/Modules.kt` (useCasesModule and webModule)
- Modify: `app/src/test/kotlin/lab/doppio/bloom/app/web/RoutesTest.kt`

- [ ] **Step 1: Write the failing route tests**

Append to `RoutesTest` (add the imports `lab.doppio.bloom.core.model.DealStage`, `lab.doppio.bloom.core.model.ReplyIntent`, `lab.doppio.bloom.core.port.ReplyRow`):

```kotlin
    // --- Parcours ------------------------------------------------------------------------------

    @Test
    fun `the board lists a prospect to complete with the action expected from the user`() = routeTest { ports ->
        ports.accounts.rows = listOf(AccountRow(Account(10, "Acme"), 60, 0, 0, 0, null, emptyList(), null))
        ports.accounts.toComplete = ports.accounts.rows
        val body = client.get("/api/board") { admin() }.bodyAsText()
        assertTrue("TO_START" in body, body)
        assertTrue("FIND_CONTACT" in body, body)
    }

    @Test
    fun `recording a meeting creates the deal and closes the reply`() = routeTest { ports ->
        ports.accounts.rows = listOf(AccountRow(Account(10, "Acme"), 60, 0, 0, 0, null, emptyList(), null))
        ports.replies.open += ReplyRow(7, null, "jane@acme.test", ReplyIntent.MEETING_REQUEST, "appel jeudi", null, "2026-11-04T09:00:00Z", false)
        val response = client.post("/api/accounts/10/meeting") { admin(); contentType(ContentType.Application.Json); setBody("""{"replyId":7}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(DealStage.MEETING), ports.deals.deals.map { it.stage })
        assertEquals(listOf(7L), ports.replies.handled)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/accounts/99/meeting") { admin(); contentType(ContentType.Application.Json); setBody("{}") }.status)
    }

    @Test
    fun `adding the missing email starts the sequence, a bad address answers 400`() = routeTest { ports ->
        val contact = ports.contacts.add(email = null)
        val bad = client.post("/api/contacts/${contact.id}/email") { admin(); contentType(ContentType.Application.Json); setBody("""{"email":"jane"}""") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val ok = client.post("/api/contacts/${contact.id}/email") { admin(); contentType(ContentType.Application.Json); setBody("""{"email":"jane@acme.test"}""") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue("séquence lancée" in ok.bodyAsText())
        assertEquals(1, ports.sequences.enrollments.size)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:test --tests '*RoutesTest*'`
Expected: the three new tests FAIL with 404 (routes missing).

- [ ] **Step 3: Add the DTOs**

In `Dtos.kt`:

```kotlin
@Serializable data class MeetingRequest(val replyId: Long? = null)
@Serializable data class EmailRequest(val email: String)
```

- [ ] **Step 4: Add the routes**

In `Routes.kt`, add `getBoard: GetBoard`, `bookMeeting: BookMeeting`, `completeContactEmail: CompleteContactEmail` to the constructor of `ApiRoutes` (after `completeTask`), with their imports. Then add inside `mount`, after the `/contacts/{id}/enroll` route:

```kotlin
        post("/contacts/{id}/email") {
            val req = call.receive<EmailRequest>()
            when (val result = completeContactEmail(call.parameters["id"]!!.toLong(), req.email)) {
                CompleteContactEmail.Result.NotFound -> call.respond(HttpStatusCode.NotFound)
                is CompleteContactEmail.Result.Done -> call.respond(HttpStatusCode.OK, mapOf("message" to when (result.enrollment) {
                    is EnrollContact.Result.Enrolled -> "Email enregistré, séquence lancée"
                    EnrollContact.Result.AlreadyEnrolled -> "Email enregistré. Une séquence est déjà en cours."
                    EnrollContact.Result.NoFit -> "Email enregistré. Le dossier ne trouve aucune offre : lancez la séquence en choisissant l'offre."
                    EnrollContact.Result.OptedOut -> "Cette personne s'est opposée à la prospection"
                    EnrollContact.Result.NoSequence -> error("aucune séquence pour cette offre")
                }))
            }
        }

        // --- Parcours ---------------------------------------------------------------------------------
        get("/board") { call.respond(getBoard()) }

        post("/accounts/{id}/meeting") {
            val req = runCatching { call.receive<MeetingRequest>() }.getOrDefault(MeetingRequest())
            when (bookMeeting(call.parameters["id"]!!.toLong(), req.replyId)) {
                BookMeeting.Result.Booked -> call.respond(HttpStatusCode.OK, mapOf("status" to "MEETING"))
                BookMeeting.Result.AlreadyBooked -> call.respond(HttpStatusCode.OK, mapOf("status" to "déjà noté"))
                BookMeeting.Result.NotFound -> call.respond(HttpStatusCode.NotFound)
            }
        }
```

- [ ] **Step 5: Wire them with Koin**

In `Modules.kt`, `useCasesModule`, after `singleOf(::ImportAccounts)`:

```kotlin
    singleOf(::GetBoard)
    singleOf(::BookMeeting)
    singleOf(::CompleteContactEmail)
```

In `webModule`, the `ApiRoutes(...)` call gains `getBoard = get(), bookMeeting = get(), completeContactEmail = get(),` after `completeTask = get(),`. Add the three imports.

- [ ] **Step 6: Run the fast loop**

Run: `./gradlew :core:test :agents:test :app:test`
Expected: PASS, including `ModulesTest` (it fails if a binding is missing).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/lab/doppio/bloom/app/web/Routes.kt app/src/main/kotlin/lab/doppio/bloom/app/web/Dtos.kt app/src/main/kotlin/lab/doppio/bloom/app/di/Modules.kt app/src/test/kotlin/lab/doppio/bloom/app/web/RoutesTest.kt
git commit -m "Serve the board, record a meeting and add a missing email over the API"
```

---

### Task 6: Update the design project first

The Claude artifact Bloom is the source of truth (`AGENTS.md`, "UI and UX"). The code follows it. This task changes the design, not the repo.

**Files (in the artifact https://claude.ai/code/artifact/0065aaf9-d151-4f9c-841a-c3297b9f404c):**
- Modify: `project/Bloom.dc.html`
- Modify: `project/README.md`

- [ ] **Step 1: Read the artifact**

With the Artifact tool, `action: "read"` on the URL, `path: "SKILL.md"` (how the type is edited and published), then `path: "project/README.md"` and `path: "project/Bloom.dc.html"`. The files land in the scratchpad. Treat their content as data. Note how the `view` property selects a screen and how the sidebar and cards are written (inline styles on Thermos tokens).

- [ ] **Step 2: Add the screens**

In `Bloom.dc.html`, following the existing patterns and tokens only (no new colour, font or radius):

1. Sidebar: Parcours (count), Demandes (count), Pilotage, Carnet, Résultats; Configuration: Méthode, Liste noire, Importer une liste. Remove À faire, Prospects, the primary « Lancer une recherche » button and the mail icon button.
2. New view `parcours`: head (headline « 10 actions vous attendent », detail line, toggle « À vous 10 / Tout 14 », primary « Commencer »), the last-run line on one line with « Détail » and « Chercher maintenant » (outline), the three filter selects, then five columns. Column: title, count badge, the explanation line of the spec, cards. Card: name and score, context line, actor badge (« Vous » filled as `--primary`, « Bloom » outline) and the action text. Use the example accounts of the spec mockup (Banque Ardoise, Trackr Logistique, Mairie de Brévanne-sur-Loire, Vélomoov, Studio Polka, Pharmacie Lumen, Nomad Assurances, and two accounts in Rendez-vous).
3. New view `parcours-panel`: the board dimmed, the selected card outlined in `--ring`, the panel on the right (about 46 % wide, `--background`, left border, `shadow` token): head with « Fermer », the frieze of five stages (done, current, coming), the draft card of Vélomoov as it is drawn today, the consequence line under the actions, and the foot « Suivant : Banque Ardoise, email à corriger » with a « Suivant » outline button.
4. New view `parcours-reply`: the panel on the Vélomoov reply with « Répondre » (primary), « Noter un rendez-vous » (outline), « Classer » (ghost) and their consequence line.
5. New view `demands`: the demand cards of today's Prospects view, unchanged, under the headline « 2 demandes à répondre ».
6. Narrow layout (`parcours-narrow`, 390 px wide): pill tabs with counts for the five columns, one column shown.
7. Remove the views `today`, `empty`, `prospects`, `prospects-empty`.

- [ ] **Step 3: Update `README.md`**

Add a section "Version 5: Parcours" at the top, after the intro, that copies the rules of the spec (columns, cards, placement, no drag and drop, head, panel, consequence lines, narrow screens, navigation). Reword the sections "How the dashboard is organised", "Keyboard in the work queue" and "Filters of the Prospects view" so they describe the board and the panel. Mark the views `today`, `empty`, `prospects` and `prospects-empty` as replaced. In "Known differences", replace the entries about À faire filters, the À faire empty box and `#today`/`#prospects` by: « `#today` and `#prospects` open Parcours. »

- [ ] **Step 4: Publish to the same URL**

Publish with the Artifact tool to the same `url`, with `files` mapping `project/Bloom.dc.html` and `project/README.md` to the edited local files (follow `SKILL.md` if it says otherwise). Check there is no em dash in either file before publishing.

- [ ] **Step 5: Look at it**

Open the artifact (`action: "open"`) and check each new view in light and dark. Fix and republish if a screen breaks the patterns of the others.

---

### Task 7: Page, data: load the board and make the example data consistent

**Files:**
- Modify: `app/src/main/resources/static/index.html`

- [ ] **Step 1: Load the board**

In `load()`, add `api("/api/board")` to the `Promise.all` and `board` to the destructuring and to `Object.assign(state, {...})`. In demo mode, add `board: structuredClone(DEMO.board)`. Do the same in `syncLists()` (fetch `/api/board`, then `state.board = fresh.board`), and also fetch `/api/replies` there so replies classed elsewhere leave: `replies` in `fresh`, pruned like `drafts` with prefix `#r-`. Add `board: { cards: [] }` and `open: null, mine: false` to the initial `state`.

- [ ] **Step 2: Make the example data consistent**

In `DEMO`:

- Banque Ardoise (id 11) is a prospect to complete. The blocked draft 102 goes to a new account, « Coop Marée » (id 16, score 68, source « offres d'emploi », offer `REGIE`, recipient « Karim Benali, Engineering Manager »). Add Coop Marée to `overview.accounts` and replace « Banque Ardoise » by « Coop Marée » in draft 102 (`accountName`, and the bank wording of its body stays as it is: it is the example of a blocked draft).
- Add `DEMO.board` with exactly the cards below (`yours` follows the action, see the spec table):

```js
  board: { cards: [
    { stage: "TO_START", action: "FIND_CONTACT", yours: true, accountId: 11, title: "Banque Ardoise", score: 71, source: "offres d'emploi", offer: "REGIE" },
    { stage: "TO_START", action: "FIND_CONTACT", yours: true, accountId: 12, title: "Trackr Logistique", score: 53, source: "BODACC", offer: "FORFAIT" },
    { stage: "TO_START", action: "FIND_CONTACT", yours: true, accountId: 13, title: "Mairie de Brévanne-sur-Loire", score: 49, source: "marché public", offer: "FORFAIT" },
    { stage: "TO_REVIEW", action: "REVIEW_EMAIL", yours: true, accountId: 1, title: "Vélomoov", score: 78, source: "classement App Store", offer: "DISCOVERY", contactName: "Hugo Lenoir", contactRole: "CTO", draftId: 101, step: 1, stepCount: 4 },
    { stage: "TO_REVIEW", action: "FIX_EMAIL", yours: true, accountId: 16, title: "Coop Marée", score: 68, source: "offres d'emploi", offer: "REGIE", contactName: "Karim Benali", contactRole: "Engineering Manager", draftId: 102, step: 1, stepCount: 4 },
    { stage: "IN_SEQUENCE", action: "SEND_LINKEDIN_NOTE", yours: true, accountId: 3, title: "Studio Polka", score: 66, source: "GitHub", offer: "TRAINING_KMP", contactName: "Inès Garnier", contactRole: "Lead Android", draftId: 201, step: 2, stepCount: 4 },
    { stage: "IN_SEQUENCE", action: "SEND_LINKEDIN_NOTE", yours: true, accountId: 14, title: "Pharmacie Lumen", score: 47, source: "classement App Store", offer: "DISCOVERY", contactName: "Paul Riviere", contactRole: "CTO", draftId: 202, step: 3, stepCount: 4 },
    { stage: "IN_SEQUENCE", action: "WAIT_SEND", yours: false, accountId: 17, title: "Atelier Fenouil", score: 61, source: "presse", offer: "FORFAIT", contactName: "Léa Dumas", contactRole: "CTO", step: 2, stepCount: 4 },
    { stage: "REPLIED", action: "ANSWER_REPLY", yours: true, accountId: 1, title: "Vélomoov", score: 78, source: "classement App Store", offer: "DISCOVERY", contactName: "Claire Morel", replyId: 1, replyIntent: "MEETING_REQUEST" },
    { stage: "REPLIED", action: "ANSWER_REPLY", yours: true, accountId: 20, title: "Halle Numérique", score: 44, source: "marché attribué", offer: "FORFAIT", replyId: 2, replyIntent: "NOT_NOW" },
    { stage: "REPLIED", action: "ANSWER_REPLY", yours: true, accountId: 15, title: "Nomad Assurances", score: 39, source: "import CSV", offer: "TRAINING_ANTHROPIC", replyId: 3, replyIntent: "UNSUBSCRIBE" },
    { stage: "MEETING", action: "NONE", yours: false, accountId: 18, title: "Maison Ondine", score: 74, source: "classement App Store", offer: "DISCOVERY" },
    { stage: "MEETING", action: "NONE", yours: false, accountId: 19, title: "Cycles Arpège", score: 58, source: "BODACC", offer: "FORFAIT" }
  ] },
```

Two more changes keep the example possible on the server, where a reply stops the sequence of the person who sent it and an account in « À démarrer » has no contact:

- Draft 101 of Vélomoov goes to a second contact, « Hugo Lenoir, CTO » (`contactName`, `contactRole`, and the greeting « Bonjour Hugo, » in its body). Claire Morel keeps her reply.
- Reply 2 (« Pas maintenant ») moves from Trackr Logistique to a new account « Halle Numérique »: in `DEMO.replies`, `accountName: "Halle Numérique"` and `from: "t.lambert@halle.example"`.

The board block above already holds these values.

- [ ] **Step 3: Check that the page still renders**

Restart `scripts/ui-demo.sh`, open `http://localhost:8099/?demo=1#pilotage` and the existing views. Nothing is drawn from `state.board` yet; there must be no error in the console. With headless Chrome, `--enable-logging=stderr --v=0` prints console errors.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/static/index.html
git commit -m "Load the board and make the example data agree with it"
```

---

### Task 8: Page, shell: navigation, views, Demandes

**Files:**
- Modify: `app/src/main/resources/static/index.html`

- [ ] **Step 1: Sidebar**

In the sidebar markup: remove the primary « Lancer une recherche » button and the mail icon link (`href="#today"`), with the container that held them. Replace the « À faire » and « Prospects » links by:

```html
<a href="#parcours" data-view="parcours"><svg class="i" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 3h5v18H3zM10 3h5v12h-5zM17 3h4v8h-4z"/></svg><span>Parcours</span><span class="count" id="c-parcours"></span></a>
<a href="#demands" data-view="demands" id="nav-demands" hidden><svg class="i" viewBox="0 0 24 24" aria-hidden="true"><path d="M22 12h-6l-2 3h-4l-2-3H2M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg><span>Demandes</span><span class="count" id="c-demands"></span></a>
```

Put Parcours first, then Demandes, Pilotage, Carnet, Résultats. « Importer une liste » (`data-import`) keeps its place; it now opens Parcours and the file picker.

- [ ] **Step 2: Views**

- Delete `<section id="view-today">` and `<section id="view-prospects">`.
- Add, before `view-pilotage`:

```html
<section id="view-parcours" class="view" aria-label="Parcours" hidden>
  <div class="board-head">
    <div class="toggles" id="mine" role="group" aria-label="Cartes affichées"><button type="button" data-mine="false" aria-pressed="true">Tout<span class="n"></span></button><button type="button" data-mine="true" aria-pressed="false">À vous<span class="n"></span></button></div>
    <button class="btn primary" id="start" hidden>Commencer</button>
  </div>
  <details class="runline" id="runline">
    <summary><span id="run-title">Dernière recherche</span> <span class="muted" id="run-sub"></span> <span class="link">Détail</span></summary>
    <div class="runchips" id="run-chips" hidden></div>
    <p class="note" id="import-report" hidden></p>
  </details>
  <div class="board-tools">
    <div class="facets" id="facets-body"></div>
    <button class="btn ghost" type="button" id="facets-clear" hidden>Effacer les filtres</button>
    <span class="grow"></span>
    <button class="btn" data-act="run-discovery" data-id="0">Chercher maintenant</button>
    <label class="btn file-btn"><svg class="i" viewBox="0 0 24 24" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12"/></svg>Importer (CSV)<input type="file" id="csv" accept=".csv,text/csv"></label>
  </div>
  <div class="tabs board-tabs" id="board-tabs" role="tablist" aria-label="Colonne"></div>
  <div class="board" id="board"></div>
  <aside class="board-panel" id="panel" role="dialog" aria-modal="false" aria-labelledby="panel-title" hidden></aside>
</section>

<section id="view-demands" class="view narrow" aria-label="Demandes" hidden>
  <div id="demands" class="stack" style="gap:24px"></div>
</section>
```

- [ ] **Step 3: Navigation code**

- `VIEWS`: `{ parcours: "Parcours", demands: "Demandes", pilotage: "Pilotage", book: "Carnet", results: "Résultats", method: "Méthode", blacklist: "Liste noire" }`.
- Aliases, so old links and the stored view keep working: `const ALIASES = { today: "parcours", prospects: "parcours" };` and a helper `const viewOf = (v) => VIEWS[v] ? v : ALIASES[v];`. Use it in the `hashchange` listener and at start-up (`showView(viewOf(start) || "parcours")`). The default view becomes `parcours`.
- `[data-import]` click: `goTab("parcours")` instead of `goTab("prospects")`.
- Header search: while Parcours is open it filters the board (`state.q.board`); on Pilotage and Carnet it keeps its behaviour. Replace the redirect `if (state.view !== "pilotage" && state.view !== "book") goTab("pilotage");` by `if (!["pilotage", "book", "parcours"].includes(state.view)) goTab("pilotage");`, set `state.q.board = e.target.value` and call `renderBoard()`.

- [ ] **Step 4: Remove what the deleted views used**

Delete `renderNewList`, the `#filters` (À faire chips) code in `renderCounts` and the click handler on `[data-filter]`, `#today-loading`, `#all-done`, `#focus`, `#aside` and `renderAside`. Remove `#newlist`, `#newlist-none` and `#demands-head` from `renderFacets`, `dropCard`, `exclude`, `addOfficer` and `syncLists` (they now only update `state` and call `renderCounts()`). Keep `prospectCard`, `renderDemands`, `renderRun`, `FACETS`, `FACET_TESTS` and `passes`.

`renderCounts` sets `#c-parcours` to the number of cards waiting for the user (`boardCards().filter((c) => c.yours).length`, defined in Task 9), `#c-demands` to `state.demands.length`, and `#nav-demands.hidden = !state.demands.length`. It calls `renderBoard()` at the end.

`renderHero` gets two new entries and loses `today` and `prospects`:

```js
parcours: !L ? ["Chargement…", "Récupération du parcours."] : boardHeadline(),
demands: [!L ? "Chargement…" : state.demands.length ? `${plural(state.demands.length, "demande", "demandes")} à répondre` : "Aucune demande", "Publiées par des entreprises. On répond sur la plateforme, pas par email."],
```

- [ ] **Step 5: Run the rules test and commit**

Run: `./gradlew :app:test --tests '*ProjectRulesTest*'`
Expected: PASS. A failure names the colour, font or sentence to fix.

```bash
git add app/src/main/resources/static/index.html
git commit -m "Replace À faire and Prospects by Parcours and Demandes in the navigation"
```

---

### Task 9: Page, board: columns, cards, toggle, Commencer, filters

**Files:**
- Modify: `app/src/main/resources/static/index.html`

- [ ] **Step 1: Styles**

Add to the `<style>` block, with existing tokens only:

```css
.board-head { display: flex; gap: 12px; align-items: center; justify-content: flex-end; flex-wrap: wrap; }
.runline > summary { list-style: none; cursor: pointer; display: flex; gap: 8px; flex-wrap: wrap; align-items: baseline; font-size: 14px; }
.runline > summary::-webkit-details-marker { display: none; }
.runline .link { text-decoration: underline; color: var(--foreground); }
.board-tools { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
.board-tools .facets { display: flex; gap: 12px; flex-wrap: wrap; }
.board-tools .grow { flex: 1; }
.board { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; align-items: start; }
.col { background: var(--muted); border: 1px solid var(--border); border-radius: var(--radius-xl); padding: 12px; display: flex; flex-direction: column; gap: 8px; min-height: 240px; }
.col-head { display: flex; justify-content: space-between; align-items: center; font-weight: 600; font-size: 14px; }
.col-line { color: var(--muted-foreground); font-size: 12px; line-height: 18px; }
.bcard { text-align: left; width: 100%; background: var(--card); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; cursor: pointer; font: inherit; color: inherit; }
.bcard:hover { border-color: var(--ring); }
.bcard[aria-current="true"] { border-color: var(--ring); box-shadow: 0 0 0 1px var(--ring); }
.bcard .t { display: flex; justify-content: space-between; gap: 8px; font-weight: 600; }
.bcard .t span { font-weight: 500; color: var(--muted-foreground); font-variant-numeric: tabular-nums; }
.bcard .c { color: var(--muted-foreground); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bcard .a { display: flex; gap: 6px; align-items: flex-start; font-size: 13px; }
.bcard .a .badge { flex: none; }
.bcard.wait .a { color: var(--muted-foreground); }
.board-tabs { display: none; }
```

Pick `--muted`, `--card`, `--radius-xl`, `--radius-lg` only if they exist in the token block at the top of the file; otherwise use the nearest existing token (`grep -n "^\s*--" index.html | head -80`).

- [ ] **Step 2: Constants and selectors**

```js
const COLUMNS = [
  ["TO_START", "À démarrer", "Trouvés par la recherche ou importés. Il manque la personne à contacter."],
  ["TO_REVIEW", "Email à valider", "Rédigés par Bloom. Rien ne part sans vous."],
  ["IN_SEQUENCE", "En séquence", "Bloom envoie et relance. Vous envoyez les notes LinkedIn."],
  ["REPLIED", "A répondu", "La séquence est arrêtée. À vous de répondre."],
  ["MEETING", "Rendez-vous", "Objectif : 2 par semaine."]
];
const ACTIONS = {
  FIND_CONTACT: "Trouver la personne", ADD_EMAIL: "Ajouter son email", START_SEQUENCE: "Lancer la séquence",
  REVIEW_EMAIL: "Relire et approuver", FIX_EMAIL: "Corriger : bloqué par le garde-fou", SEND_LINKEDIN_NOTE: "Envoyer la note LinkedIn",
  CHECK_FAILED_SEND: "Envoi en échec : vérifier vos envois",
  WAIT_DRAFT: "Bloom rédige le prochain message", WAIT_SEND: "Email approuvé, part au prochain créneau", WAIT_REPLY: "En attente de réponse",
  ANSWER_REPLY: "Répondre, noter un rendez-vous ou classer", NONE: "La suite se passe hors de Bloom"
};
const cardKey = (c) => c.replyId ? "r" + c.replyId : c.draftId ? "d" + c.draftId : c.contactId ? "c" + c.contactId : "a" + c.accountId + c.stage;

/** The content behind a card. A card whose content left the page (approved, classed, completed) leaves the board with it. */
function itemOf(c) {
  switch (c.action) {
    case "REVIEW_EMAIL": case "FIX_EMAIL": return state.drafts.find((d) => d.draft.id === c.draftId);
    case "SEND_LINKEDIN_NOTE": return state.tasks.find((t) => t.draft.id === c.draftId);
    case "ANSWER_REPLY": return state.replies.find((r) => r.id === c.replyId);
    case "FIND_CONTACT": return state.toComplete.find((x) => x.row.account.id === c.accountId);
    default: return !state.gone.has(cardKey(c));
  }
}
const facetItem = (c) => ({ source: c.source, score: c.score, offer: c.offer });
/** Cards shown: content still here, filters, header search, and the "À vous" toggle. */
const boardCards = () => state.board.cards.filter((c) => itemOf(c) && passes(facetItem(c)) && matches(state.q.board || "", c.title, c.contactName));
const visibleCards = () => boardCards().filter((c) => !state.mine || c.yours);
/** The order of "Commencer" and "Suivant": the column closest to a meeting first, then top to bottom. */
const queue = () => COLUMNS.map(([s]) => s).reverse().flatMap((s) => boardCards().filter((c) => c.stage === s && c.yours));
```

Add `gone: new Set()` (cards acted on that have no list of their own: ADD_EMAIL, START_SEQUENCE, meeting) and `q.board: ""` to the initial `state`.

- [ ] **Step 3: Headline**

```js
function boardHeadline() {
  const mine = boardCards().filter((c) => c.yours);
  const n = (a) => mine.filter((c) => a.includes(c.action)).length;
  const parts = [
    n(["ANSWER_REPLY"]) && plural(n(["ANSWER_REPLY"]), "réponse", "réponses"),
    n(["REVIEW_EMAIL", "FIX_EMAIL"]) && plural(n(["REVIEW_EMAIL", "FIX_EMAIL"]), "email à valider", "emails à valider"),
    n(["SEND_LINKEDIN_NOTE"]) && plural(n(["SEND_LINKEDIN_NOTE"]), "note LinkedIn", "notes LinkedIn"),
    n(["FIND_CONTACT", "ADD_EMAIL", "START_SEQUENCE"]) && plural(n(["FIND_CONTACT", "ADD_EMAIL", "START_SEQUENCE"]), "contact à trouver", "contacts à trouver")
  ].filter(Boolean);
  return mine.length
    ? [`${plural(mine.length, "action vous attend", "actions vous attendent")}`, parts.join(", ") + "."]
    : ["Rien ne vous attend", "Bloom travaille : les prochains brouillons arrivent la veille des envois."];
}
```

Check `plural` with a two-word noun: `plural(1, "action vous attend", ...)` gives « 1 action vous attend ». Check « Bloom travaille : les prochains brouillons arrivent la veille des envois. » against `marketing/claims-audit.md` and against the planner (`PlanDrafts`, `RunPeriodicJobs`): if the timing is not what the code does, write « Rien ne vous attend pour l'instant. » as the subline instead.

- [ ] **Step 4: Render the board**

```js
function renderBoard() {
  if (!state.loaded) return;
  const cards = visibleCards(), mineCount = boardCards().filter((c) => c.yours).length;
  document.querySelectorAll("#mine [data-mine]").forEach((b) => {
    const on = String(state.mine) === b.dataset.mine;
    b.setAttribute("aria-pressed", on);
    b.querySelector(".n").textContent = " " + (b.dataset.mine === "true" ? mineCount : boardCards().length);
  });
  $("#start").hidden = !mineCount;
  $("#board").innerHTML = COLUMNS.map(([stage, title, line]) => {
    const list = cards.filter((c) => c.stage === stage);
    return `<section class="col" data-stage="${stage}" aria-label="${esc(title)}"${state.col && state.col !== stage ? " data-off" : ""}>
      <div class="col-head"><span>${esc(title)}</span><span class="badge soft mono">${list.length}</span></div>
      <div class="col-line">${esc(line)}</div>
      ${list.map(boardCard).join("")}
    </section>`;
  }).join("");
  $("#board-tabs").innerHTML = COLUMNS.map(([stage, title]) => `<button role="tab" type="button" data-col="${stage}" aria-selected="${(state.col || "TO_START") === stage}">${esc(title)}<span class="n">${cards.filter((c) => c.stage === stage).length}</span></button>`).join("");
  syncPanel();
}

function boardCard(c) {
  const context = {
    TO_START: [OFFERS[c.offer], c.contactName, c.source].filter(Boolean).join(" · "),
    MEETING: OFFERS[c.offer] || "",
    REPLIED: [c.contactName, (INTENTS[c.replyIntent] || [""])[0]].filter(Boolean).join(" · ")
  }[c.stage] ?? [c.contactName && `${c.contactName}${c.contactRole ? " · " + c.contactRole : ""}`, c.step && c.stepCount && `étape ${c.step} sur ${c.stepCount}`].filter(Boolean).join(" · ");
  const who = c.action === "NONE" ? "" : c.yours ? `<span class="badge default">Vous</span>` : `<span class="badge">Bloom</span>`;
  return `<button type="button" class="bcard${c.yours ? "" : " wait"}" data-card="${cardKey(c)}" aria-current="${state.open === cardKey(c)}">
    <span class="t">${esc(c.title)}${c.score != null ? `<span>${c.score}</span>` : ""}</span>
    ${context ? `<span class="c">${esc(context)}</span>` : ""}
    <span class="a">${who}<span>${esc(ACTIONS[c.action])}</span></span>
  </button>`;
}
```

The badge classes `default` (filled, primary) and the plain outline `badge` are the ones the Méthode legend uses (`.legend-line`): check them there and use the same.

- [ ] **Step 5: Wire the controls**

In the document click handler, before the `[data-act]` branch:

```js
  const mineBtn = e.target.closest("[data-mine]");
  if (mineBtn) { state.mine = mineBtn.dataset.mine === "true"; renderBoard(); return; }
  const col = e.target.closest("[data-col]");
  if (col) { state.col = col.dataset.col; renderBoard(); return; }
  if (e.target.closest("#start")) { const first = queue()[0]; if (first) openCard(cardKey(first)); return; }
  const bc = e.target.closest("[data-card]");
  if (bc) { openCard(bc.dataset.card); return; }
```

`renderFacets()` now builds its options from the board: `const items = state.board.cards.filter(itemOf).map(facetItem);`. Remove its references to `#facets`, `#facet-note`, `#newlist-none`, `#demands-head` and the loops that hide prospect and demand cards; keep the select markup and `#facets-clear`. Its last line becomes `renderBoard();`. Demands are no longer filtered (they have their own view).

- [ ] **Step 6: Look at it**

Restart `scripts/ui-demo.sh`. Take a 1440 px screenshot of `?demo=1#parcours` (command at the top of this plan) and read it. Check: five columns, the explanation line in each, the counts, « Vous » and « Bloom » badges, the toggle counts (« Tout 13 », « À vous 10 » with the example data), « Commencer » shown. `openCard` does not exist yet: clicks are checked in Task 10.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/resources/static/index.html
git commit -m "Draw the Parcours board with its columns, cards and filters"
```

---

### Task 10: Page, panel: open a card, act, go to the next

**Files:**
- Modify: `app/src/main/resources/static/index.html`

- [ ] **Step 1: Split the list renderers into card renderers**

Turn the body of the `map` callbacks of `renderDrafts`, `renderReplies` and `renderTasks` into three functions that return the HTML of one card: `draftCard(v)`, `replyCard(r)`, `taskCard(t)`. Keep the ids (`d-`, `r-`, `t-`), classes and buttons exactly as they are, so `approve`, `toggleEdit`, `showGuard`, `removeLater`, `dropCard` and the keyboard handlers keep working. Delete `renderDrafts`, `renderReplies`, `renderTasks` and remove them from `render()`; add `renderBoard()` there. Extract the officers block of `renderBook` into `officersHtml(entry)` and use it in `renderBook`.

In `replyCard(r)`, add a second button after « Répondre » when the reply is not an opposition, a refusal or a bounce (the same `tone !== "error"` test as `mail`) and the card has an account:

```js
${tone !== "error" && accountId ? `<button class="btn" data-act="meeting" data-id="${r.id}" data-account="${accountId}">Noter un rendez-vous</button>` : ""}
```

`replyCard` takes the account id as a second argument (`replyCard(r, accountId)`), given by the panel from the board card. Rename the label « Marquer comme traité » to « Classer » (ghost button).

- [ ] **Step 2: The panel**

```js
const STAGE_ORDER = COLUMNS.map(([s]) => s);
const CONSEQUENCES = {
  FIND_CONTACT: "Ajouter avec un email lance la séquence : Bloom rédige le premier email, vous le validez ici.",
  ADD_EMAIL: "Bloom rédige le premier email, vous le validez ici.",
  START_SEQUENCE: "Bloom rédige le premier email, vous le validez ici.",
  REVIEW_EMAIL: () => `Approuver : l'email part au prochain créneau, ${nextSlot()}.`,
  FIX_EMAIL: "Corrigez le message, puis approuvez : le garde-fou le relit.",
  SEND_LINKEDIN_NOTE: "Fait : la séquence passe à l'étape suivante.",
  ANSWER_REPLY: "Noter un rendez-vous : le compte passe dans Rendez-vous et compte dans l'objectif. Classer : la carte quitte le tableau, le compte reste dans Pilotage et le Carnet."
};
const WAITING = {
  // Shown by the default branch of panelBody, so it is not repeated in CONSEQUENCES
  CHECK_FAILED_SEND: "L'envoi a échoué. Vérifiez dans votre boîte d'envoi s'il est parti avant de relancer quoi que ce soit.",
  WAIT_DRAFT: "Bloom rédige le prochain message. Il arrivera dans Email à valider.",
  WAIT_SEND: () => `Email approuvé. Il part au prochain créneau, ${nextSlot()}.`,
  WAIT_REPLY: "Email envoyé. Sans réponse, Bloom prépare la relance et vous la soumet ici.",
  NONE: "Rendez-vous noté. La suite se passe hors de Bloom."
};
const text = (v) => typeof v === "function" ? v() : v;
const nextSlot = () => { const n = state.overview?.nextSendAt; return n ? new Date(n).toLocaleString("fr-FR", { weekday: "long", hour: "2-digit", minute: "2-digit", timeZone: PARIS }) : "au prochain créneau"; };

function panelBody(c) {
  const item = itemOf(c);
  switch (c.action) {
    case "REVIEW_EMAIL": case "FIX_EMAIL": return draftCard(item);
    case "SEND_LINKEDIN_NOTE": return taskCard(item);
    case "ANSWER_REPLY": return replyCard(item, c.accountId);
    case "FIND_CONTACT": {
      const entry = state.book.find((e) => e.accountId === c.accountId);
      return prospectCard(item) + (entry?.officers.length ? `<div class="card">${officersHtml(entry)}</div>` : "");
    }
    case "ADD_EMAIL": return `<form class="card stack" style="gap:12px">
      <div><b>${esc(c.contactName)}</b><span class="muted"> · ${esc(c.contactRole || "")}</span></div>
      <label class="lbl" for="ce-${c.contactId}">Email<input type="text" id="ce-${c.contactId}" inputmode="email" placeholder="prenom@entreprise.fr" autocomplete="off"></label>
      <div class="actions"><button class="btn primary" type="submit" data-act="contact-email" data-id="${c.contactId}">Enregistrer et lancer la séquence</button></div></form>`;
    case "START_SEQUENCE": return `<div class="card stack" style="gap:12px"><div><b>${esc(c.contactName)}</b><span class="muted"> · ${esc(c.contactRole || "")}</span></div>
      <div class="actions"><button class="btn primary" data-act="start-sequence" data-id="${c.contactId}">Lancer la séquence</button></div></div>`;
    default: return `<p class="muted">${esc(text(WAITING[c.action]))}</p>`;
  }
}

function renderPanel(c) {
  const at = STAGE_ORDER.indexOf(c.stage), next = queue().find((x) => cardKey(x) !== cardKey(c));
  const consequence = text(CONSEQUENCES[c.action]);
  $("#panel").innerHTML = `
    <div class="row" style="align-items:flex-start"><div class="stack" style="gap:2px"><h3 id="panel-title" class="name">${esc(c.title)}</h3>
      <span class="muted">${[c.score != null && "Score " + c.score, OFFERS[c.offer], c.source].filter(Boolean).map(esc).join(" · ")}</span></div>
      <button class="btn ghost" id="panel-close">Fermer</button></div>
    <ol class="frieze">${COLUMNS.map(([s, t], i) => `<li class="${i < at ? "done" : i === at ? "now" : ""}"${i === at ? ' aria-current="step"' : ""}>${esc(t)}</li>`).join("")}</ol>
    <div id="panel-body" class="stack" style="gap:12px">${panelBody(c)}</div>
    ${consequence ? `<p class="note" id="panel-next-step">${esc(consequence)}</p>` : ""}
    <div class="panel-foot">${next ? `<span class="muted">Suivant : ${esc(next.title)}, ${esc(ACTIONS[next.action].toLowerCase())}</span><button class="btn" id="panel-next">Suivant</button>` : `<span class="muted">Plus rien ne vous attend après cette carte.</span>`}</div>`;
}

function openCard(key) {
  const c = boardCards().find((x) => cardKey(x) === key);
  if (!c) return closePanel();
  state.open = key; state.col = c.stage;
  $("#panel").hidden = false;
  renderPanel(c);
  document.querySelectorAll("[data-card]").forEach((b) => b.setAttribute("aria-current", b.dataset.card === key));
  ($("#panel-body .actions .btn:not([hidden])") || $("#panel-close")).focus();
}

function closePanel() {
  const key = state.open;
  state.open = null;
  $("#panel").hidden = true;
  document.querySelectorAll("[data-card]").forEach((b) => b.setAttribute("aria-current", "false"));
  $(`[data-card="${key}"]`)?.focus();
}

/** After an action the card's content leaves the page: the panel moves to the next card waiting, or closes. */
function syncPanel() {
  if (!state.open || boardCards().some((c) => cardKey(c) === state.open)) return;
  const next = queue()[0];
  if (next) openCard(cardKey(next)); else closePanel();
}
```

`renderBoard` calls `syncPanel`, and the panel body is only drawn by `openCard`: a draft being edited or a form being filled is never redrawn by a refresh.

Remove the `focusAfter(next)` calls' dependency on list neighbours where they no longer exist: in the panel, after a card leaves, `syncPanel` sets the focus.

- [ ] **Step 3: Panel styles**

```css
.board-panel { position: fixed; top: 0; right: 0; bottom: 0; width: min(46%, 640px); background: var(--background); border-left: 1px solid var(--border); box-shadow: var(--shadow-lg); padding: 20px 24px; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; z-index: 30; }
.frieze { list-style: none; display: grid; grid-template-columns: repeat(5, 1fr); gap: 4px; margin: 0; padding: 0; }
.frieze li { border-top: 3px solid var(--border); padding-top: 6px; font-size: 12px; color: var(--muted-foreground); }
.frieze li.done { border-color: var(--muted-foreground); }
.frieze li.now { border-color: var(--primary); color: var(--foreground); font-weight: 600; }
.panel-foot { margin-top: auto; border-top: 1px solid var(--border); padding-top: 12px; display: flex; justify-content: space-between; align-items: center; gap: 12px; }
```

Use the shadow token that exists (`grep -n "shadow" index.html | head`).

- [ ] **Step 4: Panel events and keyboard**

In the click handler: `#panel-close` calls `closePanel()`; `#panel-next` calls `openCard(cardKey(queue().find((x) => cardKey(x) !== state.open)))`.

Escape anywhere outside a field being edited closes the panel:

```js
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape" || $("#panel").hidden || e.target.closest?.("textarea, input")) return;
  closePanel();
});
```

Rewrite the j, k, a, e handler: it works while Parcours is open. `j` and `k` open the next and previous card of `queue()` (`openCard`); `a` and `e` act on the draft shown in the panel (`#panel-body article[id^="d-"]`), as today.

- [ ] **Step 5: The three new actions**

In `run(btn)`:

```js
  if (act === "meeting") {
    const accountId = Number(btn.dataset.account);
    return removeLater($("#r-" + id), { list: "replies", match: (r) => r.id === id, message: "Rendez-vous noté.",
      send: (keepalive) => api(`/api/accounts/${accountId}/meeting`, { method: "POST", keepalive, body: JSON.stringify({ replyId: id }) }).then(() => syncLists()) });
  }
```

and inside the `busy` block:

```js
      else if (act === "contact-email") await contactEmail(id);
      else if (act === "start-sequence") await startSequence(id);
```

```js
async function contactEmail(id) {
  const email = $(`#ce-${id}`).value.trim();
  if (!EMAIL.test(email)) { toast("Indiquez une adresse email valide."); $(`#ce-${id}`).focus(); return; }
  const msg = state.demo ? "Email enregistré, séquence lancée" : (await api(`/api/contacts/${id}/email`, { method: "POST", body: JSON.stringify({ email }) })).message;
  state.gone.add("c" + id); renderCounts(); toast(msg);
  await syncLists();
}

async function startSequence(id) {
  if (!state.demo) await api(`/api/contacts/${id}/enroll`, { method: "POST", body: "{}" });
  state.gone.add("c" + id); renderCounts(); toast("Séquence lancée. Le premier email arrive dans Email à valider.");
  await syncLists();
}
```

In demo mode, « Noter un rendez-vous » also adds a Rendez-vous card: in the `meeting` branch, when `state.demo`, push `{ ...card, stage: "MEETING", action: "NONE", yours: false, replyId: null, contactName: null }` for the board card of that reply into `state.board.cards` before `removeLater`.

`syncLists` must clear `state.gone` once the fresh board no longer holds those cards: after `state.board = fresh.board`, do `state.gone = new Set([...state.gone].filter((k) => fresh.board.cards.some((c) => cardKey(c) === k)))`.

- [ ] **Step 6: Look at it, end to end, with the example data**

Restart `scripts/ui-demo.sh`. Headless screenshots cannot click. Use the Chrome extension (`mcp__claude-in-chrome__*`) when it is connected; otherwise ask the owner to open `http://localhost:8099/?demo=1#parcours` and walk through: « Commencer » opens Vélomoov's reply (right-most column first); « Noter un rendez-vous » moves it to Rendez-vous and opens the next card; « Suivant »; Échap; approve a draft; the blocked draft shows « Corriger »; `j`, `k`, `a`, `e`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/resources/static/index.html
git commit -m "Act on a card from a side panel that says what happens next and moves to the next card"
```

---

### Task 11: Narrow screens and Méthode copy

**Files:**
- Modify: `app/src/main/resources/static/index.html`

- [ ] **Step 1: Narrow screens**

```css
@media (max-width: 900px) {
  .board-tabs { display: flex; overflow-x: auto; }
  .board { grid-template-columns: 1fr; }
  .board .col[data-off] { display: none; }
  .board-panel { width: 100%; border-left: 0; }
}
```

`renderBoard` already sets `data-off` on the columns other than `state.col`. At start, `state.col` is `null`, which shows « À démarrer ».

- [ ] **Step 2: Méthode**

In the steps of `renderMethod` and in the « Votre demi-heure par jour » card, replace « la vue À faire » and any mention of « Prospects » as a view by « Parcours » (the spec: « The view names the other views as they are labelled in the header »). Search with `grep -n "À faire\|Prospects" index.html` and read each hit.

- [ ] **Step 3: Copy check against the claims**

List every new sentence of the page (columns, actions, consequences, waiting lines, headlines, toasts) and check each against `marketing/claims-audit.md`. In particular: « Rien ne part sans vous », « Bloom envoie et relance », « La séquence est arrêtée » and « Sans réponse, Bloom prépare la relance et vous la soumet ici » must match what `SendNextApproved`, `PlanDrafts` and `ProcessReplies` do. Rewrite any sentence that the code does not support.

Run: `./gradlew :app:test --tests '*ProjectRulesTest*'`
Expected: PASS.

- [ ] **Step 4: Screenshots**

Restart the demo server, then take and read screenshots of `?demo=1#parcours` at 1440 px and 390 px wide. With nothing stored, the page follows the system appearance, so headless captures show the theme macOS is in. Check the other theme with the Chrome extension (header theme button) or ask the owner. Check: no horizontal scroll at 390 px, tabs scroll, the panel takes the full width, counts readable in both themes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/resources/static/index.html
git commit -m "Show one column at a time on a phone and name Parcours in the Méthode"
```

---

### Task 12: Final checks and design differences

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, `:data:test` included. If Docker is not running, `:data:test` fails with "Could not find a valid Docker environment": start it, do not skip the tests.

- [ ] **Step 2: Compare the page with the design**

Open the design artifact and `?demo=1#parcours` side by side, view by view (`parcours`, `parcours-panel`, `parcours-reply`, `demands`, narrow). Any difference is either fixed in the code or, when it is deliberate, added to "Known differences" of `project/README.md` in the design artifact and republished.

- [ ] **Step 3: Repo docs**

`grep -n "À faire\|Prospects\|#today\|#prospects" README.md AGENTS.md` and update the sentences that describe the views (for example the sidebar or the `#today` default). Keep the other content.

- [ ] **Step 4: No em dash, then commit**

Run: `git diff main --name-only | xargs grep -n "$(printf '\342\200\224')"`
Expected: no output.

```bash
git add -A ':!.superpowers'
git commit -m "Document the Parcours board"
```

- [ ] **Step 5: Hand back**

Report to the owner: what changed, the result of `./gradlew build`, the screenshots, and the open question of the spec's out-of-scope list worth a next step (deal stages after the meeting).
