# AGENTS.md — Rules for AI Coding Agents

These rules are mandatory for any agent (or human) working on this repository.
No exceptions without an explicit override from the maintainer in the task prompt.

---

## 1. Task Workflow

- **1 task = 1 commit.** Never bundle multiple unrelated tasks into one commit.
- **Every task starts with a feature branch.** Branch off `main`:
  `git checkout -b feature/<short-task-slug>`
  Never commit directly to `main`.
- **A task ends when:**
  1. The change is complete and self-contained.
  2. `.project/PROGRESS.md` and (if structure changed) `.project/ARCHITECTURE.md` are updated.
  3. Local lightweight checks pass (see §2).
  4. One commit is made on the feature branch with a clear message (see §4).
  5. A PR is opened (or updated) for the feature branch — the title must follow `type(scope): title` (see §4). Use `gh pr create` or update an existing PR via `gh pr edit`.
- Do not start a new task on top of an unmerged feature branch. Finish/merge (or hand off for review) before starting the next one.

## 2. Local Build Policy — STRICT

- **Never** run a full/heavy local build (`cargo build --release`, full Gradle assemble, full app packaging, etc.) as part of normal task work.
- Local execution is limited to **fast, cheap checks only**:
  - `cargo check`
  - `cargo test`
  - `cargo clippy`
  - `cargo fmt --check`
  - Equivalent lightweight checks on the Kotlin/Compose side (e.g. `ktlint`, unit tests only — no full `assembleRelease`).
- If a task genuinely requires a heavy build to verify (e.g. before a release tag), stop and ask the maintainer explicitly. Do not assume it's fine.
- Rationale: local heavy builds waste time/resources and are not part of the verification loop for a single task — tests are.

## 3. Code Quality Rules

- No dead code. No commented-out code blocks left in commits.
- No TODO/stub placeholders without a linked task/issue reference. A task is either implemented for real or not started.
- Consistent formatting enforced by the formatter (`rustfmt` / `ktlint`) — run before every commit.
- Every public function, struct, trait, and module must have a doc comment explaining *what* and *why*, not just restating the signature.
- Naming must be descriptive; no single-letter names outside tight local scopes (loop indices, etc.).
- Any non-obvious algorithmic choice (e.g. why a Rope over a Piece Table, why a given dirty-region strategy) must be documented inline **and** reflected in `.project/ARCHITECTURE.md`.

## 4. Commit Messages

Conventional Commits format: `type(scope): title`

- Types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`, `ci`,
  `chore`, `bench`.
- Scope: the module or area in hand (`buffer`, `selection`, `agents`, …);
  omit only when no sensible scope exists.
- Title: short imperative summary.
- Body (optional) explains *why*, not a restatement of the diff.

Examples:
- `feat(buffer): implement rope insert/delete with split threshold`
- `fix(selection): clamp cursor at line boundaries`
- `docs(agents): adopt conventional commit format`

- **PR titles must follow the same `type(scope): title` format.** PRs are
  squash-merged, so the PR title becomes the commit message on `main`.
  A mismatched title means the merged history reads poorly.

## 5. Mandatory Documentation Updates

Every task commit must, where applicable:
- Update `.project/PROGRESS.md`: mark the task done, log what changed, note any follow-up needed.
- Update `.project/ARCHITECTURE.md`: if the task changes structure, data flow, module boundaries, or a core design decision.

A task without a corresponding progress log entry is considered incomplete, even if the code is correct.

## 6. Project Meta Files Location

- `PROGRESS.md` and `ARCHITECTURE.md` live inside the `.project/` folder at repo root.
- The **whole `.project/` folder** is listed as a single entry in `.gitignore` — do not gitignore the files individually.
  This keeps `.gitignore` clean and means anyone browsing the repo can see the folder name is intentionally excluded, without the working docs themselves being versioned/public.
- These files are working documents for agents/maintainer — they are not part of the committed history, but they must always exist and be current in the working tree.

## 7.5 CI, Merging, and `gh` Usage — STRICT

- CI runs the required checks defined in `.github/workflows/ci.yml` on every
  PR. `check` is a debug-only build for sanity — never a release build
  (consistent with §2); the crate itself denies `missing_docs` and broken
  intra-doc links.
- The agent may use `gh` freely for **read-only and PR-creation** actions:
  checking CI status (`scripts/ci-status.sh`), opening a PR
  (`scripts/open-pr.sh`), downloading CI artifacts
  (`scripts/download-apk.sh`), reading issues/PRs, commenting.
- **The agent must never merge a PR, under any circumstance** — not
  `gh pr merge`, not the GitHub UI equivalent, not by editing branch
  protection to route around it. Only the repo owner approves and merges.
  This holds even if all checks pass and the change looks trivial.
- `main` is protected at the repo level (`scripts/setup-branch-protection.sh`,
  run once by the owner) — PRs, 1 approval, and all 3 checks are required,
  direct pushes are disabled. `.opencode/opencode.json` additionally denies
  the `gh pr merge`/force-push bash patterns at the tool-permission level, so
  the agent is blocked from even attempting them. Both are backstops, not a
  substitute for the rule above: follow the rule even where the platform or
  the tool config would technically stop you anyway.
- Use `scripts/local-check.sh` before pushing — it's the token-cheap way to
  catch what CI would catch, without waiting on/parsing full CI logs.

## 8. Design Work

- For any Compose UI work, load the `material3-expressive` skill
  (`.opencode/skills/material3-expressive/SKILL.md`) — it has the actual M3
  Expressive spec (color/type/shape/motion system, current component set).
  Don't default to generic/outdated Material patterns.

## 7. When Unsure

If a task is ambiguous, under-specified, or touches a decision not yet recorded in `ARCHITECTURE.md`, stop and ask rather than guessing — especially for anything affecting the buffer/data model, the public API surface, or IME handling.

## 9. Communication Language

- The maintainer may write prompts in Arabic; this does **not** change the agent's output language.
- The agent must **always reply in English** — never Arabic or any other language.
- Applies everywhere: chat responses, commit messages, PR titles/bodies, code comments, and documentation.

## 10. Output Style — No Machine Signals

- Never present as, mention, or compare against AI systems, models, or providers in any output. No model names, no "AI-generated" labels or footers, no assistant-style disclaimers or meta commentary.
- Chat replies read like a colleague talking: plain and short, no workflow narration (no task/phase/status bookkeeping, no checklists of what was touched) unless the maintainer asks for it.
- Applies everywhere §9 applies: chat, commits, PRs, code comments, docs.

## 11. Porting Source of Truth

- This repo is a from-scratch rewrite of an old project (`NoteScribe`), kept as a
  read-only reference one level up at `../reference/NoteScribe-Android-stable/`.
  A file-by-file map of what old code corresponds to what new work is at
  `../reference/PORTING_NOTES.md`.
- Before implementing any ported behavior, open the exact old file(s) named for
  that concept in `PORTING_NOTES.md` and read them in full. Do not port from
  memory of "how text editors usually do X" and do not port from a summary —
  read the actual old source.
- If a task touches a concept with no entry in `PORTING_NOTES.md`, stop and ask
  which old file (if any) is the reference, rather than guessing or inventing
  behavior that was never in the old code.
- New code does not have to be a literal translation — Rust/Compose idioms,
  data structures (e.g. a rope instead of a `StringBuilder` per line), and
  API shapes can and should differ from the Java original. What must not
  differ without a documented reason is *behavior*: wrapping rules, scroll
  thresholds, cursor placement edge cases, etc., unless the task explicitly
  says to change them.
