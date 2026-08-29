# UliteEditor

A privacy-respecting, performance-first text editor for Android, split into a
Rust text engine and a Compose UI layer.

**Status: early development.** The core text engine (`ulite-editor-core`) is
feature-complete and fully tested; the Compose editor layer and the
Rust–Kotlin bridge are under active construction.

## Repo layout

- `crates/ulite-editor-core` — the text engine: buffer, cursor movement, line
  wrapping, hit-testing, scrolling, and bidi-aware text-direction heuristics.
  UI-free by design; rendering, animation, and text measurement live in the
  UI layer.
- `editor/` — Android library module: editable text surface, IME handling,
  and theme (Compose).
- `app/` — Android application module that wires the editor library into a
  launcher activity.
- `scripts/` — workflow helpers for CI and repo housekeeping (see
  `scripts/README.md`).

## Building and testing

Rust core (toolchain pinned in `rust-toolchain.toml`):

```sh
cargo fmt --all -- --check
cargo clippy --all-targets --all-features -- -D warnings
cargo test --all-targets --all-features
cargo doc --no-deps --all-features
```

Android app (requires JDK 17 and an Android SDK):

```sh
gradle ktlintCheck
gradle :editor:testDebugUnitTest :app:lintDebug :editor:lintDebug :app:assembleDebug
```

## CI

Every pull request runs five checks — `lint`, `test`, `check`, and `docs` for
the Rust core (`.github/workflows/ci.yml`), plus `build` for the Android side
(`.github/workflows/android.yml`).

## License

GPL-3.0-or-later, see [`LICENSE`](LICENSE).