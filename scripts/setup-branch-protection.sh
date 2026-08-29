#!/usr/bin/env bash
set -euo pipefail

# setup-branch-protection.sh — one-time repo setup, run by the owner
# (AGENTS.md §7.5). Enables branch protection on main: pull requests
# required, no required review (the maintainer delegates merging, and the
# agent runs the green-checks + diff-preview protocol), the
# lint/test/check/build/docs status checks required, direct pushes
# disabled. Refuses to run until the checks that will become required
# actually exist (ci.yml + android.yml merged to main), because protecting
# before the checks exist would block every PR. Re-running re-applies the
# same settings (idempotent).

repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"

if ! gh api "repos/${repo}/contents/.github/workflows/ci.yml?ref=main" --jq '.name' >/dev/null 2>&1; then
  echo "error: ci.yml is not on origin/main yet — merge the CI PR first" >&2
  exit 1
fi
if ! gh api "repos/${repo}/contents/.github/workflows/android.yml?ref=main" --jq '.name' >/dev/null 2>&1; then
  echo "error: android.yml is not on origin/main yet — merge the Android CI PR first" >&2
  exit 1
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
cat > "$tmp" <<'JSON'
{
  "required_status_checks": {
    "strict": false,
    "contexts": ["lint", "test", "check", "build", "docs"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": false,
    "require_code_owner_reviews": false
  },
  "restrictions": null
}
JSON

gh api -X PUT "repos/${repo}/branches/main/protection" \
  -H "Accept: application/vnd.github+json" \
  --input "$tmp" >/dev/null

echo "main protection applied on $repo (PRs required, 0 approvals, lint/test/check/build/docs required)"