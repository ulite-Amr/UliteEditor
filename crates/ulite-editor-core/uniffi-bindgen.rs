//! Thin launcher for UniFFI's generator, shipped in-crate so the version
//! that produces the Kotlin bindings is exactly the version this crate
//! embeds (`cargo run --bin uniffi-bindgen -- generate ...`, see
//! `scripts/build-ffi.sh`). The `cli` feature pulls `uniffi_bindgen` in.

fn main() {
    uniffi::uniffi_bindgen_main();
}