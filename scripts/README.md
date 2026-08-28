# Scripts — not yet written

AGENTS.md and the `.opencode/command/` files reference these by name, but
their contents weren't provided, so they're not included here. The commands
that call them will fail until they exist:

- `local-check.sh` — run cargo fmt --check / clippy / check / test (+ Kotlin
  equivalents), used by `/check` (AGENTS.md §2)
- `new-task.sh` — create + switch to `feature/<slug>` off a clean `main`,
  used by `/new-task` (AGENTS.md §1)
- `open-pr.sh` — push branch + `gh pr create`, never merge, used by
  `/open-pr` (AGENTS.md §1, §7.5)
- `ci-status.sh` — compact CI check summary, used by `/ci-status`
  (AGENTS.md §7.5)
- `err-compact.sh`, `test-summary.sh` — referenced by `/ci-status` as
  follow-ups on failure
- `download-apk.sh` — download CI artifacts (AGENTS.md §7.5)
- `setup-branch-protection.sh` — one-time, run by the repo owner
  (AGENTS.md §7.5)

Write these before the first real task — nothing else in the workflow works
without them.
