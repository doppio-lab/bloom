# 05. Scripted screenshots of the dashboard

> Claude Code prompt written by the `claims-proof-audit` skill. Paste everything below the line into Claude Code, at the root of the repo. Produces slide 6 of templates A, B and C of [positioning.md](../positioning.md); see the screenshot inventory in [claims-audit.md](../claims-audit.md).

---

Start in plan mode. Read the CLAUDE.md files of the repo first. Do not write code before the plan is approved. Run after prompt 01, so no capture shows copy the audit marked as over-claiming.

## Why

Every marketing slideshow for Bloom ends on a real screenshot, and the repo has no way to produce one: no Playwright, no snapshot tests, CI builds and tests only (`.github/workflows/build.yml`). The dashboard is a single page, `app/src/main/resources/static/index.html`, with five views (À faire, Prospects, Carnet, Résultats, Méthode), light and dark themes, and a built-in example dataset (`DEMO`) shown only when the page is opened with `?demo=1`, with a visible "Données d'exemple" pill. That is the honest base: repeatable, no real prospect on screen.

- `index.html` `DEMO` and `load()`: the example data and the `?demo=1` switch.
- `index.html` `#hash` routes (`#today`, `#prospects`, `#book`, `#results`, `#method`): one URL per view.

## What to build

In layer order:

1. Data: review `DEMO` so that one draft is blocked for an unsourced number (template A), one passes with its citations and the sources panel open (template B), one LinkedIn note waits, and the deliverability gauges stay under their thresholds. Every company and person stays fictional.
2. API: none. The capture runs on the static page without a backend.
3. Contract: stable file names per view, theme and size, in a folder ignored by git (under `marketing/screenshots/`, see `marketing/.gitignore`).
4. App: one capture script, one documented command, outside the Gradle build. Sizes 1440x900 and 390x844, light and dark, per view, plus the states the templates need (sources panel open, blocked draft in view).

## Decisions for the owner

Do not settle these silently: present the options and wait.

- Tool: Playwright (obvious, adds a Node dependency) or headless Chrome over the DevTools protocol (no dependency, more code).
- English interface for the second market: option A, a string table and `?lang=en` (the design project must cover it first) / option B, French captures only for now.
- The "Données d'exemple" pill: keep it in captures (honest label) or hide it and say "exemple" in the caption.
- Captures of a real instance in dry mode (a draft really refused, template A): needs seeded PostgreSQL data instead of `DEMO`.

## Invariants to cover with tests

- The capture fails if the page is not in example mode, so real data never ends up in a marketing image.
- The capture fails if a request leaves the page, apart from fonts.
- Two runs produce identical file names.

## Check and report, without fixing

- Whether any name in `DEMO` matches an existing French company or trademark (search before answering).
- Which views look empty or broken at 390 pixels wide.
- Whether template C (the footer of a sent email) can be produced without a real send.

## Done

The script runs from a clean checkout with one documented command, `./gradlew build` still passes, and branch and commit conventions of the repo are followed (English commit messages, no em dash, no Claude attribution).
