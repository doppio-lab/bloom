# Proof audit

Companion to [positioning.md](positioning.md).

11 claims proven, 7 partial, 2 false, 1 unverifiable. Read-only audit of the working tree on 2026-10-10 (commit 8e4546f plus uncommitted changes). "Proven" means a code path was read, not that it ran: only `core` has tests, and agents, SMTP and IMAP have never run end to end.

## Claims register

| # | Claim | Status | Proof | Marketing consequence |
| --- | --- | --- | --- | --- |
| 1 | The first email cites at least one sourced fact | Proven | `MessageGuard.kt` rule 3 (`sans-preuve`), test "un premier email sans preuve est bloqué" in `CoreTest.kt`. Applied at drafting (`WriterAgent.kt`) and at approval (`Routes.kt`) | Usable as is |
| 2 | No number goes out unless it appears in a cited fact | Partial | `MessageGuard.kt` rule 4, test "un chiffre inventé est bloqué". Gaps: the subject line is not scanned, 1, 2, 3, 15, 20 and 30 always pass, and web facts embed a model-written sentence (`ResearchAgent.kt`, `recordQuote`, `meaning`) that counts as provenance | Say "every number in the body of the email". Fix before saying "every number" |
| 3 | Every claim or sentence has a source (README line 9, dashboard subtitle) | False | The guard checks that markers point to real facts, not that each assertion carries one. Only the prompt asks for it (`Prompts.kt`). Follow-up emails need no fact | Never write "every sentence" or "every claim". Interim: "the first email rests on a sourced fact, every number is traced" |
| 4 | Web quotes are kept only if found word for word | Proven | `ResearchAgent.kt`, `recordQuote`: refusal when the normalized quote is not in the page. No test | Usable, with a real example |
| 5 | Every fact carries its source and date | Proven | `Models.kt`, `Fact` (`sourceUrl`, `retrievedAt`). Shown under each draft in `index.html` | Usable. It is slide 4 of template B |
| 6 | A fake "Re:" is blocked | Partial | `MessageGuard.kt` rule 2 blocks it in drafts. `Mail.kt` line 103 adds "Re: " to follow-ups threaded on our own first email | Hook 4 on hold until the owner decides |
| 7 | Seven reasons a draft is blocked | Partial | `MessageGuard.kt` has seven rule groups. The seventh only warns | Reword hook 3 to six, or count individual rules |
| 8 | Stock phrases are banned in code | Proven | `MessageGuard.kt`, `bannedFr` and `bannedEn`, test "le jargon et le tiret cadratin sont bloqués" | Hook 6 usable |
| 9 | No link in the first email | Partial | `MessageGuard.kt` rule 6 blocks links in the body. `Mail.kt`, `footer`, appends the unsubscribe link to every email | Reword hook 21: "no link, apart from the one to unsubscribe" |
| 10 | Nothing leaves without human approval | Proven | Drafts are created `PENDING_REVIEW` (`WriterAgent.kt`). Only `/drafts/{id}/approve` sets `APPROVED` (`Routes.kt`). `sendApproved` reads approved drafts only (`Orchestrator.kt`). No test | Usable. Worth a test before it becomes a headline |
| 11 | 25 emails a day per mailbox, not one more | Partial | Cap enforced in `Orchestrator.kt`, `sendApproved`. Value read from `MAX_PER_MAILBOX_PER_DAY` with no upper bound (`Application.kt`) | Say "25 a day by default" |
| 12 | Sent Tuesday to Thursday, 8:30 to 11:30, spaced out | Proven | `SendingPolicy` in `Models.kt`, `SequenceEngine.kt`, window tests in `CoreTest.kt`, 180 seconds spacing in `Orchestrator.kt` | Usable |
| 13 | No automated action on LinkedIn | Proven | Searched `data`, `agents` and `app` for LinkedIn: only a search URL built for a human (`DiscoveryRoutes.kt`) and a manual task queue (`Orchestrator.kt`, `completeTask`). No LinkedIn client | Hook 26 usable |
| 14 | Any reply stops the sequence | Proven | `Orchestrator.kt`, `processReplies`. Never run against a real mailbox | Usable after one real run |
| 15 | Unsubscribe in one click, no account, no reason | Proven | `Routes.kt`, `/u/{token}`, and `Mail.kt` headers `List-Unsubscribe` and `List-Unsubscribe-Post`. The confirmation page names Doppio Lab | Hook 23 usable |
| 16 | No open-tracking pixel | Proven | `Mail.kt` sends plain text only. No tracking or analytics code found in the repo | Hook 22 usable |
| 17 | Every email says where your contact details come from | Partial | One generic sentence from `LEGAL_FOOTER_FR` ("sources publiques"). The contact's own `dataSource` exists but is not used in the footer | Reword hook 29 and template C slide 3: "from public sources" |
| 18 | Opposition list by email and by domain, never purged | Partial | `Repository.kt`, `isSuppressed` checks both. The purge deletes contacts only. No code path ever writes a domain | Say "by email" until a domain can be added |
| 19 | Contacts deleted after 3 years without exchange | Proven | `Repository.kt`, `purgeStaleContacts`, scheduled monthly in `Orchestrator.kt`. No test | Hook 30 usable once the CNIL reference is checked |
| 20 | Legal references (LinkedIn terms section 8.2, RGPD article 14, CNIL three years) | Unverifiable | Stated in the README, nothing in the repo to check them against | Open each text and link it before quoting |
| 21 | A team other than Doppio Lab can configure Bloom | False | Hardcoded: studio description (`Prompts.kt`), ten client references (`ReferenceLibrary.kt`), offers and sectors (`Models.kt`), sequences (`SequenceEngine.kt`), allowed link domain (`MessageGuard.kt` line 142), login `doppio` (`Application.kt`), unsubscribe page (`Routes.kt`) | No "for your team" copy. The waitlist can say "built for our own prospecting first" |

One proof point is not in the positioning yet: the uncommitted `Llm.kt` adds a local model option through Ollama. "Your prospect data never reaches an AI vendor" could become a claim, once it is committed and tested.

## What can ship now

| Item | Status | Condition |
| --- | --- | --- |
| Landing page H1, subtitle, CTA | Go | None |
| Hook family 2, Le reçu | Go | Hooks 7 and 10 need a real example |
| Hook family 5, Les règles | Go | Hook 29 reworded, hook 30 after the CNIL check |
| Hook family 1, Le refus | Partial | Hooks 5 and 6 go. Hook 3 reworded. Hooks 1 and 2 need a draft really refused. Hook 4 waits for the "Re:" decision |
| Hook family 3, L'anti-volume | Partial | Hook 17 goes. Hook 13 reworded. Hook 14 waits for the business model, hook 15 for live sending |
| Hook family 4, Côté destinataire | Partial | Hooks 19, 22 and 23 go. Hook 21 reworded. Hook 24 needs a real approved email |
| Templates A and B | Wait | A real refused draft and real screenshots |
| Template C | Wait | Per-contact data source (prompt 2), or slide 3 reworded |
| Copy for other teams | Wait | Configuration (prompt 3) |

## Screenshot inventory

There is no screenshot tooling in the repo, and one usable asset: the dashboard ships with example data.

| Item | State |
| --- | --- |
| Frameworks | None. No Playwright, no snapshot tests, no CI configuration |
| Screens | One static page, `app/src/main/resources/static/index.html`: À valider (with blocked drafts and guard reasons), Réponses, LinkedIn à la main, Nouveaux prospects, Comptes classés, Pipeline, Délivrabilité, Agents. Plus the explainer `docs/pipeline.html` |
| Fixture data | A built-in `DEMO` dataset, shown when the API does not answer, with a visible "Données d'exemple" pill |
| Locales | French only |
| Themes | Light and dark, through CSS tokens |
| Gaps | No English interface, no scripted capture, no account detail page, and the example dataset has to be checked for real company names before publication |

Slide 6 can be produced today from the example data, as long as the caption says it is an example. A screenshot of a draft Bloom really refused needs one real run in dry mode.

## Backlog: Claude Code prompts

Four workstreams, most blocking first. Each prompt is to paste into Claude Code at the root of the repo. Prompts 1 and 2 make the promise true, prompt 3 opens Bloom to other teams, prompt 4 produces slide 6.

### Prompt 1. Close the gaps in the proof guard

```text
Start in plan mode. Read README.md (there is no CLAUDE.md in this repo), then
core/src/main/kotlin/lab/doppio/bloom/core/guard/MessageGuard.kt,
core/src/test/kotlin/lab/doppio/bloom/core/CoreTest.kt,
agents/src/main/kotlin/lab/doppio/bloom/agents/ResearchAgent.kt (recordQuote),
agents/src/main/kotlin/lab/doppio/bloom/agents/WriterAgent.kt and
app/src/main/kotlin/lab/doppio/bloom/app/outreach/Mail.kt.
Propose a plan and wait for my approval before writing any code.

Why: Bloom's public promise will be "no cold email leaves without proof: the
first email rests on a sourced fact and every number is traced to a cited
fact". An audit found four places where the code does not hold that promise:
1. MessageGuard rule 4 scans numbers in the body only. A number in the
   subject line is never checked.
2. recordQuote builds the fact statement as "$meaning (« $quote »)". The
   meaning is written by the model, and rule 4 treats the whole statement as
   provenance, so a number invented in the meaning passes the guard.
3. Rule 3 requires a cited fact only when stepIndex == 0. Follow-up emails can
   make claims about the prospect with no fact at all.
4. Rule 2 blocks a subject starting with "Re:", but Mail.kt line 103 adds
   "Re: " to every follow-up threaded on our own first email.

What to build, in this order:
- core: scan the subject line for numbers with the same rule as the body.
- core and agents: number provenance must come from verbatim material only
  (Fact.excerpt for web facts, statements built by code for App Store, job
  and registry facts). Keep the model-written meaning for display, but do not
  let it count as provenance. Propose the smallest model change that does it.
- core: decide with me how follow-ups are treated (see decisions).
- app: align Mail.kt with the decision on "Re:".

Decisions that are mine, do not settle them silently. Present options and
trade-offs:
- Follow-ups: (a) require at least one cited fact on every email, safest for
  the promise but short follow-ups get heavier; (b) require a fact only when
  the follow-up contains a number or names the prospect's product, lighter
  but needs a heuristic; (c) leave as is and keep the public claim limited to
  the first email.
- "Re:" on follow-ups: (a) keep threading headers but send the original
  subject without the prefix; (b) keep the prefix and drop the public claim
  that a fake "Re:" is blocked.
- The always-allowed numbers (1, 2, 3, 15, 20, 30): keep, shrink or tie them
  to the offer labels.

Invariants to write as tests in core:
- a number present only in the subject line and in no cited fact is blocked;
- a number present only in a web fact's model-written meaning is blocked;
- a number present in a web fact's verbatim excerpt passes;
- the follow-up rule I choose, with one passing and one blocked case;
- existing tests stay green.

Check and report without fixing:
- whether any path creates or sends a draft without going through
  MessageGuard.check;
- whether a human edit at approval can remove every citation on a follow-up;
- group 7 of the guard only warns: list which rules block and which warn, so
  the marketing copy can quote an exact count.

Done means: ./gradlew build passes, including the new tests. Do not commit
unless I ask. Follow my global rules for commit messages and comments
(English, no em dash).
```

### Prompt 2. Make the control and respect claims true and tested

```text
Start in plan mode. Read README.md (there is no CLAUDE.md in this repo), then
app/src/main/kotlin/lab/doppio/bloom/app/outreach/Orchestrator.kt,
app/src/main/kotlin/lab/doppio/bloom/app/outreach/Mail.kt,
app/src/main/kotlin/lab/doppio/bloom/app/web/Routes.kt,
app/src/main/kotlin/lab/doppio/bloom/app/Application.kt and
data/src/main/kotlin/lab/doppio/bloom/data/db/Repository.kt (isSuppressed,
suppress, purgeStaleContacts, sentTodayBy). Propose a plan and wait for my
approval before writing any code.

Why: Bloom's marketing will claim that nothing leaves without human approval,
that sending is capped at 25 a day per mailbox, that every email says where
the contact's details come from, and that opposition works by email and by
domain. The audit found these paths correct on reading but untested, and
three gaps:
1. The legal footer (Mail.kt, footer) is one generic sentence from
   LEGAL_FOOTER_FR or LEGAL_FOOTER_EN. The contact's own dataSource is stored
   but never shown to the recipient.
2. isSuppressed checks "@domain" entries, but no route, screen or code path
   ever writes one.
3. MAX_PER_MAILBOX_PER_DAY has no upper bound, so "25, not one more" is only
   a default.

What to build, in this order:
- data: a way to add and list a domain opposition.
- app: a route for it, behind the admin authentication, and the per-contact
  data source in the footer.
- app: the cap decision below.
- tests: this repo has no tests outside core. Propose the lightest setup
  (the README mentions Testcontainers as not done) and tell me its cost
  before adding dependencies.

Decisions that are mine, with options and trade-offs:
- Daily cap: (a) hard ceiling in code, for example 25 or 30, refusing higher
  values at startup, which makes the public claim strict; (b) keep it free
  and let the copy say "25 by default".
- Footer wording when dataSource is a free-text note typed by a human: show
  it as is, map it to a short list of public labels, or fall back to the
  generic sentence.
- GET /u/{token} unsubscribes at once. Mail security scanners that prefetch
  links can therefore unsubscribe people by accident. Options: keep it (safe
  for the recipient, loses some prospects), or show a one-button confirmation
  page on GET and keep the one-click POST for mail clients.

Invariants to write as tests:
- no email is sent for a draft whose status is not APPROVED;
- approval is refused when MessageGuard returns a blocking violation,
  including after a human edit;
- a mailbox that reached the cap sends nothing more that day;
- a contact whose email or domain is in the opposition list gets no draft
  and no email, and its enrollment stops;
- the purge never deletes a row of the opposition list;
- every outgoing email carries List-Unsubscribe, List-Unsubscribe-Post and a
  footer, and its content type is text/plain.

Check and report without fixing:
- whether the three-year purge criterion (createdAt and lastInboundAt)
  matches the CNIL guidance it refers to, with the link to the text;
- the exact LinkedIn terms section the README cites as 8.2, with the link;
- any place where a recipient-facing text hardcodes "Doppio Lab".

Done means: ./gradlew build passes, including the new tests. Do not commit
unless I ask. Follow my global rules for commit messages and comments
(English, no em dash).
```

### Prompt 3. Let another team configure Bloom

```text
Start in plan mode. Read README.md (there is no CLAUDE.md in this repo), then
agents/src/main/kotlin/lab/doppio/bloom/agents/Prompts.kt,
core/src/main/kotlin/lab/doppio/bloom/core/references/ReferenceLibrary.kt,
core/src/main/kotlin/lab/doppio/bloom/core/model/Models.kt (Offer, Sector,
SignalType), core/src/main/kotlin/lab/doppio/bloom/core/sequence/SequenceEngine.kt
(DefaultSequences), core/src/main/kotlin/lab/doppio/bloom/core/guard/MessageGuard.kt
(line 142, allowed link domain), core/src/main/kotlin/lab/doppio/bloom/core/scoring/Scoring.kt,
app/src/main/kotlin/lab/doppio/bloom/app/Application.kt and
app/src/main/kotlin/lab/doppio/bloom/app/web/Routes.kt (unsubscribe page).
Propose a plan and wait for my approval before writing any code.

Why: Bloom is going to be positioned as a product for other teams, but it is
wired to one company. Hardcoded today: the studio description and voice in
Prompts.kt, ten client references in ReferenceLibrary.kt, the offers enum and
its labels, the three default sequences, the only allowed link domain
(doppio-lab.com), the login name "doppio", the authentication realm, and the
unsubscribe confirmation text. Until a second team can run Bloom without
editing Kotlin, no public copy may say "for your team".

What to build, in this order. Keep the plan incremental: I want a first step
that changes no behavior for the current installation.
- core: a single tenant profile type that carries company name, description,
  voice notes, offers, references, allowed link domains and sequences.
- core: MessageGuard, ReferenceLibrary, Scoring and DefaultSequences read the
  profile instead of constants. The current Doppio Lab values become the
  default profile, so existing tests stay green unchanged.
- data or app: where the profile is loaded from (see decisions).
- agents: Prompts.kt builds its system prompts from the profile.
- app: login name, realm and recipient-facing texts come from the profile.

Decisions that are mine, with options and trade-offs:
- Profile storage: (a) a file mounted next to .env, simplest and fits the
  self-hosted model; (b) database tables with an admin screen, needed for a
  hosted product but much larger; (c) file now, tables later.
- Offers and sectors are Kotlin enums stored by name in PostgreSQL. Turning
  them into data touches migrations and scoring weights. Tell me the real
  cost and whether a first version can keep sectors fixed and make only
  offers, references and voice configurable.
- Discovery sources (mobile job posts, App Store France rankings) are
  specific to selling native app development. Options: leave discovery as a
  Doppio Lab module and make CSV import the generic entry point, or design a
  source interface now.
- Single tenant per installation, or several tenants in one database.

Invariants to write as tests:
- with the default profile, every existing core test passes unchanged;
- with a second, fictional profile, the guard allows that profile's link
  domain and blocks doppio-lab.com;
- a reference of one profile can never be cited under another profile;
- no prompt sent to the model contains the string "Doppio" when the profile
  is the fictional one.

Check and report without fixing:
- every remaining occurrence of "doppio" outside package names, with file and
  line, sorted into: must become configuration, may stay (comments), and
  unclear;
- the Kotlin package name lab.doppio.bloom: list what renaming would touch,
  do not rename.

Done means: ./gradlew build passes, including the new tests. Do not commit
unless I ask. Follow my global rules for commit messages and comments
(English, no em dash).
```

### Prompt 4. Scripted screenshots of the dashboard

```text
Start in plan mode. Read README.md (there is no CLAUDE.md in this repo), then
app/src/main/resources/static/index.html (the DEMO constant near line 238 and
the render functions) and docs/pipeline.html. Propose a plan and wait for my
approval before writing any code.

Why: every marketing slideshow for Bloom ends on a real screenshot, and the
repo has no way to produce one. The dashboard already falls back to a built-in
example dataset when the API does not answer, with a visible "Données
d'exemple" pill, in light and dark themes. That is the right base: honest,
repeatable, no real prospect on screen.

What to build, in this order:
- fixtures: review the DEMO dataset so that one draft is blocked by the guard
  for an unsourced number, one passes with its citations and facts visible,
  one LinkedIn note waits in the manual queue, and the deliverability tab
  shows values under the thresholds. Every company and person must be
  fictional.
- capture: one script that opens the static page without a backend and saves
  full-page and per-tab captures at 1440x900 and 390x844, light and dark.
  Propose the tool (Playwright is the obvious candidate) and where the script
  and its dependencies live so the Gradle build stays untouched.
- output: a folder ignored by git, with stable file names per tab, theme and
  size.

Decisions that are mine, with options and trade-offs:
- English interface: the page is French only and the second market is
  English-speaking. Options: (a) a small string table and a ?lang=en switch,
  (b) a separate English fixture page used only for captures, (c) French
  captures only for now.
- The "Données d'exemple" pill: keep it visible in captures (my default,
  it is the honest label) or hide it and state it in the caption instead.
- Whether captures of a real instance in dry-run mode are also needed, which
  requires seeded data in PostgreSQL rather than the DEMO constant.

Invariants to check in the script:
- the capture fails if the page shows live or dry-run mode instead of the
  example mode, so real data can never end up in a marketing image;
- the capture fails if a network request leaves the page, apart from fonts;
- two runs produce identical file names.

Check and report without fixing:
- whether any name in the DEMO dataset matches an existing French company or
  trademark (search before answering, do not guess);
- which tabs look empty or broken at 390 pixels wide;
- the account detail page listed as not done in the README: say whether a
  slideshow would need it.

Done means: the script runs from a clean checkout with one documented
command, and ./gradlew build still passes. Do not commit unless I ask. Follow
my global rules for commit messages and comments (English, no em dash).
```
