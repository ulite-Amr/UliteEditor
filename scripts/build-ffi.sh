#!/usr/bin/env bash
#
# Produces the UniFFI bridge output the Android `:editor` module consumes:
#   * Kotlin bindings  -> editor/build/generated/ffi/kotlin/uniffi/.../
#   * per-ABI cdylib    -> editor/build/generated/ffi/jniLibs/<abi>/libulite_editor_core.so
#
# This is the FFI *producer* and it deliberately runs a real build, so it is
# the exception to the repo's no-heavy-local-build culture: it must run on a
# machine with a full Rust + Android NDK toolchain (the android CI job runs
# it; the operator's mobile dev box cannot and never should). Requires on
# PATH:
#   cargo-ndk -> cargo install cargo-ndk
# The UniFFI generator needs no install — it's the crate's own `uniffi-bindgen`
# binary (`[[bin]]` + the `cli` feature), so its version can never drift from
# the scaffolded crate.
#
# Run from anywhere; paths are resolved relative to the repo root.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
crate_dir="$repo_root/crates/ulite-editor-core"
udl="$crate_dir/src/ulite_editor_core.udl"
out_root="$repo_root/editor/build/generated/ffi"

abis=(arm64-v8a armeabi-v7a x86_64)

require() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "build-ffi.sh: '$1' is not on PATH." >&2
        exit 1
    fi
}

require cargo-ndk

echo "> uniffi-bindgen generate (Kotlin bindings)"
cargo run --manifest-path "$crate_dir/Cargo.toml" --bin uniffi-bindgen -- \
    generate "$udl" --language kotlin --no-format --out-dir "$out_root/kotlin"

echo "> cargo ndk build (per-ABI cdylib)"
cargo ndk -o "$out_root/jniLibs" \
    -t "${abis[0]}" -t "${abis[1]}" -t "${abis[2]}" \
    build --release --manifest-path "$crate_dir/Cargo.toml"

echo "Done. Bindings: $out_root/kotlin/uniffi/ulite_editor_core/"
echo "       Native: $out_root/jniLibs/{${abis[0]},${abis[1]},${abis[2]}}/libulite_editor_core.so"