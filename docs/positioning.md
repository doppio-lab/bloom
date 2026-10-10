# Bloom: app positioning

2026-10-10 · Renaud Mathieu

## Verdict

Bloom is the prospecting tool where no cold email leaves without proof: the AI drafts, deterministic code refuses any number without a source, and a human approves.

The angle holds on two conditions. The claim stays inside what `MessageGuard` really checks (a cited fact in the first email, every number traced to a cited fact). And a team other than Doppio Lab can configure Bloom, which is not the case today.

- Why this angle: no competitor homepage leads with it, and none in France mentions it. Players priced by emails sent, by credits or by autonomy cannot adopt it without cutting what they bill.
- What is off limits: volume, automation, autonomy, "AI SDR". That ground belongs to lemlist, Instantly, Clay, 11x and Artisan.
- What is not proven: only `core` is covered by tests, and nothing has run end to end. The proof audit below gives the status of each claim.
- What needs your decision: confirm angle A, and settle the business model (pricing by volume would contradict the promise).

## What the code says

The angle survives the audit, in a narrower form: of 21 claims, 11 are proven in code, 7 are partial, 2 are false and 1 cannot be verified from the repo. The promise can say "first email" and "numbers", not "every sentence".

Read-only audit of the working tree on 2026-10-10 (commit 8e4546f plus uncommitted changes). The four modules now compile and the 20 `core` tests pass (results dated 2026-10-10, read from `core/build/test-results`, not re-run here). Agents, SMTP and IMAP have not run end to end, and no module other than `core` has tests.

| Building block | What it does | Gap with the positioning |
| --- | --- | --- |
| `MessageGuard` (`core/.../guard/MessageGuard.kt`) | Blocks a first email without a cited fact, a citation that matches no fact, a number absent from the cited facts, banned phrases, a link in the body, a fake "Re:" | Numbers in the subject line are not scanned. Follow-up emails need no fact. A sentence without a number and without a marker passes: "every claim has a source" is false |
| Research agent (`agents/.../ResearchAgent.kt`, `recordQuote`) | Keeps a web quote only if found word for word on the page read | The fact statement also stores a sentence written by the model (`meaning`). A number in that sentence then counts as sourced |
| Approval (`app/.../web/Routes.kt`, `/drafts/{id}/approve`) | Only this route sets a draft to approved. The guard runs again on human edits | No test covers it |
| Sending (`app/.../outreach/Orchestrator.kt`, `sendApproved`) | Sends approved emails only, Tuesday to Thursday 8:30 to 11:30, 180 seconds apart, under the daily cap | The cap is an environment variable with no upper bound: 25 is a default. Follow-ups get a "Re:" prefix (`Mail.kt`, line 103) that the guard forbids in drafts |
| Email (`app/.../outreach/Mail.kt`) | Plain text, no pixel, `List-Unsubscribe` headers, signed link, legal footer | The footer adds the unsubscribe link, so "no link in the first email" is false as worded. The footer gives one generic source sentence, not the contact's actual source |
| Opposition and purge (`data/.../db/Repository.kt`) | Checks email and domain before each draft and each send. Deletes contacts after 3 years. Never touches the opposition list | Nothing ever writes a domain entry: no route, no screen |
| LinkedIn | No call to LinkedIn anywhere in `data`, `agents` or `app`. Notes are a manual task queue | None |
| Configuration | Mailboxes, footer, sender name, LLM provider by environment variable | Studio description, client references, offers, sequences, allowed link domain and login are hardcoded for Doppio Lab. "For your team" is false |

**Consequence on the plan.**

- Can start now: the landing page with the H1 and subtitle as written, hook families 2 (Le reçu) and 5 (Les règles), the free hooks of the other families, and screenshots from the dashboard's built-in example data, labelled as examples.
- To reword before use: hook 3 (six blocking rule groups, not seven), hook 13 ("par défaut"), hook 21 ("aucun lien, sauf celui pour se désinscrire"), hook 29 ("de sources publiques"). Hook 4 waits for a decision on the "Re:" prefix.
- Waits for a real run: templates A and B (a draft Bloom really refused), and every "we tested" hook.
- Waits for a workstream: any copy inviting other teams (configuration), template C slide 3 (per-contact data source).

The full register, the screenshot inventory and four ready-to-paste Claude Code prompts are in [proof-audit.md](proof-audit.md).

## Intake

Bloom is positioned as a product for other teams, not only as Doppio Lab's internal tool. Answers given by the user on 2026-10-10.

| Question | Answer | Consequence |
| --- | --- | --- |
| Subject | Bloom as a product (evidence-only B2B prospecting tool) | Competitors are sales engagement and AI SDR tools, not app studios |
| Market and language | France first, English version second | Promise and hooks in French, each with an English version |
| Status | Pre-launch: one commit, four modules compiling, tests only in `core` (20 tests), no landing page, no store listing | Goal is waitlist or beta sign-ups, not installs |
| Business model | Not decided | No price or independence claim in the copy until it is |

Bloom is a self-hosted web dashboard (Kotlin, Ktor, PostgreSQL), not a mobile app. The social formats of this skill are adapted to B2B: LinkedIn carousels first, TikTok and Instagram second.

## Diagnosis of the current copy

The only existing copy is the README, the dashboard microcopy and `docs/pipeline.html`. The headline is generic, and the strongest line of the product sits in a tab subtitle.

| Copy (source) | Substitution test | Verdict |
| --- | --- | --- |
| "L'outil de prospection commerciale de Doppio Lab" (README, line 3) | Any sales tool could say it with its own name | Generic |
| "Bloom repère les entreprises qui vont avoir besoin d'une app native avant qu'elles ne lancent un appel d'offres" (README, line 3) | Specific, but it describes Doppio Lab's use case, not a product others can buy | Specific to one customer |
| "On ne contacte que sur preuve" (README, line 7) | A volume-based sender cannot say it | Differentiating, second paragraph |
| "Les LLM cherchent et rédigent ; le code score, vérifie et décide" (README, line 10) | An autonomous AI SDR cannot say it | Differentiating, buried at the end of a paragraph |
| "Chaque affirmation renvoie à sa source. Rien ne part sans vous." (dashboard, tab "À valider") | Same | Best line of the product, buried in a subtitle |

Three points would mislead a buyer if the copy went public as is:

- The product is wired to one customer. Discovery sources (mobile job posts, App Store France), the reference library, the exclusion list and the allowed link domain (`doppio-lab.com` in `MessageGuard.kt`) are Doppio Lab's. "For any team" is not true yet.
- The README states that `data`, `agents` and `app` now compile (uncommitted changes of 2026-10-10), but agents, SMTP and IMAP have not run end to end. Only `core` is tested. Any "we sent" or "we tested" claim waits for a first real run.
- Legal references are asserted without a link: LinkedIn terms "section 8.2", the CNIL three-year purge, RGPD article 14. They go to the proof audit before they appear in public copy.

## Market map

Volume, automation and autonomy are taken by players with far more resources, so they are off limits in our copy. No homepage among the 12 opened on 2026-10-10 leads with sourced or fact-checked outreach.

| Player | Territory (homepage, verbatim) | Model and entry price | Exploitable weakness | News, last 12 months |
| --- | --- | --- | --- | --- |
| [lemlist](https://www.lemlist.com/) (FR) | "The only platform you need to make Outbound work." | Per user, from $69/month, "50,000 emails/mo" ([pricing](https://www.lemlist.com/pricing)) | Sells automation across five channels and send volume. Nothing on sourcing or accuracy | Bought Claap, eight-figure deal ([Le Média de l'Investisseur](https://lemediadelinvestisseur.fr/actualite/lemlist-rachat-claap/), 22 Oct 2025) |
| [La Growth Machine](https://lagrowthmachine.com/) (FR) | "Your GTM stack. Our engine built to convert." | Per identity, from 60 €/month ([pricing](https://lagrowthmachine.com/pricing/)) | Core is LinkedIn automation plus AI-written messages. Has to reassure on account restrictions | None found |
| [Waalaxy](https://www.waalaxy.com/fr) (FR) | "On rend la prospection LinkedIn (vraiment) simple" | Per user, from 19 €, 300 invitations/month ([tarifs](https://www.waalaxy.com/fr/tarifs)) | Promise is a monthly contact count ("800+"). Its own blog admits a past feature to exceed LinkedIn's quota | Quota bypass "no longer works" ([blog](https://www.waalaxy.com/blog/linkedin-platform-knowledge/bypass-linkedin-limit), updated 11 Sep 2026) |
| [Clay](https://www.clay.com/) | "Build systems to grow revenue" | Credits, free tier, paid from $54 to $185/month depending on the page ([pricing](https://www.clay.com/pricing)) | Infrastructure for signals, not a guard on what gets written. Credits reward more enrichment | $115M Series D at $7.1B ([Runtimewire](https://runtimewire.com/article/kareem-amin-clay-115m-series-d-7-1b-valuation), 9 Sep 2026) |
| [Instantly](https://instantly.ai/) | "Find Clients" | Tiers by send volume, from $47/month for 5,000 emails ([pricing](https://instantly.ai/pricing)) | The price list is an email count, up to 500,000 a month. Unlimited mailboxes | Entry plan raised to $47 ([Industry Lens](https://industry-lens.com/reports/signal-spotlight-instantly/md), 5 Aug 2026, single secondary source) |
| [11x](https://www.11x.ai/) | "Digital Workers, Human Results" | Not public | Sells work done "without supervision". No human review, no sourcing | Nothing verified |
| [Artisan](https://www.artisan.co/) | "Hire Ava, the autonomous AI BDR" | Credits, no public figure ([pricing](https://www.artisan.co/pricing)) | Autonomous end to end. LinkedIn alleged use of scraped data | Banned from LinkedIn, then reinstated ([TechCrunch](https://techcrunch.com/2026/01/07/yes-linkedin-banned-ai-agent-startup-artisan-but-now-its-back), 7 Jan 2026) |
| [AiSDR](https://aisdr.com/) | "Your AI SDR that books meetings that close" | From $250/month for 200 researched contacts ([pricing](https://aisdr.com/pricing/)) | Closest rhetoric ("lead quality over volume", "Back every message with real research"), but the AI answers replies alone and no claim carries a citation | None found |

Checked and set aside: [Pharow](https://www.pharow.com/) (French data provider, no sending, a possible partner), [Unify](https://www.unifygtm.com/), [Smartlead](https://www.smartlead.ai/), [Amplemarket](https://www.amplemarket.com/). Apollo was not checked.

The evidence ground is free at headline level, and fully free in France. It will not stay free: [FirstSales](https://firstsales.io/changelog/ai-researches-prospects-website-before-writing/) shipped a site-fact check (21 Jul 2026) and [Vibe Prospecting](https://www.vibeprospecting.ai/blog/how-to-stop-ai-agents-hallucinating-outreach-personalization-2026-for-gtm-engineers) published "Ask for Receipts" (8 Sep 2026).

Open points: homepage wording was read as extracted text and should be checked once by eye. Clay and La Growth Machine show inconsistent prices on their own pages. 11x and Artisan prices could not be obtained.

## User pains

The strongest verified pains are on the sender's side: a burned domain, a LinkedIn account at risk, and money spent for zero meetings. The hallucination pain is real but thinly documented in users' own words.

### Usage and trust figures

| Signal | Figure | Source |
| --- | --- | --- |
| Recipients' reaction to cold outreach | 52% delete unread, 61% distrust AI outreach they receive (n = 1,009 professionals, published Aug 2026, geography undisclosed, secondary coverage of a Firmable report) | [PPC Land](https://ppc.land/52-of-professionals-delete-cold-outreach-unread-firmable-finds/) |
| Cold email reply rate, vendor data | 3.43% average on 2025 data, sample given as "billions" of emails | [Instantly](https://instantly.ai/report-page) |
| Reply rate on the first email, agency data | 0.59% (n = 7,530,489 emails, 2025). Different denominator, not comparable with the row above | [Belkins](https://belkins.io/blog/sales-follow-up-statistics) |
| Gmail spam complaint threshold | Stay below 0.10%, never reach 0.30% (senders above 5,000 messages a day) | [Google](https://support.google.com/a/answer/81126?hl=en) |
| Outlook rules | Non-compliant mail from high-volume domains sent to Junk since 5 May 2025 (secondary source) | [Petri](https://petri.com/microsoft-outlook-email-authentication-rules/) |
| LinkedIn automation | Users of such tools "risk having their accounts restricted or shut down". No public restriction rate | [LinkedIn Help](https://www.linkedin.com/help/linkedin/answer/a1341387) |
| CNIL sanction, Kaspr | 240,000 €, 5 Dec 2024, about 160 million contacts | [CNIL](https://cnil.fr/fr/aspiration-de-donnees-sanction-de-240-000-euros-lencontre-de-la-societe-kaspr) |
| CNIL prospecting sanctions, 15 May 2025 | 900,000 € and 80,000 €, companies not named | [CNIL](https://cnil.fr/fr/les-sanctions-prononcees-par-la-cnil) |
| CNIL rule for B2B email | Allowed without consent when the message relates to the person's profession, with prior information and opt-out in each message (page dated 10 June 2026) | [CNIL](https://www.cnil.fr/fr/la-prospection-commerciale-par-courrier-electronique) |
| AI SDR churn | 11x employee: "We were losing 70-80% of customers that came through the door" (11x claims 79% retention). Artisan CEO: "We had extremely bad hallucinations when we first launched" | TechCrunch, [24 Mar 2025](https://techcrunch.com/2025/03/24/a16z-and-benchmark-backed-11x-has-been-claiming-customers-it-doesnt-have) and [9 Apr 2025](https://techcrunch.com/2025/04/09/artisan-the-stop-hiring-humans-ai-agent-startup-raises-25m-and-is-still-hiring-humans) |

### Recurring complaints

Quotes are fragments of 1 and 2 star Trustpilot reviews unless stated, read on 2026-10-10.

| Pain, in the user's words (FR / EN) | Side | Verbatim | Source |
| --- | --- | --- | --- |
| J'ai payé un outil et il a grillé mon domaine / I paid for a tool and it wrecked my domain | Sender | "My Domain and Emails were taken down for months while I paid to keep my domain healthy!" (Jul 2026) | [Instantly reviews](https://www.trustpilot.com/review/instantly.ai?stars=1&stars=2) |
| L'automatisation a mis mon compte LinkedIn en danger / The automation put my own LinkedIn account at risk | Sender | "apparently a shadow ban from LinkedIn due too many connection requests." (Dec 2025) | [Waalaxy reviews](https://www.trustpilot.com/review/waalaxy.com?stars=1&stars=2) |
| Tout est fait pour me faire consommer des crédits / Everything is designed to make me burn credits | Sender | "Support team never help, and everything is made to spend credit." (Oct 2025) | [Clay reviews](https://www.trustpilot.com/review/clay.com?stars=1&stars=2) |
| Des milliers d'emails, zéro rendez-vous / Thousands of emails, not one meeting | Sender | "Since starting with 11x.ai, we have had 0 meetings have been set through this software." (Sep 2024) | [11x reviews](https://www.trustpilot.com/review/11x.ai?stars=1&stars=2) |
| L'IA écrit des choses que je dois revérifier ligne par ligne / The AI writes things I have to recheck line by line | Sender | "The emails feel spammy and have very basic customization." (Nov 2024) | [11x reviews](https://www.trustpilot.com/review/11x.ai?stars=1&stars=2) |
| Je vois que c'est un robot, je supprime / I can tell it's a robot, and I delete it | Recipient | "I receive multiple, obviously AI-generated junk emails from this company" (Sep 2026) | [Instantly reviews](https://www.trustpilot.com/review/instantly.ai?stars=1&stars=2) |
| Personne ne m'a demandé avant de revendre mes coordonnées / Nobody asked me before selling my contact details | Recipient | "Kaspr a scrappé mes données illégalement et les vend à des annonceurs peu scrupuleux" (Oct 2024) | [Kaspr reviews](https://fr.trustpilot.com/review/kaspr.io?stars=1&stars=2) |

### Where the data is thin

- Reddit, G2 and Capterra blocked reading, so no quote comes from them.
- No first-person quote says "the AI invented a fact about my company". The hallucination pain rests on TechCrunch reporting and the Artisan CEO's own admission.
- No verified source shows reply rates falling year over year. Do not write "cold email is dying".
- French sender-side verbatims are nearly absent. The fear of a CNIL sanction is inferred from the sanctions, not from user quotes.
- Gartner, Forrester and Mailshake figures seen in search results were not opened and are excluded.

## Three angles, one choice

Angle A, proof before sending, wins with 17 out of 20. It is the only one a volume-priced or autonomous competitor cannot copy without breaking its own model.

| Angle | Demand | Defensibility | Social fit | Provability | Total |
| --- | --- | --- | --- | --- | --- |
| A. Proof before sending: the AI drafts, the code refuses anything without a source, you approve | 3 | 5 | 5 | 4 | 17 |
| B. Safe sending: low volume, no LinkedIn automation, no tracking, your domain and account stay intact | 5 | 2 | 3 | 3 | 13 |
| C. Compliant by construction: CNIL and RGPD rules built into the tool | 3 | 3 | 2 | 2 | 10 |

Scores from 1 to 5. Provability is a first estimate, to be confirmed by the proof audit.

**A. Proof before sending.**

- Demand: 61% of recipients distrust AI outreach, and AI SDR vendors admit hallucinations. First-person quotes on invented facts are missing, hence 3 and not 4.
- Defensibility: Instantly prices by emails sent, Clay by credits, 11x and Artisan sell work "without supervision". A blocking guard and a human approval reduce what they bill.
- Social fit: a refused draft is a ready-made visual. "The AI wrote 43%. The code said no." works as a carousel, a before and after, or a screen recording.
- Provability: `MessageGuard` is deterministic and tested in `core`. Limit: it checks that cited facts exist, that the first email cites at least one, and that every number appears in a cited fact. It does not verify each sentence. The claim must stay inside that limit.

**B. Safe sending.** The pain is the best documented (burned domains, restricted LinkedIn accounts). But every deliverability tool already promises to protect the domain, and a send cap is a setting any competitor can add in a week. It becomes the second pillar of A.

**C. Compliant by construction.** Useful in France after the Kaspr sanction, weak in English-speaking markets, and poor material for short formats. "Compliant" is also a promise no tool can guarantee for its user. It becomes the third pillar, worded as verifiable rules.

### Decisions

| Date | Decision | By |
| --- | --- | --- |
| 2026-10-10 | Position Bloom as a product, not Doppio Lab's studio offer | User |
| 2026-10-10 | France first, English version second | User |
| 2026-10-10 | Business model left open | User |
| 2026-10-10 | Angle A proposed, B and C kept as pillars. Awaiting confirmation | Claude, to be confirmed by the user |

## Messaging house

**Promise.** FR: "Aucun email de prospection ne part sans preuve." EN: "No cold email leaves without proof."

**The sentence a third party repeats.** FR: "Bloom, c'est l'outil où l'IA rédige, mais où le code refuse tout ce qui n'a pas de source." EN: "Bloom is the outreach tool where the AI drafts, and the code refuses anything without a source."

### Pillars

| Pillar | Claim | Required proof | Status |
| --- | --- | --- | --- |
| 1. Proof | The first email of a sequence cites at least one sourced fact. No number goes out unless it appears in a cited fact | `MessageGuard` tests, plus a real screenshot of a refused draft with its reason | Rule exists and is tested in `core`. Partial: subject-line numbers are not checked and follow-up emails are not held to the same rule |
| 2. Control | Nothing leaves without your approval. 25 emails a day per mailbox. LinkedIn notes are prepared, you send them by hand | Approval queue and sending limits running on a real instance | Proven in code, no test, not run end to end. 25 a day is a default, not a hard limit |
| 3. Respect | One-click unsubscribe, data source stated in every message, no open-tracking pixel, opposition list never purged | Code paths for footer, unsubscribe and opposition list, plus a link to each legal text cited | Partial: unsubscribe and no-pixel proven in code, data source only generic, domain opposition has no entry point. Legal references unverified |
| Prerequisite | A team other than Doppio Lab can configure its own signals, references and domain | Configuration without code change | To build |

Every row goes into the proof audit, since none has the full status "exists".

**Tone.** Write like an engineer reading a log aloud: a fact, its source, what the tool refused. No superlative, no promise of results.

**Banned words.**

- Ground taken by bigger players: scale, illimité, unlimited, autopilot, pilote automatique, autonome, autonomous, AI SDR, 10x, "book more meetings".
- Category jargon: hyper-personnalisation, growth hacking, intent data, séquences multicanales.
- Claims we cannot prove: "zéro hallucination", "conforme RGPD" as a guarantee, any reply rate.
- The guard's own list applies to our copy too: leader, booster, révolution, solution innovante, clé en main.

### Landing page rewrite

| Element | FR | EN |
| --- | --- | --- |
| H1 | Aucun email de prospection ne part sans preuve. | No cold email leaves without proof. |
| Subtitle | L'IA cherche et rédige. Le code bloque tout chiffre sans source. Vous validez chaque message. | The AI researches and drafts. The code blocks any number without a source. You approve every message. |
| Single CTA | Demander un accès à la bêta | Request beta access |

To remove from the page: the native-app use case in the headline (it becomes the first case study), the technical stack above the fold, any price, any "RGPD" badge, any figure on replies or meetings.

### Business model

The model is open, so the copy makes no price or independence claim. One constraint follows from the angle: pricing by emails sent or by credits would contradict pillar 2. If Bloom is sold, a flat price per mailbox or per team keeps the promise true.

## Hook bank

30 hooks in 5 families, French first with the English version. 8 are publishable as they stand, 22 wait for a real test, a pillar or a source.

Flags: **Test** means a real, dated example must exist before publishing. **P1, P2, P3** means the hook depends on a pillar not yet proven. **Source** means a figure to re-open and cite in the post. **Free** means publishable once the angle is confirmed.

### 1. Le refus (the code said no)

| # | FR | EN | Flag |
| --- | --- | --- | --- |
| 1 | L'IA a écrit « 43 % de crashs ». Le code a refusé l'email. | The AI wrote "43% crashes". The code refused the email. | Test, P1 |
| 2 | Voici un email que notre outil a refusé d'envoyer. | Here is an email our tool refused to send. | Test |
| 3 | 7 raisons pour lesquelles un brouillon est bloqué avant d'arriver chez vous. | 7 reasons a draft gets blocked before it reaches you. | P1, count to confirm in the audit |
| 4 | Faux « Re: » dans l'objet ? Bloqué. | Fake "Re:" in the subject line? Blocked. | P1 (rule tested in `core`) |
| 5 | Un chiffre sans source dans un email de prospection, c'est un mensonge poli. | A number without a source in a cold email is a polite lie. | Free |
| 6 | On a interdit « je me permets » dans le code. | We banned "hope this email finds you" in the code. | P1 (rule tested in `core`) |

### 2. Le reçu (show the source)

| # | FR | EN | Flag |
| --- | --- | --- | --- |
| 7 | Chaque chiffre de cet email a une source. Cliquez dessus. | Every number in this email has a source. Click it. | Test, P1 |
| 8 | Votre IA de prospection peut-elle montrer ses sources ? | Can your outreach AI show its sources? | Free |
| 9 | « J'ai vu votre post » n'est pas une preuve. | "Saw your post" is not proof. | Free |
| 10 | Une offre iOS ouverte depuis 74 jours : voilà un signal. | An iOS job open for 74 days: that is a signal. | Test (real, dated example) |
| 11 | L'IA cherche et rédige. Le code vérifie et décide. | The AI researches and drafts. The code checks and decides. | P1 |
| 12 | Demandez le reçu avant d'envoyer. | Ask for the receipt before you send. | Free |

### 3. L'anti-volume (sending less)

| # | FR | EN | Flag |
| --- | --- | --- | --- |
| 13 | 25 emails par jour. Pas un de plus. | 25 emails a day. Not one more. | P2 |
| 14 | Votre outil vous facture à l'email envoyé. Devinez ce qu'il vous pousse à faire. | Your tool bills you per email sent. Guess what it pushes you to do. | Blocked until the business model is decided |
| 15 | On a testé : 25 emails prouvés contre 1 000 emails génériques. | We tested: 25 proven emails against 1,000 generic ones. | Test (needs live sending), P2 |
| 16 | 52 % des pros suppriment un email de prospection sans l'ouvrir. | 52% of professionals delete cold outreach unread. | Source (open the Firmable report itself) |
| 17 | Envoyer moins est une fonctionnalité. | Sending less is a feature. | Free |
| 18 | Un domaine grillé coûte plus cher qu'un mois sans prospection. | A burned domain costs more than a month without outreach. | Source (no figure supports it yet) |

### 4. Côté destinataire (what the recipient sees)

| # | FR | EN | Flag |
| --- | --- | --- | --- |
| 19 | Vous aussi, vous reconnaissez un email écrit par un robot dès la première ligne ? | You also spot a robot-written email from the first line? | Free |
| 20 | 61 % se méfient des emails de prospection écrits par IA. | 61% distrust AI-written outreach. | Source (same report) |
| 21 | Le premier email n'a aucun lien. Voici pourquoi. | The first email has no link. Here is why. | P1 (rule tested in `core`) |
| 22 | Pas de pixel. On ne sait pas si vous avez ouvert, et c'est voulu. | No pixel. We do not know if you opened it, on purpose. | P3 |
| 23 | Se désinscrire en un clic, sans compte, sans motif. | Unsubscribe in one click, no account, no reason asked. | P3 |
| 24 | L'email que vous auriez aimé recevoir d'un commercial. | The email you wish a salesperson had sent you. | Test (real approved email, anonymized) |

### 5. Les règles (the rules of the game)

| # | FR | EN | Flag |
| --- | --- | --- | --- |
| 25 | Kaspr : 240 000 € d'amende CNIL. Vos contacts viennent d'où ? | Kaspr: €240,000 CNIL fine. Where do your contacts come from? | Source (CNIL, cited), P3 |
| 26 | LinkedIn peut restreindre votre compte pour automatisation. On ne l'automatise pas. | LinkedIn can restrict your account for automation. We do not automate it. | Source (LinkedIn Help, cited), P2 |
| 27 | Ce que la CNIL demande pour un email B2B, en 3 points. | What the CNIL requires for a B2B email, in 3 points. | Free (CNIL page cited) |
| 28 | Gmail tolère 0,3 % de plaintes. Après, c'est le spam. | Gmail tolerates 0.3% complaints. After that, spam. | Free (Google page cited) |
| 29 | Chaque email dit d'où viennent vos coordonnées. | Every email says where your contact details come from. | P3 |
| 30 | Trois ans sans échange : le contact est supprimé. | Three years without contact: the record is deleted. | P3, CNIL reference to verify |

Ethical rules applied: no "we tested" or "we sent" before Bloom has really sent emails (it runs in dry mode today), no invented testimonial, no real prospect named without consent, and the business model disclosed as soon as it exists.

## Slideshow templates

Three 6-slide templates, one per pillar, written in French. Each works as a LinkedIn carousel (4:5) and as a TikTok or Instagram slideshow (9:16).

| Slide | A. Le refus (pillar 1) | B. Le reçu (pillar 1) | C. Les règles (pillar 3) |
| --- | --- | --- | --- |
| 1 | L'IA a écrit ce chiffre. Le code a refusé l'email. | Votre IA de prospection peut-elle montrer ses sources ? | Ce que la CNIL demande pour un email B2B. |
| 2 | Le brouillon : « 43 % de vos avis parlent de crashs. » | « J'ai vu votre post » n'est pas une preuve. | Un : un message lié au métier de la personne. |
| 3 | Le fait sourcé disait autre chose. | Une preuve : une offre iOS ouverte depuis 74 jours. | Deux : l'origine de ses coordonnées, dite clairement. |
| 4 | La règle : aucun chiffre absent d'un fait cité. | Chaque fait a un lien et une date. | Trois : un refus possible dans chaque message. |
| 5 | L'IA corrige. Vous validez. L'email part. | Pas de fait, pas d'email. | Dans Bloom, ces trois règles sont dans le code. |
| 6 | Real screenshot: the "À valider" queue with a blocked draft and its reason | Real screenshot: a draft with its citations and the fact panel open | Real screenshot: the footer of a sent email, with data source and unsubscribe link |

The draft and the figure in template A are placeholders. They must be replaced by a draft Bloom really refused, on a prospect that is anonymized or has agreed.

Production rules:

- 12 words maximum per slide, one font (Bricolage Grotesque, already in the dashboard), espresso `#6b3a22` as the only accent.
- Slide 6 is always a real screenshot, produced with the `app-marketing-screenshots` skill from a running instance. No mock-up passed off as the product.
- Real photos or commercially licensed ones only. No realistic AI-generated people or places.
- Trending sound added by hand on TikTok and Instagram. No account farms, no automation of the posting accounts.
- No slideshow goes out before the proof audit clears the pillar it rests on.

## KPI

One KPI: beta sign-ups per 1,000 views. No numeric target before a baseline measured over the first 10 posts.

Diagnostic indicators: saves and clicks to the landing page. On LinkedIn, carousel saves and profile visits play the same role.

| Weekly reading | What it means | What to fix |
| --- | --- | --- |
| Many views, few sign-ups | The hook works, the proof or the page does not | Slide 6, the caption or the landing page |
| Few views, good sign-up rate | The proof works, the hook does not | The hooks, starting with another family |
| Few views, few sign-ups | Nothing to read yet | Keep posting until 10 posts, then change the template |
| Many saves, few clicks | Useful content, weak call to action | The caption and the CTA wording |

## Sources

All pages opened on 2026-10-10. Facts seen only in search results were excluded.

- Repo: `README.md`, `core/.../guard/MessageGuard.kt`, `app/.../static/index.html`, `.env.example` (commit 8e4546f).
- Competitors: [lemlist](https://www.lemlist.com/) and [pricing](https://www.lemlist.com/pricing), [La Growth Machine](https://lagrowthmachine.com/) and [pricing](https://lagrowthmachine.com/pricing/), [Waalaxy](https://www.waalaxy.com/fr), [tarifs](https://www.waalaxy.com/fr/tarifs) and [blog](https://www.waalaxy.com/blog/linkedin-platform-knowledge/bypass-linkedin-limit), [Clay](https://www.clay.com/) and [pricing](https://www.clay.com/pricing), [Instantly](https://instantly.ai/) and [pricing](https://instantly.ai/pricing), [11x](https://www.11x.ai/), [Artisan](https://www.artisan.co/), [AiSDR](https://aisdr.com/) and [pricing](https://aisdr.com/pricing/), [Pharow](https://www.pharow.com/), [Unify](https://www.unifygtm.com/), [Smartlead](https://www.smartlead.ai/), [Amplemarket](https://www.amplemarket.com/).
- Evidence ground: [FirstSales changelog](https://firstsales.io/changelog/ai-researches-prospects-website-before-writing/) (21 Jul 2026), [Vibe Prospecting blog](https://www.vibeprospecting.ai/blog/how-to-stop-ai-agents-hallucinating-outreach-personalization-2026-for-gtm-engineers) (8 Sep 2026), [Autobound](https://www.autobound.ai/).
- News: [Le Média de l'Investisseur](https://lemediadelinvestisseur.fr/actualite/lemlist-rachat-claap/) (22 Oct 2025), [Runtimewire](https://runtimewire.com/article/kareem-amin-clay-115m-series-d-7-1b-valuation) (9 Sep 2026), [Industry Lens](https://industry-lens.com/reports/signal-spotlight-instantly/md) (5 Aug 2026), TechCrunch on [Artisan and LinkedIn](https://techcrunch.com/2026/01/07/yes-linkedin-banned-ai-agent-startup-artisan-but-now-its-back) (7 Jan 2026), [11x](https://techcrunch.com/2025/03/24/a16z-and-benchmark-backed-11x-has-been-claiming-customers-it-doesnt-have) (24 Mar 2025) and [Artisan](https://techcrunch.com/2025/04/09/artisan-the-stop-hiring-humans-ai-agent-startup-raises-25m-and-is-still-hiring-humans) (9 Apr 2025).
- Figures: [PPC Land on the Firmable survey](https://ppc.land/52-of-professionals-delete-cold-outreach-unread-firmable-finds/) (Aug 2026), [Instantly benchmark](https://instantly.ai/report-page), [Belkins](https://belkins.io/blog/sales-follow-up-statistics), [Google sender guidelines](https://support.google.com/a/answer/81126?hl=en), [Petri on Outlook rules](https://petri.com/microsoft-outlook-email-authentication-rules/), [LinkedIn Help](https://www.linkedin.com/help/linkedin/answer/a1341387).
- CNIL: [Kaspr sanction](https://cnil.fr/fr/aspiration-de-donnees-sanction-de-240-000-euros-lencontre-de-la-societe-kaspr) (5 Dec 2024), [list of sanctions](https://cnil.fr/fr/les-sanctions-prononcees-par-la-cnil), [prospecting by email](https://www.cnil.fr/fr/la-prospection-commerciale-par-courrier-electronique) (10 June 2026).
- Reviews: Trustpilot 1 and 2 star pages for [Instantly](https://www.trustpilot.com/review/instantly.ai?stars=1&stars=2), [Waalaxy](https://www.trustpilot.com/review/waalaxy.com?stars=1&stars=2), [Clay](https://www.trustpilot.com/review/clay.com?stars=1&stars=2), [11x](https://www.trustpilot.com/review/11x.ai?stars=1&stars=2) and [Kaspr](https://fr.trustpilot.com/review/kaspr.io?stars=1&stars=2).
