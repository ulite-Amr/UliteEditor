#!/usr/bin/env bash
set -euo pipefail

# err-compact.sh — compact the latest CI run's diagnostic output for the
# current branch: strips the '<job>\t<step>\t<timestamp> ' log prefix and
# ANSI codes, keeps only the plain error/warning lines. Read-only.

branch="$(git branch --show-current)"
run="$(gh run list --branch "$branch" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
if [[ -z "$run" || "$run" == "null" ]]; then
  echo "no CI runs for branch $branch" >&2
  exit 3
fi

gh run view "$run" --log-failed 2>/dev/null \
  | sed -E 's/^[^\t]*\t[^\t]*\t[0-9]{4}-[0-9]{2}-[0-9]{2}[^ ]* //' \
  | sed -E 's/\x1B\[[0-9;]*[mK]//g' \
  | grep -vE '^\s*$' \
  || true