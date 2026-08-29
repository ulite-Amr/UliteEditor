#!/usr/bin/env bash
set -euo pipefail

# open-pr.sh — push the current feature branch and open a PR into main
# (AGENTS.md §1, §7.5). Never merges: merging is the maintainer's decision
# only. The PR title defaults to the latest commit's subject (already
# type(scope): title); the body to a per-commit summary from main. Pass
# positional title/body to override either.

title="${1:-}"
body="${2:-}"

branch="$(git branch --show-current)"
if [[ "$branch" == "main" || "$branch" == "HEAD" ]]; then
  echo "error: must run from a feature branch (on $branch)" >&2
  exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is not clean; commit or stash first" >&2
  exit 1
fi

git push -u origin "$branch"

if [[ -z "$title" ]]; then
  title="$(git log -1 --pretty=%s)"
fi
if [[ -z "$body" ]]; then
  body="$(git log origin/main..HEAD --pretty='- %s%n  %b' | sed '/^[[:space:]]*$/d')"
fi

args=(--base main --head "$branch" --title "$title")
if [[ -n "$body" ]]; then
  args+=(--body "$body")
fi

gh pr create "${args[@]}"
echo "PR opened — do NOT merge it (maintainer only, AGENTS.md §7.5)"