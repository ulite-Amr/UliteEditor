# UliteEditor

> **Status: pre-alpha — not usable yet.** Under active development, no
> releases. Expect breaking changes until the first **alpha** tag.

UliteEditor is a **Kotlin/Android library** wrapping a tested **Rust text
engine**, connected via [UniFFI]. A small sample app demonstrates the
library in action; UliteEditor itself is not an editor app.

The Rust engine ([`ulite-editor-core`](crates/ulite-editor-core/)) already
implements and unit-tests the pure logic — line buffer, cursor movement,
word-wrapping, hit-testing, scrolling, and bidi-aware text direction — with
no UI or platform dependencies. The Kotlin bindings belong to the `editor`
library; the bridge and the interactive sample are under construction.

## Repo layout

- `crates/ulite-editor-core/` — the Rust text engine and the source of
  truth for behavior: buffer, cursor, wrap, hit-test, scroll, direction.
- `editor/` — the Kotlin library: UniFFI bindings to the core, a thin Kotlin
  API, and the editor theme.
- `app/` — the sample app: a single screen that exercises the library
  interactively.
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

Android side (requires JDK 17, an Android SDK, and Rust Android targets —
`cargo ndk` produces the per-ABI native library via `uniffi-bindgen`):

```sh
scripts/build-ffi.sh          # generate Kotlin bindings + .so per ABI
gradle ktlintCheck
gradle :editor:testDebugUnitTest :app:lintDebug :editor:lintDebug :app:assembleDebug
```

## Contributing

Contributions are welcome — bug reports, fixes, tests, and ideas all help.

- Read [`AGENTS.md`](AGENTS.md) for the repo conventions: one task = one
  commit, conventional-commit messages, CI is the only verification path,
  and behavior must stay faithful to the old project this engine is ported
  from (documented there).
- Open an issue first for anything non-trivial so the approach is agreed
  before the code lands.
- Every PR runs five checks on GitHub: `lint`, `test`, `check`, and `docs`
  for the Rust core, plus `build` for the Android side.

## Roadmap

The project has no release schedule. The first release will be an **alpha**
tag, and it happens only when the library's Kotlin API and the sample screen
cover the engine end-to-end and the Android build is reproducible. Until
then the crate API is free to change.

## License

GPL-3.0-or-later, see [`LICENSE`](LICENSE).

[UniFFI]: https://mozilla.github.io/uniffi-rs/