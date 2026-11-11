# Bloom

Doppio Lab's prospecting tool. Kotlin, Koog, Ktor, PostgreSQL. See `README.md` for modules and how to run it.

## Where things go

Dependencies point inward: `app` -> `data`, `agents` -> `core`. `core` is plain Kotlin (no Ktor, Exposed, Koin or Koog) and the compiler enforces it. Wiring is Koin, in `app` only.

| You are adding | It goes in |
| --- | --- |
| A business rule or a step of the workflow | a use case in `core/.../usecase/`, taking ports in its constructor, with a test using the fakes |
| Something the core needs from outside (store, source, mail, LLM role) | an interface in `core/.../port/`, plus its fake in `core/src/testFixtures/.../usecase/Fakes.kt` |
| Its PostgreSQL or HTTP implementation | `data/.../persistence/<aggregate>/` (Exposed, new Flyway migration `V<n>__*.sql`, never edit an applied one) or `data/.../sources/{facts,discovery}/` |
| An LLM agent, prompt or structured output | `agents/` (depends on core only, the `PromptExecutor` is injected) |
| An HTTP route | `app/.../web/Routes.kt` or `DiscoveryRoutes.kt`, thin: parse, call one use case, map to a DTO in `Dtos.kt` |
| Binding of a new component | the right module in `app/.../di/Modules.kt`; `ModulesTest` fails if a dependency is missing |

Never put logic in a route or an adapter that a use case could hold: that is what makes it untestable without a database.

## Build, test, run

- Fast loop (about 10 s, no Docker): `./gradlew :core:test :agents:test :app:test`. Add `:data:test` for PostgreSQL (Testcontainers, about 15 s).
- Everything CI runs: `./gradlew build`. Run it before saying a change is done. CI (`.github/workflows/build.yml`) runs exactly this.
- OrbStack: `data/build.gradle.kts` points Testcontainers at `~/.orbstack/run/docker.sock` when `DOCKER_HOST` is unset, so no manual variable is needed. If Docker is not running, `:data:test` fails with "Could not find a valid Docker environment": start it, do not skip the tests.
- Tests: Kotlin `kotlin.test`, French backtick names describing the behaviour. Use cases and routes use the in-memory fakes, `data` tests use the real database and recorded HTTP responses (`MockEngine`), never the network.
- Run the app: `docker compose up -d db && ./gradlew :app:run` (needs `.env`, see `README.md`). `DRY_RUN=true` sends nothing. Discovery and research call the paid LLM: do not trigger them to verify a change that does not touch them.
- Never read or print `.env` (secrets). `.env.example` lists every variable.

## Checking an interface change

The dashboard sits behind basic auth. To see it without the password: `scripts/ui-demo.sh`, then open `http://localhost:8099/?demo=1` (example data, no server). `ProjectRulesTest` (in `:app:test`) fails on a colour literal or font outside the Thermos tokens, and on a sentence that `marketing/claims-audit.md` marks False. A hook runs it after edits to `index.html` and `README.md`. When you lift or add a False claim, edit the list in that test.

## Definition of done

1. `./gradlew build` passes (with `:data:test` included).
2. New behaviour has a test at the lowest level that can see it (use case before route before database).
3. UI change: the design project was read or updated first, and `?demo=1` was looked at.
4. No em dash, English comments and commit messages, no Claude sign-off (global rules).

## UI and UX: the Claude Design project is the source of truth

The interface is designed in the Claude artifact **Bloom** (the former Claude Design project
`4aa99dff-b97a-4abb-a24f-a32b2d73243e`, moved to claude.ai and now read-only at its old address):
https://claude.ai/code/artifact/0065aaf9-d151-4f9c-841a-c3297b9f404c

The code follows the design, never the other way round.

| Design file | Implemented by |
| --- | --- |
| `Bloom.dc.html` (version 4, on Thermos) | `app/src/main/resources/static/index.html` (the design's inline styles become CSS classes, value for value) |
| `thermos/tokens/` | the token block at the top of the `<style>` block of `index.html` (light on `:root`, dark on `.dark`) |

Rules for any change that touches layout, components, interface copy or visual tokens:

1. Read the design first (Artifact tool, `action: "read"` on the URL above with `path: "project/README.md"`, then `project/Bloom.dc.html`). Design changes are published to the same URL.
2. If the design already covers the change, port it to the code as it is. Do not reinterpret it.
3. If it does not, update the design project first, then the code, in the same piece of work. Never ship a UI change that exists only in the repo.
4. Tokens come from the Thermos design system (copied into `thermos/` of the Bloom design project). Do not add a color, font or radius in Bloom.
5. Accepted differences are listed under "Known differences" in the design project's `README.md`. Anything else that differs is a bug in the code.

Not designed yet, so the code is the only reference for now: `docs/pipeline.html`, the unsubscribe page in `Routes.kt`. Design them in the project before reworking them.

## Product claims

`marketing/positioning.md` and `marketing/claims-audit.md` define what the interface and the marketing may claim. Interface copy must stay inside the claims marked as proven there.
