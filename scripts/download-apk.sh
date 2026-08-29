#!/usr/bin/env bash
set -euo pipefail

# download-apk.sh — download the current branch's latest CI artifacts
# (debug APK) into dist/. The Rust-only CI does not produce an APK yet, so
# this reports that and exits cleanly rather than pretending. Revisit when
# the Compose/Gradle side lands and starts uploading APKs.

branch="$(git branch --show-current)"
run="$(gh run list --branch "$branch" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
if [[ -z "$run" || "$run" == "null" ]]; then
  echo "no CI runs for branch $branch" >&2
  exit 3
fi

mkdir -p dist
if ! gh run download "$run" --dir dist 2>/dev/null; then
  echo "no artifacts on run #$run — the current CI has no APK-producing job yet"
  exit 0
fi
echo "artifacts downloaded to dist/"