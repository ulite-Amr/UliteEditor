#!/usr/bin/env bash
set -euo pipefail

# ci-status.sh — compact CI status for the current branch's open PR.
# One line per required check; no raw logs. On failure, points at
# ./scripts/err-compact.sh (compile/lint) and ./scripts/test-summary.sh
# (tests) instead of dumping full CI logs.

pr="$(gh pr view --json number --jq .number 2>/dev/null || true)"
if [[ -z "$pr" ]]; then
  echo "no open PR for the current branch" >&2
  exit 3
fi

if gh pr checks "$pr" 2>/dev/null; then
  echo "ok: all checks pass"
else
  echo "some checks failed — ./scripts/err-compact.sh (compile/lint) or ./scripts/test-summary.sh (tests) for details" >&2
  exit 1
fi