# Claims proof audit: Bloom

> Written and updated by the `claims-proof-audit` skill. The repo was read only; this folder is the only place the skill writes. Keep the headings: they are the anchors of later updates. Hand edits are welcome and kept.

Status: to validate | Repo: doppio-lab/bloom @ 15980e4 | Positioning: [positioning.md](positioning.md) | Last update: 2026-10-15

## Verdict

The promise holds in its narrow form, and nothing on the guard side moved since 2026-10-10: `MessageGuard.kt` has no diff. Of 25 claims, 12 are proven, 10 partial, 2 false and 1 unverifiable. The campaign can start on the landing page and the hooks marked go below; it must not start before the dashboard itself stops over-claiming. The Méthode view states "12 catégories, 120 apps par nuit" when the code reads 21 categories and up to 400 apps (C24), the README still says "chaque affirmation est reliée à un fait sourcé" (C3, False), and the fact date promised by template B is no longer shown on screen (C5). Those three fixes are prompt 01, and they are small.

What improved since the last audit: sending is idempotent and tested against PostgreSQL (`data/src/test`, 12 tests), approval refuses a draft that is no longer waiting (409), bounces stop sequences, scheduled jobs are persisted, LLM usage has a daily token cap, and CI runs `./gradlew build` on every push (`.github/workflows/build.yml`). What did not: no test covers the approval gate itself, the guard has no test for "Re:", links, emojis or numbers in the subject, and agents, SMTP and IMAP have never run end to end.

## Scope and sources

- **Claims taken from:** [positioning.md](positioning.md) (updated 2026-10-15: pillars, 30 hooks, three templates), `README.md`, and the interface copy of `app/src/main/resources/static/index.html` (views À faire and Méthode). There is no landing page and no store listing yet.
- **Repo areas read:** `CLAUDE.md`, `README.md`; `core` (guard, scoring, sequences, models), `data` (repository, discovery, migrations V1 to V9, tests), `agents` (research, writer, replies and coach, LLM clients), `app` (routes, orchestrator, mail, application, dashboard). Commits read: `8e4546f..15980e4` (10 commits).
- **Not reachable:** a real mailbox, so replies, bounces and SMTP sending were checked on reading and in PostgreSQL tests only, never against a live server; the legal texts cited in the README (C20) were not opened; `.env` was not read.

## Map of what the user sees

| Result shown to the user | Produced by (file, function) | Data sources | Screen |
|---|---|---|---|
| Prospects and their trigger fact | `data/.../discovery/DiscoveryService.kt` `run`, `DiscoveryRoutes.kt` `/to-complete` | App Store charts, France Travail, BODACC, BOAMP, TED, DECP (awarded contracts ending within a year), Codeur, press, GitHub, job boards, CSV import | Prospects |
| Facts behind a prospect or a draft | `agents/.../ResearchAgent.kt` `recordQuote` (web), code-built statements for the other sources | Prospect site (6 pages), public APIs | Prospects, À faire (sources panel) |
| Draft emails and LinkedIn notes | `agents/.../WriterAgent.kt`, checked by `core/.../guard/MessageGuard.kt` `check` | Facts, `ReferenceLibrary.kt`, `Prompts.kt` | À faire |
| Approval and sending | `app/.../web/Routes.kt` `/drafts/{id}/approve`, `app/.../outreach/Orchestrator.kt` `sendApproved`, `Mail.kt` `send` | Approved drafts only | À faire, then the recipient's inbox |
| Reply handling | `Orchestrator.kt` `processReply`, `ReplyAndCoachAgents.kt` classifier | IMAP | À faire (Réponses) |
| Contact book | `DiscoveryRoutes.kt` `/book`, Annuaire des entreprises, Dropcontact | Public registry, Dropcontact | Carnet |
| Results | `Routes.kt` `/overview` | Events table | Résultats |
| How Bloom works | Static text in `index.html` (view Méthode) | None: hand-written copy | Méthode |

Two paths lead to a draft's provenance: facts built by code from public APIs, and web facts whose statement embeds a sentence written by the model (`ResearchAgent.kt` line 206). Only the first is verbatim; the guard treats both the same (C2).

## Claims register

| # | Claim | Status | Proof (path) | Marketing consequence |
|---|---|---|---|---|
| C1 | The first email cites at least one sourced fact | Proven | `MessageGuard.kt` rule `sans-preuve` (lines 104 to 106), applied in `WriterAgent.kt` (lines 95, 112) and at approval in `Routes.kt`. Test `CoreTest` "un premier email sans preuve est bloqué" | Usable as is |
| C2 | No number goes out unless it appears in a cited fact | Partial | `MessageGuard.kt` lines 115 to 117 scan the body only: the subject is never checked. 1, 2, 3, 15, 20 and 30 always pass (line 59). `ResearchAgent.kt` line 206 builds web facts as "meaning («quote»)", so a number in the model's meaning counts as provenance. Test "un chiffre inventé est bloqué" | Say "every number in the body of the first email". Fix before saying "every number" (prompt 02) |
| C3 | Every claim or sentence has a source | False | The guard checks that markers point to real facts (lines 101 to 103), not that each sentence has one; follow-ups need no fact (line 104). Still written in `README.md` lines 8 and 9 | Never write it. Interim: "le premier email s'appuie sur un fait sourcé, chaque chiffre du corps est relié à un fait cité". Fix the README (prompt 01) |
| C4 | Web quotes are kept only if found word for word | Proven, no test | `ResearchAgent.kt` `recordQuote` lines 190 to 206 (normalized, case-insensitive match, 12 to 300 characters). `agents` has no test | Usable with a real example. A test is cheap (prompt 02) |
| C5 | Every fact carries its source and its date, on screen | Partial | `Models.kt` `Fact.sourceUrl`, `retrievedAt` (lines 130, 131). The dashboard shows the statement and a source link (`index.html` `factList`), not the date: `retrievedAt` appears nowhere in `index.html` since the rework of 2026-10-14 | Template B slide 4 ("un lien et une date") waits for the date on screen (prompt 01) |
| C6 | A fake "Re:" is blocked | Partial | Guard rule `objet` (lines 87 to 89) blocks it in drafts; `Mail.kt` `send` line 106 still adds "Re: " to every follow-up threaded on the first email. No test | Hook 4 on hold until the owner decides (prompt 02) |
| C7 | Six reasons a draft is blocked | Partial | `MessageGuard.kt` has 7 rule groups. Blocking rule names: `tiret-cadratin`, `emoji`, `vocabulaire`, `longueur`, `objet`, `fait-inconnu`, `sans-preuve`, `chiffre-non-sourcé`, `référence`, `lien` (10). Warning only: `exclamation`, `appel-à-l'action`, and some cases of `objet` and `référence` | Hook 3 "6 raisons" is defensible as groups that block; "10 règles bloquantes" is the exact count. Pick one and keep it |
| C8 | Stock phrases are banned in code | Proven | `MessageGuard.kt` `bannedFr` (16) and `bannedEn` (12), lines 43 to 51. Test "le jargon et le tiret cadratin sont bloqués" (French only) | Hook 6 usable |
| C9 | No link in the first email, apart from the unsubscribe one | Proven | Guard rule `lien` (lines 139, 140); `Mail.kt` `footer` (lines 71 to 75) adds the unsubscribe URL. No test for the link rule | Hook 21 as reworded on 2026-10-15 is usable |
| C10 | Nothing leaves without human approval | Proven, gate untested | `WriterAgent.kt` lines 114 to 124 create drafts `PENDING_REVIEW` only; `Routes.kt` approves only a `PENDING_REVIEW` draft that passes the guard (409 otherwise); `Repository.nextToSend` reads approved emails only; `gateway.send` is called once (`Orchestrator.kt` line 177); CSV import creates no draft. `SendSafetyTest` covers claim-once and no resend, but builds drafts already `APPROVED` | Usable. A test of the gate itself before it becomes a headline (prompt 03) |
| C11 | 25 emails a day per mailbox | Partial | `Models.kt` line 227, checked at `Orchestrator.kt` line 156. `MAX_PER_MAILBOX_PER_DAY` has no upper bound (`Application.kt` line 97); `sentTodayBy` counts `sentAt` only | "25 par jour et par boîte, par défaut" (hook 13 as reworded) |
| C12 | Sent Tuesday to Thursday, 8:30 to 11:30, spaced out | Proven | `Models.kt` lines 222 to 228, `SequenceEngine.kt` `nextSendingSlot` and `inWindow`, gap at `Orchestrator.kt` line 157. Test "le créneau saute le week-end et les jours hors fenêtre"; the 180 s gap is untested | Usable |
| C13 | No automated action on LinkedIn | Proven | Only `linkedin.com` string: a search URL a person opens (`DiscoveryRules.kt` lines 159 to 161). LinkedIn steps are manual tasks (`Routes.kt` `/drafts/{id}/done`). Searched every `.kt` and Gradle file | Hook 26 usable |
| C14 | Any reply stops the sequence | Partial | `Orchestrator.kt` `processReply` lines 242 to 245 stop the enrollment; bounces stop it and suppress the address (line 248). Gaps: a reply from an address that matches no contact and carries no In-Reply-To stops nothing; out of office pauses and nothing resumes. No test of the stop | "Toute réponse d'une personne arrête la séquence" holds for matched replies. The À faire subtitle is acceptable; add a test (prompt 03) |
| C15 | Unsubscribe in one click, no account, no reason | Proven | `Routes.kt` `/u/{token}` GET and POST (lines 224 to 235), public route (`Application.kt` line 193), headers at `Mail.kt` lines 109, 110. Caveat: a mail security scanner that fetches the GET link unsubscribes the contact | Hook 23 usable. Decide on the scanner risk (prompt 03) |
| C16 | No open-tracking pixel, plain text | Proven | `Mail.kt` line 107 sends text/plain, no image, no tracking code in the repo. The operator's dashboard loads Google Fonts (`index.html` lines 6 to 8); recipients are not affected | Hook 22 usable. Do not extend it to "no third party at all" while the dashboard calls Google Fonts and, when configured, Dropcontact |
| C17 | Every email says where the contact details come from | Partial | Generic footer (`Application.kt` lines 110 to 118, `Mail.kt` `footer`). The contact's own `dataSource` (for example "import de la liste Doppio") is never put in the email | Hook 29 as reworded ("de sources publiques") is usable. Template C slide 3 waits (prompt 03) |
| C18 | Opposition list by email and by domain, never purged | Partial | `Repository.isSuppressed` checks both (lines 642 to 646); nothing deletes from it; nothing ever writes a domain entry (`Routes.kt` line 218, `Orchestrator.kt` lines 247, 248 write emails only) | Say "par email" (prompt 03) |
| C19 | Contacts deleted after 3 years without exchange | Proven, no test | `Repository.purgeStaleContacts` lines 369 to 374, monthly persisted job (`Orchestrator.kt` lines 313 to 319). "Exchange" means inbound only | Hook 30 after the CNIL reference is checked |
| C20 | Legal references (LinkedIn terms 8.2, RGPD article 14, CNIL three years) | Unverifiable | Stated in `README.md` lines 354 to 359 and in comments, never linked | Open each text and link it before quoting |
| C21 | A team other than Doppio Lab can configure Bloom | False | 50 occurrences of "doppio" outside package names in 14 files: `Prompts.kt` 19, `ReferenceLibrary.kt` 9, `Application.kt` 6 (login "doppio" line 188), `index.html` 5, `Models.kt` 3, `MessageGuard.kt` line 142 (allowed link domain), `Routes.kt` (unsubscribe page), and one each in 6 other files | No "pour votre équipe". The waitlist can say "construit d'abord pour notre propre prospection" (prompt 04) |
| C22 | Prospect data can stay on your machine (local model) | Partial | `agents/.../Llm.kt` `LlmProvider.OLLAMA`; every AI call goes through the configured provider (writer, research, classifier, coach). Default in code is `anthropic` (`Application.kt` line 74); `.env.example` sets `ollama`. Non-AI third parties still receive prospect data when configured: Dropcontact (name, company, site), Gmail SMTP and IMAP by default. No test | Not a public claim yet. If used: "l'IA peut tourner sur votre machine (Ollama)", never "vos données ne quittent jamais votre machine" |
| C23 | AI usage is bounded | Proven, mostly untested | Daily token cap `LLM_DAILY_TOKEN_BUDGET` (`Llm.kt` `RunTracker.checkBudget`, default unlimited), 3 rejected drafts per step stop the enrollment, writer backoff up to one day, 25 research runs a night (`Orchestrator.kt` line 52), 6 pages per research. Only `tokensSince` is tested | Internal argument, not a hook |
| C24 | The Méthode view describes what the code does | Partial | False: "12 catégories : 120 apps examinées par nuit" (`index.html` line 541) against 21 genres and two charts (`DiscoveryService.kt` lines 62 to 68) and `appsPerRun = 400` (line 70). To qualify: "Jour 0, 2, 6" is in business days after the previous step (`Models.kt` line 143, `SequenceEngine.kt` lines 99 to 101). Partial: corrections reused as examples are the last 3 edited drafts actually sent (`WriterAgent.kt` line 54); the recontact date is shown, nothing schedules it. True: 2 h to 5 h, 6 pages, 25 by default, two repairs, Monday memo, 40/50/10 and 65/45, Dropcontact | Interface copy over-claims: fix before any screenshot of Méthode (prompt 01). CLAUDE.md requires interface copy to stay inside proven claims |
| C25 | À faire copy: "Rien ne part sans vous", "Aucune automatisation sur votre compte", "La séquence s'arrête dès qu'on vous répond" | Proven, last one Partial | C10, C13, C14 | Usable; the third one inherits the C14 limits |

Status: **Proven** (code path, test or data, and the screen that exposes it), **Partial** (true on one path or under conditions), **False** (the code says otherwise), **Unverifiable** (not found where it should be, or outside the repo).

## What can ship now

| Content | Allowed now | Waits for |
|---|---|---|
| Landing page H1, subtitle, CTA | yes | Nothing |
| Hook family 1: Le refus | partly | Hooks 5 and 6 go. Hook 3 goes as "6 raisons" (blocking groups). Hooks 1 and 2 need a draft really refused. Hook 4 waits for the "Re:" decision (02) |
| Hook family 2: Le reçu | yes | Hooks 7 and 10 need a real, dated example |
| Hook family 3: L'anti-volume | partly | Hooks 13 (reworded) and 17 go. Hook 14 waits for the business model, hook 15 for live sending, hooks 16 and 18 for their source |
| Hook family 4: Côté destinataire | partly | Hooks 19, 21 (reworded), 22 and 23 go. Hook 20 needs its source, hook 24 a real approved email |
| Hook family 5: Les règles | partly | Hooks 26, 27, 28 and 29 (reworded) go. Hook 25 needs its CNIL link, hook 30 the CNIL reference |
| Slideshow template A, Le refus | no | A draft Bloom really refused (one run in dry mode) and prompt 05 for the capture |
| Slideshow template B, Le reçu | no | The fact date on screen (01), then a real capture (05) |
| Slideshow template C, Les règles | partly | Slides 1 to 5 go. Slide 3 says "de sources publiques" until per-contact sources (03). Slide 6 needs a real sent email |
| Any screenshot of the Méthode view | no | Prompt 01 |
| Copy for other teams | no | Configuration (04) |

Honest interim claims:
- Instead of "chaque affirmation est reliée à un fait sourcé": "le premier email s'appuie sur un fait sourcé, chaque chiffre du corps est relié à un fait cité" until prompt 02.
- Instead of "chaque chiffre a une source": "chaque chiffre du corps du message" until the subject is scanned (02).
- Instead of "chaque email dit d'où viennent vos coordonnées": "de sources publiques" until per-contact sources (03).
- Instead of "par email et par domaine": "par email" until a domain can be added (03).
- Instead of "pour votre équipe": "construit d'abord pour notre propre prospection" until configuration (04).

## Screenshot inventory

| Framework | Locales | Screens covered | Light / dark | Full screens with fixture data |
|---|---|---|---|---|
| None in the repo (no Playwright, Puppeteer or snapshot tests; CI builds and tests only) | French only | The five views of `index.html`: À faire, Prospects, Carnet, Résultats, Méthode | Both, through CSS tokens and the theme switch | Yes in the page, no in tooling: a built-in example dataset shown only with `?demo=1`, with a "Données d'exemple" pill |

**Gap for slide 6:** no scripted capture, no English interface, and three screens the templates need do not exist yet as real data: a draft really refused (A), a draft whose facts show their date (B), the footer of a sent email (C). The example dataset gives honest captures today if the caption says "exemple". To dress up with `app-marketing-screenshots` once prompt 05 produces raw captures.

## Backlog

One Claude Code prompt per workstream, most blocking first.

| # | Workstream | Fixes claims | Prompt |
|---|---|---|---|
| 01 | Interface copy inside the proven claims | C3, C5, C24 | [prompts/01-interface-claims.md](prompts/01-interface-claims.md) |
| 02 | Close the gaps in the proof guard | C2, C4, C6, C7, C9 | [prompts/02-proof-guard.md](prompts/02-proof-guard.md) |
| 03 | Control and respect, true and tested | C10, C11, C14, C15, C17, C18, C19 | [prompts/03-control-respect.md](prompts/03-control-respect.md) |
| 04 | Let another team configure Bloom | C21 | [prompts/04-configuration.md](prompts/04-configuration.md) |
| 05 | Scripted screenshots of the dashboard | slide 6 of templates A, B, C | [prompts/05-screenshots.md](prompts/05-screenshots.md) |

## History

| Date | Skill | Change |
|---|---|---|
| 2026-10-10 | claims-proof-audit | Created as `docs/proof-audit.md`: 21 claims, four prompts inline |
| 2026-10-15 | claims-proof-audit | Moved to `marketing/claims-audit.md` and re-run at 15980e4: 25 claims (C22 to C25 added), C5 down to Partial (date no longer on screen), C9 up to Proven as worded, C14 down to Partial (unmatched replies, out of office), C16 qualified (Google Fonts on the dashboard), new False finding in the Méthode view (C24). Prompts moved to `prompts/`, a new prompt 01 for interface copy |
