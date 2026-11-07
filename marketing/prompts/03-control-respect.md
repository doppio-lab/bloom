# 03. Control and respect, true and tested

> Claude Code prompt written by the `claims-proof-audit` skill. Paste everything below the line into Claude Code, at the root of the repo. Fixes claims C10, C11, C14, C15, C17, C18 and C19 of [claims-audit.md](../claims-audit.md).

---

Start in plan mode. Read the CLAUDE.md files of the repo first. Do not write code before the plan is approved.

## Why

The marketing pillars 2 and 3 say: nothing leaves without your approval, 25 emails a day per mailbox, any reply stops the sequence, every email says where the contact's details come from, opposition by email and by domain, purge after three years. The code paths exist and the data module now has PostgreSQL tests (`data/src/test`, Testcontainers), but the claims themselves are not tested and four gaps remain:

- `data/src/test/kotlin/lab/doppio/bloom/data/SendSafetyTest.kt` builds drafts already `APPROVED` (line 34): the approval gate (`app/.../web/Routes.kt` `/drafts/{id}/approve`, only `PENDING_REVIEW`, guard run again) has no test, and `app` has no test directory.
- `app/.../Application.kt` line 97: `MAX_PER_MAILBOX_PER_DAY` has no upper bound; `data/.../db/Repository.kt` `sentTodayBy` counts `sentAt` only.
- `app/.../outreach/Orchestrator.kt` `processReply` (lines 242 to 248): a reply from an address that matches no contact and has no In-Reply-To stops nothing; an out-of-office pauses the enrollment and no code resumes it.
- `app/.../outreach/Mail.kt` `footer` (lines 71 to 75) prints a generic sentence from `LEGAL_FOOTER_FR`; the contact's own `dataSource` (for example "import de la liste Doppio", `DiscoveryRoutes.kt` line 284) never reaches the recipient.
- `Repository.isSuppressed` (lines 642 to 646) checks "@domain" entries, but no route, screen or code path writes one.
- `Routes.kt` GET `/u/{token}` (lines 224 to 233) unsubscribes at once: mail security scanners that prefetch links can unsubscribe a contact by accident.

## What to build

In layer order:

1. Data: a way to add and list a domain opposition; keep "never deleted" for the opposition list.
2. API: a route for it behind the admin authentication; the per-contact data source available to the footer.
3. Contract: the opposition by domain needs a screen. The Claude Design project Bloom is the source of truth (see CLAUDE.md): design it there first (probably next to the Liste noire), then port it.
4. App: footer, cap and out-of-office decisions below; reply matching by domain if the owner chooses it.

## Decisions for the owner

Do not settle these silently: present the options and wait.

- Daily cap: option A, hard ceiling in code (refuse a value above 25 or 30 at startup, the public claim becomes strict) / option B, keep it free and the copy says "25 par défaut".
- Footer wording when `dataSource` is free text typed by a person: show it as is / map it to a short list of public labels / keep the generic sentence.
- Out of office: option A, resume the sequence after the return date found in the message (or after N business days) / option B, keep the pause and show it in À faire so a person decides.
- Unmatched replies: option A, match by domain to an active enrollment (may stop the wrong contact's sequence in a large company) / option B, show them in À faire as unmatched and leave the sequence running.
- GET unsubscribe: option A, keep it (safest for the recipient, some prospects lost to scanners) / option B, a one-button confirmation page on GET, one-click POST kept for mail clients (RFC 8058).

## Invariants to cover with tests

- No email is sent for a draft whose status is not `APPROVED`.
- Approval is refused when the guard returns a blocking violation, including after a human edit, and refused with 409 when the draft is no longer `PENDING_REVIEW`.
- A mailbox that reached the cap sends nothing more that day.
- A matched reply stops the enrollment; a bounce stops it and suppresses the address.
- A contact whose email or domain is in the opposition list gets no draft and no email.
- The purge never deletes a row of the opposition list, and deletes a contact with no inbound exchange for three years.
- Every outgoing email carries `List-Unsubscribe`, `List-Unsubscribe-Post`, a footer, and is `text/plain`.

## Check and report, without fixing

- Whether "three years without exchange" counted on inbound only (`purgeStaleContacts`, lines 369 to 374) matches the CNIL guidance, with the link to the text.
- The exact LinkedIn terms section the README cites as 8.2, with the link.
- Any recipient-facing text that hardcodes "Doppio Lab".

## Done

The repo's CI gates pass (`./gradlew build`), including the new tests; branch and commit conventions of the repo are followed (English commit messages, no em dash, no Claude attribution).
