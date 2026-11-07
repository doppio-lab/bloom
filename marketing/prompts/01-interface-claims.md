# 01. Interface copy inside the proven claims

> Claude Code prompt written by the `claims-proof-audit` skill. Paste everything below the line into Claude Code, at the root of the repo. Fixes claims C3, C5 and C24 of [claims-audit.md](../claims-audit.md).

---

Start in plan mode. Read the CLAUDE.md files of the repo first, then `marketing/claims-audit.md` (claims C3, C5, C24). Do not write code before the plan is approved.

## Why

CLAUDE.md says interface copy must stay inside the claims marked as proven in `marketing/claims-audit.md`. The audit of 2026-10-15 found three places where the product over-claims or under-shows:

- `app/src/main/resources/static/index.html`, view Méthode, step 1 (around line 541): "Les classements App Store France de 12 catégories : 120 apps examinées par nuit". The code reads 21 genres and two charts per genre (`data/src/main/kotlin/lab/doppio/bloom/data/discovery/DiscoveryService.kt`, `chartGenres` and `chartKinds`, lines 62 to 68) and examines up to `appsPerRun = 400` apps per run (line 70, overridable by `DISCOVERY_APPS_PER_RUN` in `app/.../Application.kt` line 150).
- Same view, step 8: "Jour 0 / Jour 2 / Jour 6". The delays are business days after the previous step (`core/.../model/Models.kt` line 143, `core/.../sequence/SequenceEngine.kt` `DefaultSequences.audit`), and sending only happens Tuesday to Thursday.
- `README.md` lines 8 and 9: "chaque affirmation est reliée à un fait sourcé". The guard does not check that (claim C3, False).
- `index.html` `factList`: each fact shows its statement and a source link, but not its date. `Fact.retrievedAt` exists (`core/.../model/Models.kt` line 131) and is sent by the API. Template B of the marketing plan promises "Chaque fait a un lien et une date".

## What to build

In layer order:

1. Data: nothing. Check that `retrievedAt` is present in the JSON of `/api/drafts` and `/api/to-complete` facts.
2. API: nothing expected; report if a route drops the field.
3. Contract: the Claude Design project Bloom is the source of truth for interface copy and layout (see CLAUDE.md). Update it first: the Méthode step 1 sentence, the step 8 labels ("jours ouvrés"), and the fact line with its date in `.fact` (for example "statement · source · 08/10/2026"). Use existing classes and tokens only.
4. App: port the design changes to `index.html`, then fix the README sentence with the interim wording from the audit: "le premier email s'appuie sur un fait sourcé, chaque chiffre du corps est relié à un fait cité".

## Decisions for the owner

Do not settle these silently: present the options and wait.

- Méthode step 1 figures: option A, state the code values ("21 catégories, jusqu'à 400 apps par nuit", accurate but the default can be changed by configuration) / option B, no figures ("les classements App Store France, des centaines d'apps par nuit", never wrong, less concrete).
- Fact date format: option A, retrieval date ("relevé le 08/10/2026", what the code knows) / option B, the date of the underlying event when the statement contains one (more meaningful, needs parsing, risk of inconsistency).

## Invariants to cover with tests

- No test framework exists for `index.html`. Do not add one in this workstream. Instead, list in the plan the manual checks you will run (each view in light and dark, `?demo=1`).

## Check and report, without fixing

- Every other factual sentence of the Méthode view against the code, using the C24 row of the audit as a starting list ("vos corrections récentes servent d'exemples" is Partial: only the last 3 edited drafts that were sent).
- Any other interface string that names a number, a schedule or a limit.

## Done

The repo's CI gates pass (`./gradlew build`); the design project and the code say the same thing; branch and commit conventions of the repo are followed (English commit messages, no em dash, no Claude attribution).
