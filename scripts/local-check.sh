#!/usr/bin/env bash
set -euo pipefail

# local-check.sh — pre-push sanity check for the current feature branch.
#
# The operator runs on a mobile device and CI is the only verification
# path (root AGENTS.md Rule #1 overrides the repo's §2 allowance for local
# toolchain runs). So this never executes the build toolchain: it checks
# repo *state* (branch, cleanliness, sync with origin, open PR) and hands
# actual verification to GitHub CI via ./scripts/ci-status.sh.

fail=0

branch="$(git branch --show-current)"
if [[ "$branch" != "main" ]]; then
  :
else
  echo "x on main — start a task with ./scripts/new-task.sh" >&2
  fail=1
fi

if [[ -z "$(git status --porcelain)" ]]; then
  :
else
  echo "x uncommitted changes in the working tree" >&2
  fail=1
fi

if git rev-parse --verify origin/main >/dev/null 2>&1; then
  git fetch origin --quiet
  behind="$(git rev-list --left-right --count origin/main...HEAD | awk '{print $1}')"
  if [[ "$behind" != "0" ]]; then
    echo "x branch is $behind commit(s) behind origin/main" >&2
    fail=1
  fi
fi

if [[ "$fail" -eq 1 ]]; then
  echo "local state is NOT ready — fix the items above, then re-run" >&2
  exit 1
fi

echo "ok: local state is clean"
if ./scripts/ci-status.sh; then
  echo "ok: all CI checks pass"
else
  echo "CI reports failures — fix and re-push before opening/updating the PR" >&2
  exit 1
fi