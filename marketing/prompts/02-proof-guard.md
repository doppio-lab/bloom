# 02. Close the gaps in the proof guard

> Claude Code prompt written by the `claims-proof-audit` skill. Paste everything below the line into Claude Code, at the root of the repo. Fixes claims C2, C4, C6, C7 and C9 of [claims-audit.md](../claims-audit.md).

---

Start in plan mode. Read the CLAUDE.md files of the repo first. Do not write code before the plan is approved.

## Why

Bloom's public promise is "no cold email leaves without proof: the first email rests on a sourced fact and every number is traced to a cited fact". `MessageGuard.kt` has not changed since the audit of 2026-10-10, and four gaps remain:

- `core/src/main/kotlin/lab/doppio/bloom/core/guard/MessageGuard.kt` (`check`, lines 115 to 117): numbers are scanned in the body only, never in the subject line. 1, 2, 3, 15, 20 and 30 always pass (line 59).
- `agents/src/main/kotlin/lab/doppio/bloom/agents/ResearchAgent.kt` (`recordQuote`, line 206): the fact statement is "$meaning («$quote»)". The meaning is written by the model and counts as provenance, so a number invented in it passes the guard.
- `MessageGuard.kt` line 104: a cited fact is required only when `stepIndex == 0`. Follow-ups can make claims with no fact.
- `app/src/main/kotlin/lab/doppio/bloom/app/outreach/Mail.kt` (`send`, line 106) adds "Re: " to every follow-up, while the guard rule `objet` blocks a fake "Re:" in drafts.
- Tests: `core/src/test/kotlin/lab/doppio/bloom/core/CoreTest.kt` has 7 guard tests; none covers "Re:", links, emojis or numbers in the subject. `agents` has no tests at all, so `recordQuote` (word for word match) is untested.

## What to build

In layer order:

1. Data: no migration expected. If provenance needs `Fact.excerpt` to be filled for web facts, say so.
2. Core: scan the subject line for numbers with the same rule as the body. Number provenance comes from verbatim material only (`Fact.excerpt` for web facts, statements built by code for App Store, job and registry facts); the model-written meaning stays for display.
3. Agents: the smallest change in `recordQuote` that separates meaning from quote, and a first test file for `agents` covering the word for word rule.
4. App: align `Mail.kt` with the decision on "Re:".

## Decisions for the owner

Do not settle these silently: present the options and wait.

- Follow-ups: option A, at least one cited fact on every email (safest for the promise, heavier short follow-ups) / option B, a fact only when the follow-up contains a number or names the prospect's product (lighter, needs a heuristic) / option C, unchanged, and the public claim stays limited to the first email.
- "Re:" on follow-ups: option A, keep the threading headers and send the original subject without the prefix / option B, keep the prefix and drop the public claim that a fake "Re:" is blocked (hook 4).
- Always-allowed numbers (1, 2, 3, 15, 20, 30): keep, shrink, or tie them to the offer labels.
- Public count of rules: "6 groupes de règles bloquantes" or "10 règles bloquantes" (the audit lists them under C7). Pick one so hook 3 stays exact.

## Invariants to cover with tests

- A number present only in the subject and in no cited fact is blocked.
- A number present only in a web fact's model-written meaning is blocked; the same number in its verbatim excerpt passes.
- A web quote not found word for word on the page is refused by `recordQuote`.
- A subject starting with "Re:" in a draft is blocked; a link in the body of the first email is blocked; an emoji is blocked.
- The follow-up rule chosen, with one passing and one blocked case.
- Existing tests stay green.

## Check and report, without fixing

- Whether any path creates or sends a draft without `MessageGuard.check`.
- Whether a human edit at approval can remove every citation of a follow-up.
- Whether `reference.proof` and `offer.label` (counted as provenance, lines 110 to 114) can carry a number the prospect never saw sourced.

## Done

The repo's CI gates pass (`./gradlew build`, run by `.github/workflows/build.yml`), including the new tests; branch and commit conventions of the repo are followed (English commit messages, no em dash, no Claude attribution).
