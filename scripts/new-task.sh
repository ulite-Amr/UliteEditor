#!/usr/bin/env bash
set -euo pipefail

# new-task.sh — create and switch to feature/<slug> off an up-to-date main
# (AGENTS.md §1). Refuses to run from a dirty tree, from another branch, or
# when the branch already exists — a task must branch off main so PRs stay
# independent and never stack.

slug="${1:-}"
if [[ -z "$slug" ]]; then
  echo "usage: $0 <task-slug (kebab-case)>" >&2
  exit 2
fi
if [[ ! "$slug" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  echo "error: slug must be kebab-case (lowercase letters, digits, hyphens)" >&2
  exit 2
fi

branch="feature/$slug"

if [[ "$(git branch --show-current)" != "main" ]]; then
  echo "error: run from main (currently on $(git branch --show-current))" >&2
  exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is not clean; commit or stash first" >&2
  exit 1
fi
if git rev-parse --verify "$branch" >/dev/null 2>&1; then
  echo "error: branch $branch already exists" >&2
  exit 1
fi

git fetch origin --quiet
if git rev-parse --verify origin/main >/dev/null 2>&1; then
  git pull --ff-only origin main
fi

git checkout -b "$branch" main
echo "-> $branch (off main)"