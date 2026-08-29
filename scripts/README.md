# Repo Scripts

Shell helpers for the agent workflow defined in `AGENTS.md` (§1 task flow,
§7.5 CI/`gh`). They keep every repo operation reproducible and safe —
**one script is the exception and is called out below**: `build-ffi.sh` is
the FFI *producer* and runs a real Rust/NDK build, but only on a machine
built for it (the android CI job); the operator works from a mobile device
and can never run it locally (root `AGENTS.md` Rule #1 overrides the repo's
§2 quick-check allowance). Every other script deals in repo *state* and
GitHub data, never compilers or test runs.

Every script is conservative: read-only unless its job is to create (a
branch, a PR), refuses to run in the wrong state, and prints a pointer to the
correct tool instead of papering over problems.

## `local-check.sh`

Pre-push sanity gate for the current feature branch. Validates repo *state*
only: correct branch (never `main`), clean working tree, not behind
`origin/main`. Then delegates the real verification to CI via `ci-status.sh`.
Exit codes: `0` ready, `1` not ready.

## `new-task.sh <task-slug>`

Starts a task per §1: refetches `main`, fast-forwards it, and creates +
switches to `feature/<slug>` (kebab-case) off it. Refuses on a dirty tree,
from a non-`main` branch, or when the branch already exists — this is what
keeps PRs independent instead of stacked.

## `open-pr.sh [title] [body]`

Pushes the current feature branch and creates a PR into `main`. **Never
merges** — that is the maintainer's call alone. The title defaults to the
latest commit subject (already `type(scope): title`); the body to a
per-commit summary of the branch against `main`. Positional `title`/`body`
override both. Requires a clean tree.

## `ci-status.sh`

Compact CI status for the current branch's open PR — one line per required
check, no raw logs. On failure it points at `err-compact.sh` (compile/lint
diagnostics) and `test-summary.sh` (test results) instead of dumping CI
output.

## `err-compact.sh`

Dumps only the failure-relevant lines of the latest CI run for the current
branch: strips the `<job>\t<step>\t<timestamp> ` prefix and ANSI escapes,
emits plain error/warning lines. Read-only.

## `test-summary.sh`

Summarizes the latest CI run's test results — the `test result:` pass/fail
lines and any failures — stripped to plain text. Exits non-zero when the run
left tests failing.

## `download-apk.sh`

Downloads the current branch's latest CI artifacts into `dist/` (gitignored).
Today the Rust-only CI produces no APK, so it reports that and exits `0`
rather than pretending; revisit when the Gradle/Compose side uploads APKs.

## `setup-branch-protection.sh`

One-time repo setup, run by the **owner** (§7.5): enables branch protection
on `main` (PRs required, 1 approving review, the required status checks,
direct pushes disabled). Refuses to run until `.github/workflows/ci.yml`
exists on `origin/main`, because protecting before the checks exist would
lock everyone out. Idempotent — re-running reapplies the same settings.

## `build-ffi.sh`

The UniFFI bridge **producer** — the single exception to the repo's
no-local-build rule. It runs `uniffi-bindgen generate` (Kotlin bindings) and
`cargo ndk build --release` (per-ABI cdylib) for the `:editor` module,
writing into the gitignored `editor/build/generated/ffi/` (bindings under
`kotlin/uniffi/…`, shared objects under `jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/`).
Requires `uniffi-bindgen` (match the crate's `uniffi` version: 0.32.0) and
`cargo-ndk` on PATH. Never run on the operator's mobile dev box — the android
CI job installs the tooling and invokes it before any Gradle task.

---

The local (gitignored) opencode commands in `.opencode/command/` call these
directly: `/check`, `/new-task`, `/open-pr`, `/ci-status`, `/err-compact`,
`/test-summary`, `/download-apk`, `/protect-main`.