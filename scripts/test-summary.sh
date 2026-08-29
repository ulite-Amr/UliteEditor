#!/usr/bin/env bash
set -euo pipefail

# test-summary.sh — summarize the current branch's latest CI test results:
# the 'test result:' lines (pass/fail counts) and any failures, stripped to
# plain text. Exits non-zero when the run's tests did not pass.

branch="$(git branch --show-current)"
run="$(gh run list --branch "$branch" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
if [[ -z "$run" || "$run" == "null" ]]; then
  echo "no CI runs for branch $branch" >&2
  exit 3
fi

gh run view "$run" --log 2>/dev/null \
  | sed -E 's/^[^\t]*\t[^\t]*\t[0-9]{4}-[0-9]{2}-[0-9]{2}[^ ]* //' \
  | sed -E 's/\x1B\[[0-9;]*[mK]//g' \
  | grep -E 'test result:|panicked at|failures:' \
  || true

if gh run view "$run" --log 2>/dev/null | grep -q 'test result: ok'; then
  echo "ok: tests passed"
else
  echo "tests did not pass — ./scripts/err-compact.sh for details" >&2
  exit 1
fi