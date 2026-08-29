// Generates the UniFFI scaffolding for the Kotlin/Java bridge at build
// time. The UDL is the single source of truth for the exported surface;
// `lib.rs` `include!`s this crate's output so the ABI symbols land in the
// compiled library. Behavior is tested in `ffi.rs`'s `#[cfg(test)]`
// modules, not here — this file only wires generation so bindgen stays
// deterministic (no binary scanning, unlike the CLI's `--library` mode).

fn main() {
    uniffi::generate_scaffolding("src/ulite_editor_core.udl").unwrap();
}