//! Buffer, cursor, wrap, hit-test, scroll, and text-direction logic for
//! UliteEditor.
//!
//! This crate is deliberately UI-free: no rendering, no animation, no
//! text measurement, no platform APIs. Those stay on the Compose side —
//! see `../../reference/PORTING_NOTES.md` for exactly which old
//! (Android/Java) file each module here replaces, and why the split
//! landed where it did.
//!
//! Modules:
//! - [`buffer`] — the line store (replaces `EditorState` + `TextLineModel`)
//! - [`cursor`] — cursor position type
//! - [`input`] — insert/newline/backspace (replaces `InputProcessor`)
//! - [`layout`] — word-wrap + per-line cache (replaces `LayoutManager`,
//!   minus the text measurement it also did)
//! - [`hit_test`] — tap-to-position, cursor-to-screen-position (replaces
//!   the geometry half of `NoteScribeEngine`)
//! - [`scroll`] — camera-follow bounds/clamping/fling (replaces
//!   `ScrollManager`'s math; fling physics is new, not ported — see
//!   [`scroll::ScrollState`]'s doc comment)
//! - [`direction`] — RTL heuristic (replaces `TextDirectionHelper`)

pub mod buffer;
pub mod cursor;
pub mod direction;
pub mod hit_test;
pub mod input;
pub mod layout;
pub mod scroll;

pub use buffer::Buffer;
pub use cursor::CursorPosition;
