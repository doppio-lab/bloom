# 04. Let another team configure Bloom

> Claude Code prompt written by the `claims-proof-audit` skill. Paste everything below the line into Claude Code, at the root of the repo. Fixes claim C21 of [claims-audit.md](../claims-audit.md).

---

Start in plan mode. Read the CLAUDE.md files of the repo first. Do not write code before the plan is approved.

## Why

Bloom is positioned as a product for other teams, but it is wired to one company. The audit of 2026-10-15 counted 50 occurrences of "doppio" outside package names, in 14 files, more than on 2026-10-10. Until a second team can run Bloom without editing Kotlin, no public copy may say "pour votre équipe".

- `agents/.../Prompts.kt` (19 occurrences): studio description and voice.
- `core/.../references/ReferenceLibrary.kt` (9): ten client references.
- `app/.../Application.kt` (6): login "doppio" (line 188), authentication realm, footer defaults, `SENDER_NAME` default.
- `core/.../model/Models.kt` (3): the `Offer` enum and its labels; `core/.../sequence/SequenceEngine.kt` `DefaultSequences` hardcodes the offers.
- `core/.../guard/MessageGuard.kt` line 142: the only allowed link domain, doppio-lab.com.
- `app/.../web/Routes.kt`: the unsubscribe confirmation page names Doppio Lab.
- `app/src/main/resources/static/index.html` (5) and `docs/pipeline.html` (3).
- One each in `DiscoveryRoutes.kt`, `Sources.kt`, `Scoring.kt`, `DiscoveryRules.kt`, `ResearchAgent.kt`, `ReplyAndCoachAgents.kt`.

## What to build

Keep it incremental: the first step changes no behavior for the current installation.

1. Data: where the profile lives (see decisions).
2. API: login name, realm and recipient-facing texts read the profile.
3. Contract: one tenant profile type in `core` (company name, description, voice notes, offers, references, allowed link domains, sequences). The current Doppio Lab values become the default profile, so existing tests stay green unchanged. Interface strings that name the company follow CLAUDE.md: design project first.
4. App: `MessageGuard`, `ReferenceLibrary`, `Scoring`, `DefaultSequences` and `Prompts.kt` read the profile instead of constants.

## Decisions for the owner

Do not settle these silently: present the options and wait.

- Profile storage: option A, a file mounted next to `.env` (simplest, fits self-hosting) / option B, database tables with an admin screen (needed for a hosted product, much larger) / option C, file now, tables later.
- Offers and sectors are enums stored by name in PostgreSQL. Give the real cost of turning them into data, and whether a first version can keep sectors fixed and make only offers, references and voice configurable.
- Discovery sources (App Store France charts, mobile job posts) are specific to selling native app work: keep discovery as a Doppio Lab module and make CSV import the generic entry point, or design a source interface now.
- One tenant per installation, or several tenants in one database.

## Invariants to cover with tests

- With the default profile, every existing test passes unchanged.
- With a second, fictional profile, the guard allows that profile's link domain and blocks doppio-lab.com.
- A reference of one profile can never be cited under another profile.
- No prompt sent to the model contains "Doppio" when the profile is the fictional one.

## Check and report, without fixing

- Every remaining occurrence of "doppio" outside package names, with file and line, sorted into: must become configuration, may stay (comments), unclear.
- The package name `lab.doppio.bloom`: list what renaming would touch, do not rename.

## Done

The repo's CI gates pass (`./gradlew build`), including the new tests; branch and commit conventions of the repo are followed (English commit messages, no em dash, no Claude attribution).
