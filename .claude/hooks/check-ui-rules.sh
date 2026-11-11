#!/usr/bin/env bash
# PostToolUse hook (Edit|Write): after a change to index.html or README.md, run the mechanical rule checks
# (Thermos tokens only, no claim the audit marks False). A failure goes back to the agent (exit 2).
input="$(cat)"
file="$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')"
case "$file" in
  */app/src/main/resources/static/index.html|*/README.md) ;;
  *) exit 0 ;;
esac
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
if out="$(./gradlew :app:test --tests '*ProjectRulesTest*' -q --console=plain 2>&1)"; then
  exit 0
fi
printf '%s\n' "$out" | grep -vE '^> Task|^$|^\* Try|^> Run with|^BUILD' | head -15 >&2
grep -ho 'message="[^"]*"' app/build/test-results/test/*ProjectRulesTest.xml 2>/dev/null | head -3 >&2
echo "Rule check failed (see AGENTS.md, UI and product claims). Fix the change or the design project, then retry." >&2
exit 2
