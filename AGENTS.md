# Bloom

Doppio Lab's prospecting tool. Kotlin, Koog, Ktor, PostgreSQL. See `README.md` for modules and how to run it.

## UI and UX: the Claude Design project is the source of truth

The interface is designed in the Claude Design project **Bloom**:
https://claude.ai/design/p/4aa99dff-b97a-4abb-a24f-a32b2d73243e

The code follows the design, never the other way round.

| Design file | Implemented by |
| --- | --- |
| `Bloom.dc.html` (version 4, on Thermos) | `app/src/main/resources/static/index.html` (the design's inline styles become CSS classes, value for value) |
| `thermos/tokens/` | the token block at the top of the `<style>` block of `index.html` (light on `:root`, dark on `.dark`) |

Rules for any change that touches layout, components, interface copy or visual tokens:

1. Read the design first (`mcp__claude-design__read_file` on project `4aa99dff-b97a-4abb-a24f-a32b2d73243e`, start with its `README.md`).
2. If the design already covers the change, port it to the code as it is. Do not reinterpret it.
3. If it does not, update the design project first, then the code, in the same piece of work. Never ship a UI change that exists only in the repo.
4. Tokens come from the Thermos design system (copied into `thermos/` of the Bloom design project). Do not add a color, font or radius in Bloom.
5. Accepted differences are listed under "Known differences" in the design project's `README.md`. Anything else that differs is a bug in the code.

Not designed yet, so the code is the only reference for now: `docs/pipeline.html`, the unsubscribe page in `Routes.kt`. Design them in the project before reworking them.

## Product claims

`marketing/positioning.md` and `marketing/claims-audit.md` define what the interface and the marketing may claim. Interface copy must stay inside the claims marked as proven there.
