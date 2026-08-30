#![deny(missing_docs)]
#![deny(rustdoc::broken_intra_doc_links)]

//! Buffer, cursor, input, scroll, and FFI logic for UliteEditor.
//!
//! This crate is deliberately UI-free: no rendering, no animation, no
//! text measurement, no platform APIs. Those stay on the Compose side —
//! see `../../../reference/PORTING_NOTES.md` for exactly which old
//! (Android/Java) file each module here replaces, and why the split
//! landed where it did.
//!
//! Glyph-space geometry (wrapping, caret placement, tap hit-testing) is
//! deliberately *not* in this crate: text is measured and laid out by the
//! Compose renderer, whose bidi/shaping results only it can produce. The
//! crate therefore owns everything that is direction-agnostic byte math —
//! the buffer, cursor, insert/newline/backspace, and the scroll camera.
//!
//! Modules:
//! - [`buffer`] — the line store (replaces `EditorState` + `TextLineModel`)
//! - [`cursor`] — cursor position type
//! - [`input`] — insert/newline/backspace (replaces `InputProcessor`)
//! - [`scroll`] — camera-follow bounds/clamping/fling (replaces
//!   `ScrollManager`'s math; fling physics is new, not ported — see
//!   [`scroll::ScrollState`]'s doc comment)
//! - [`ffi`] — the UniFFI bridge: [`ffi::EditorSession`], a facade owning
//!   buffer + cursor + scroll camera. Persists as the only cross-language
//!   surface — see `.project/ARCHITECTURE.md` for the UniFFI-versus-JNI
//!   decision.

pub mod buffer;
pub mod cursor;
pub mod ffi;
pub mod input;
pub mod scroll;

pub use buffer::Buffer;
pub use ffi::{CursorPosition, EditorSession};

/// Tag type UniFFI's generated UDL scaffolding uses to tie every exported
/// item's metadata to this specific crate. Its attribute macros reference it
/// as `crate::UniFfiTag`, so it must live at the crate root — which, in the
/// default UDL layout, is also where the scaffolding itself would unpack;
/// this is that contract, kept explicit. Hidden from API docs; there is no
/// "what and why" to document for a marker, only the wiring note above.
#[allow(missing_docs)]
#[doc(hidden)]
pub struct UniFfiTag;

/// Generated UniFFI scaffolding — `build.rs` compiles `ulite_editor_core.udl`
/// into `$OUT_DIR/ulite_editor_core.uniffi.rs`, which this module `include!`s
/// so the exported C ABI lands in the compiled library. It stays inside a
/// module rather than at the crate root so any `#![allow(...)]` pragmas the
/// generated file carries cannot downgrade this crate's own
/// `#![deny(missing_docs)]`; the root [`UniFfiTag`] satisfies the code's
/// crate-root references, and the hand-written API lives in [`ffi`].
#[allow(clippy::all, dead_code, missing_docs)]
mod scaffolding {
    use crate::ffi::*;
    uniffi::include_scaffolding!("ulite_editor_core");
}
