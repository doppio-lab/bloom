# Parcours: a kanban board as the main work view

Date: 12/11/2026. Status: implemented on 12/11/2026 (branch `parcours-kanban`). This document describes
what was built; the sentences that changed during the build are the ones the code could not keep true.

## Problem

The owner of Bloom reports three problems with the dashboard:

1. **Where to start.** The default view is Pilotage (figures and a chart). The work of the day is split
   between À faire (emails, replies, LinkedIn notes), Prospects (accounts with no contact, Codeur demands)
   and Carnet (contacts with no email), with no order.
2. **The journey is not visible.** Nothing shows that an account goes from discovery to a contact, to an
   email to approve, to a sequence, to a reply, to a meeting, or where a given account stands.
3. **Actions are unclear.** Buttons do not say what happens next ("Ajouter et lancer la séquence"), a state
   has no action ("Email manquant"), and nothing in the interface records a meeting: the Rendez-vous figure
   and the pipeline are fed by the API only.

Density of the cards was not reported as a problem and is out of scope.

## Decision

A kanban board named **Parcours** becomes the default view and replaces the À faire and Prospects views.
Each column is a step of the journey. A card says whose turn it is. A click on a card opens a side panel
with the content and the action of its step, so the work is done without leaving the board.

Choices made with the owner:

- Kanban rather than a guided daily list or a band of shortcuts on Pilotage.
- The board is the main work view, not an overview on top of the existing views.
- The board stops at the meeting. Proposal, amount and signature stay outside the interface.

## The board

### Columns

| Column (label) | Stage | Holds | Explanation line under the title |
| --- | --- | --- | --- |
| À démarrer | `TO_START` | Accounts whose sequence has not started | « Trouvés par la recherche ou importés. Aucune séquence n'a commencé. » |
| Email à valider | `TO_REVIEW` | Drafts waiting for review | « Rédigés par Bloom. Rien ne part sans vous. » |
| En séquence | `IN_SEQUENCE` | Running sequences | « Bloom envoie les emails validés et prépare les relances. Vous envoyez les notes LinkedIn. » |
| A répondu | `REPLIED` | Replies not handled yet | « La séquence est arrêtée, ou en pause pour une absence. À vous de répondre. » |
| Rendez-vous | `MEETING` | Accounts with a meeting recorded | « Objectif : 2 par semaine. » |

The explanation line stays when the column is empty, so the board explains the journey with no data.
Every sentence must hold in the code and stay inside the claims marked as proven in
`marketing/claims-audit.md`; the implementation checks each one before shipping it.

### Cards

One card per account and per person contacted: a sequence belongs to a person, so a company with two
people in a sequence has two cards. An account with no contact has one card.

A card shows: the account name and its score, one context line (the offer and the source in À démarrer,
the person and « étape N sur M » elsewhere, the intent of the reply in A répondu, the offer in
Rendez-vous), and the next action with an actor badge: **Vous** (filled badge) or **Bloom** (outline badge),
the same vocabulary as the Méthode view.

| Stage | Action (key) | Card text | Actor |
| --- | --- | --- | --- |
| `TO_START` | `FIND_CONTACT` | Trouver la personne | Vous |
| `TO_START` | `ADD_EMAIL` | Ajouter son email | Vous |
| `TO_START` | `START_SEQUENCE` | Lancer la séquence | Vous |
| `TO_REVIEW` | `REVIEW_EMAIL` | Relire et approuver | Vous |
| `TO_REVIEW` | `FIX_EMAIL` | Corriger : bloqué par le garde-fou | Vous |
| `IN_SEQUENCE` | `SEND_LINKEDIN_NOTE` | Envoyer la note LinkedIn | Vous |
| `IN_SEQUENCE` | `CHECK_FAILED_SEND` | Envoi en échec : vérifier vos envois | Vous |
| `IN_SEQUENCE` | `WAIT_DRAFT` | Bloom rédige le prochain message | Bloom |
| `IN_SEQUENCE` | `WAIT_SEND` | Email approuvé, part dès {date} (« part dès le prochain créneau » when the slot is unknown) | Bloom |
| `IN_SEQUENCE` | `WAIT_REPLY` | En attente de réponse | Bloom |
| `REPLIED` | `ANSWER_REPLY` | Répondre, noter un rendez-vous ou classer (« Classer » for an opposition, a "not interested" or a bounce) | Vous |
| `MEETING` | `NONE` | La suite se passe hors de Bloom | none |

Inside a column, cards that wait for the user come first, then by score, highest first.

### Rules that place a card (server)

Blacklisted accounts never appear. For the others:

1. **Meeting.** An account with a deal in stage `MEETING`, `DISCOVERY_SOLD` or `PROPOSAL` has exactly one
   card, in Rendez-vous, and no other card. The card shows the offer of the deal; with several such deals,
   the one at the most advanced stage. Best score first, 20 at most (a deal carries no date).
2. **Reply.** An open reply gives one card in A répondu. When the reply belongs to no known contact, the
   card is titled with the sender address and has no account.
3. **Running sequence.** An `ACTIVE` enrollment (what `activeEnrollments()` returns) whose contact has no
   open reply gives one card:
   - a draft `PENDING_REVIEW`: Email à valider, `FIX_EMAIL` when the guard blocks it, else `REVIEW_EMAIL`;
   - an approved draft on a channel other than email: En séquence, `SEND_LINKEDIN_NOTE`;
   - an approved or sending email: En séquence, `WAIT_SEND`;
   - a draft `FAILED` (the sender gave up or was interrupted): En séquence, `CHECK_FAILED_SEND`, actor
     Vous. The failed draft still counts as open, so the sequence waits until a human checks the sent
     folder;
   - no open draft: En séquence, `WAIT_DRAFT` before the first email was sent, `WAIT_REPLY` after.
4. **Not started.** An account where nobody was ever enrolled gives one card in À démarrer:
   `FIND_CONTACT` with no contact (same accounts as today's "prospects à compléter": score 45 and more,
   dossier not marked no fit), `ADD_EMAIL` when its contact has no email, `START_SEQUENCE` when the email
   is known. With several contacts, the first one with an email is shown. An address that opposed
   prospecting (suppressed) is skipped: when every contact with an email is suppressed, the card is
   `ADD_EMAIL` for a contact with no email if there is one, otherwise the account has no card.
5. A finished sequence (completed, stopped, or replied with the reply handled) gives no card. The account
   stays visible in Pilotage and in the Carnet.
   A PAUSED enrollment (out-of-office reply) gives no card while it is paused.

### No drag and drop

A stage is derived from facts (a contact exists, a draft waits, a reply came). A card changes column when
the action is done, by the user or by Bloom. Cards cannot be moved by hand.

### Head of the view

- Headline: « N actions vous attendent » with the detail by kind (« 3 réponses, 2 emails à valider,
  2 notes LinkedIn, 3 contacts à trouver »), or « Rien ne vous attend » when N is 0. The kinds, in order:
  réponses, emails à valider, notes LinkedIn, envois à vérifier, contacts à trouver (`FIND_CONTACT`),
  séquences à lancer (`ADD_EMAIL`, `START_SEQUENCE`); a kind at 0 is left out.
- A two-choice toggle, « À vous N » and « Tout M ». « À vous » keeps only the cards whose actor is the
  user. « Tout » is the default.
- « Commencer » (primary) opens the first card that waits for the user, starting from the right-hand
  column: the closest to a meeting first. Hidden when nothing waits. A `CHECK_FAILED_SEND` card waits for
  the user but stays out of this order (and of « Suivant », `j` and `k`): the panel has no action that
  clears it.
- The last-run line of today's Prospects view, on one line with a « Détail » disclosure for the source
  chips and notes, and « Chercher maintenant » (outline). The Source, Score and Offre filters apply to the
  cards of the board.
- The header search filters the cards by account or person name while Parcours is open.

### The panel

A click on a card (or Entrée on it) opens a panel on the right of the board. The board stays visible
behind it, the selected card is outlined.

Layout as built: the panel is `position: fixed` to the window. Above 900 px the board reserves the panel's
width while it is open, so no card hides under it; its columns keep at least 220 px and the board scrolls
sideways when five do not fit, the open card being scrolled into sight. The undo toast can sit over the
foot of the panel for its 5 seconds. Below 900 px the panel covers the page and the rest of the page is
inert while it is open.

- **Head.** Account name, score, offer, source, and « Fermer ». Échap closes and gives the focus back to
  the card.
- **Frieze.** The five stages in one line; past stages, the current stage and the coming ones are styled
  differently. It is not interactive.
- **Body.** The content of the stage, built from today's cards, unchanged:
  - `TO_START` / `FIND_CONTACT`: the prospect card (trigger fact, review issues, LinkedIn searches, the
    contact form, « Liste noire » with its inline confirmation), plus the registry officers of the Carnet
    with « Ajouter au carnet »;
  - `TO_START` / `ADD_EMAIL`: the contact, an email field, « Enregistrer et lancer la séquence »;
  - `TO_START` / `START_SEQUENCE`: the contact and « Lancer la séquence »;
  - `TO_REVIEW`: the draft card (guard report, subject, message with citations, sources, « Approuver »
    or « Corriger », « Modifier », « Rejeter »);
  - `IN_SEQUENCE` / `SEND_LINKEDIN_NOTE`: the LinkedIn task card (« Copier la note », « Ouvrir le
    profil », « Fait »);
  - `IN_SEQUENCE`, waiting: one sentence that says what Bloom is waiting for and when: `WAIT_SEND`
    « Email approuvé. Il part dès le prochain créneau, {date}. », `WAIT_REPLY` « Message envoyé. Sans
    réponse, Bloom prépare l'étape suivante, s'il en reste une, et vous la soumet ici. » (the step sent may
    be a LinkedIn note, the last step has no follow-up);
  - `IN_SEQUENCE` / `CHECK_FAILED_SEND`: no action, the sentence « L'envoi a échoué. Vérifiez dans votre
    boîte d'envoi s'il est parti avant de relancer quoi que ce soit. »;
  - `REPLIED`: the reply card with « Répondre », **« Noter un rendez-vous »** (new) and « Classer »
    (today's « Marquer comme traité »). « Répondre » and « Noter un rendez-vous » are not shown for an
    opposition, a "not interested" or a bounce, nor for a reply with no account;
  - `MEETING`: one sentence, « Rendez-vous noté. La suite se passe hors de Bloom. »
- **Consequence line.** Under each primary action, one muted sentence says what happens next:
  - Approuver: « Approuver : l'email part dès le prochain créneau, {date}. » (approved emails leave one at
    a time, spaced and under a daily cap: the slot is the earliest, not a promise); for a LinkedIn draft
    « Approuver : la note passe dans En séquence, vous l'envoyez sur LinkedIn. »
  - Ajouter et lancer la séquence: « Bloom rédige le premier email, vous le validez ici. »
  - Noter un rendez-vous: « Le compte passe dans Rendez-vous et compte dans l'objectif. »
  - Classer: « La carte quitte le tableau. Le compte reste dans Pilotage et le Carnet. »
  - Fait (LinkedIn): « La séquence passe à l'étape suivante. »
- **Foot.** « Suivant : {account}, {action} » and a « Suivant » button that opens the next card waiting
  for the user, in the order of « Commencer » (the first one when the open card does not wait for the
  user). The order does not wrap around: after its last card there is no next card, the foot reads « Plus
  rien ne vous attend après cette carte. » and has no button. After an action the panel moves to the next
  card by itself; when none is left it closes.
- **Keyboard.** Outside a field, `j` and `k` move to the next and previous card waiting for the user.
  `a` approves the draft shown and `e` opens its edit mode, only while the focus is inside the panel.

Removing actions keep today's rule: the card leaves at once, a toast offers « Annuler » for 5 seconds,
and the server is told only after that. « Noter un rendez-vous » follows the same rule.

### Narrow screens

Below 900 px the board shows one column at a time, chosen by the pill tabs with their counts (the
look of the tier tabs of Pilotage, as buttons with `aria-pressed`). The panel takes the whole screen.

## Navigation

Sidebar: **Parcours** (count: cards waiting for the user), **Demandes** (count, only shown when there is
at least one demand), Pilotage, Carnet, Résultats. Configuration: Méthode, Liste noire, Importer une liste.

- Parcours is the default view (`#parcours`). `#today` and `#prospects` open Parcours.
- **Demandes** (`#demands`) holds the Codeur demands of today's Prospects view, unchanged.
- The primary button « Lancer une recherche » and the mail icon button leave the sidebar: the search is
  started from the head of the board.
- Pilotage, Carnet, Résultats and Liste noire do not change. The Méthode view names Parcours where it
  named À faire and Prospects.

## Server

All logic sits in `core` use cases with tests on the fakes. Routes stay thin. No migration.

### `GetBoard` (new use case)

Returns `Board(cards: List<BoardCard>)`, built from the existing ports (accounts, contacts, sequences,
drafts, replies, deals, facts) with the rules above. One port method is added:
`SequenceRepository.enrolledContactIds(): Set<ContactId>` (every contact ever enrolled, whatever the status),
so rule 4 needs one query instead of one per contact.

```kotlin
enum class BoardStage { TO_START, TO_REVIEW, IN_SEQUENCE, REPLIED, MEETING }
enum class BoardAction {
    FIND_CONTACT, ADD_EMAIL, START_SEQUENCE, REVIEW_EMAIL, FIX_EMAIL,
    SEND_LINKEDIN_NOTE, CHECK_FAILED_SEND, WAIT_DRAFT, WAIT_SEND, WAIT_REPLY, ANSWER_REPLY, NONE,
}
data class BoardCard(
    val stage: BoardStage, val action: BoardAction, val yours: Boolean,
    val accountId: AccountId?, val title: String, val score: Int?, val source: String?, val offer: Offer?,
    val contactId: ContactId? = null, val contactName: String? = null, val contactRole: String? = null,
    val draftId: Long? = null, val replyId: Long? = null, val replyIntent: ReplyIntent? = null,
    val step: Int? = null, val stepCount: Int? = null,
)
```

The page keeps loading the lists it loads today (drafts, tasks, replies, prospects to complete, book) and
uses the ids of a card to find the content of its panel. The French labels live in the page.

Route: `GET /api/board`.

### `BookMeeting` (new use case)

Input: an account id and the id of the reply that led to it. Creates a deal in stage `MEETING` for the
account (offer of the dossier, else the recommended offer, else Discovery; amount 0), or moves an existing
`QUALIFIED` deal to `MEETING`; does nothing more when a deal is already at `MEETING` or later. Logs
`MEETING_BOOKED` once, and marks the reply handled. A blacklisted account answers not found.

Route: `POST /api/accounts/{id}/meeting` with `{ "replyId": 12 }`. `POST /api/deals` is left as it is.

### `CompleteContactEmail` (new use case)

Input: a contact id and an email. Refuses an address that opted out, stores the email with the source
« saisie manuelle », then enrolls the contact through `EnrollContact` as a manual request.

Route: `POST /api/contacts/{id}/email` with `{ "email": "..." }`. It gives « Email manquant » its action.

## Example data

The `?demo=1` data gets a board that agrees with its lists. Today Banque Ardoise is both a prospect with
no contact and the recipient of a draft: the example set is made consistent, and two accounts with a
meeting are added. In demo mode an action moves or removes the card in the page, as the lists do today.

## Design project

The Claude artifact Bloom is the source of truth and is updated before the code:

- `Bloom.dc.html`: new views `parcours` (board), `parcours-panel` (board with the panel open), `demands`,
  and the narrow layout; the `today`, `empty`, `prospects` and `prospects-empty` views are removed; the
  sidebar changes.
- `README.md`: a "Parcours" section with the rules of this document, the sections on À faire and Prospects
  reworded for the panel, and the list of known differences revised.

Version 13 of the design takes the copy changed during the build (column lines, « dès le prochain
créneau », the headline kinds, the failed send card, no wrap of « Suivant ») and lists the panel layout
under its known differences.

No new token: colours, fonts and radii come from Thermos.

## Tests

- `core`: `GetBoardTest` (one test per placement rule), `BookMeetingTest`, `CompleteContactEmailTest`,
  with the fakes.
- `app`: route tests for the three routes; `ModulesTest` covers the bindings; `ProjectRulesTest` keeps
  checking tokens and claims on `index.html`.
- Interface: `scripts/ui-demo.sh` and `?demo=1`, looked at in light and dark, wide and narrow.
- `./gradlew build` passes.

## Out of scope

- Deal stages after the meeting (proposal, amount, won, lost) in the interface.
- Drag and drop.
- A stage column in the ranked accounts table of Pilotage.
- Reworking the density of the cards, the Carnet, Résultats or the unsubscribe page.
- Follow-up of a "not now" reply after it is classed: the card leaves the board, as the reply leaves the
  queue today.
