//! The UniFFI bridge: this crate's Kotlin/Java-facing surface.
//!
//! One [`EditorSession`] owns the buffer, cursor, and scroll camera, so
//! IME/edit/scroll flows are one call each instead of five (the old Android
//! code scattered the same state across `EditorState`, `InputProcessor`,
//! and `ScrollManager`). The engine itself is untouched — this module
//! composes [`crate::buffer`], [`crate::input`], and [`crate::scroll`] and
//! widens sizes to the FFI boundary (`u64`, `f32`).
//!
//! No glyph-space geometry (wrapping, caret placement, tap hit-testing)
//! crosses this boundary: text is measured and laid out by the Compose
//! renderer, whose bidi/shaping results only it can produce. The crate only
//! owns the direction-agnostic byte math around it. See
//! `.project/ARCHITECTURE.md` for the split and its rationale.
//!
//! Field widths: positions use `u64` because the bindings are generated for
//! a 64-bit JVM where a Java `long` is the cheapest unambiguous container;
//! the engine's `usize` byte offsets convert losslessly (values are never
//! negative and real buffers stay far below 2^63).

use std::sync::{Mutex, MutexGuard};

use crate::buffer::Buffer;
use crate::cursor::CursorPosition as EngineCursorPosition;

// ---------------------------------------------------------------------
// Records passed across the FFI boundary.

/// A cursor position as seen by Kotlin/Java: `row` into the buffer's
/// logical lines, `column` a UTF-8 byte offset into that row's content.
/// Deliberately the same name as the engine type but a separate 64-bit
/// type — see the module doc.
#[derive(Debug, Clone, PartialEq)]
pub struct CursorPosition {
    /// Logical row.
    pub row: u64,
    /// UTF-8 byte offset into the row's content.
    pub column: u64,
}

impl CursorPosition {
    /// Widens an engine position to the FFI record.
    fn from_engine(position: EngineCursorPosition) -> Self {
        Self {
            row: position.row as u64,
            column: position.column as u64,
        }
    }

    /// Narrows an FFI record back to the engine type. Callers clamp
    /// values before converting, so the `as` truncation never loses bits.
    fn into_engine(self) -> EngineCursorPosition {
        EngineCursorPosition::new(self.row as usize, self.column as usize)
    }
}

// ---------------------------------------------------------------------
// EditorSession: the facade the sample app (and any Kotlin client) drives.

/// A whole editing document state: buffer, cursor, and scroll camera —
/// created empty (one blank line) and mutated via the IME-style methods.
/// All coordinates entering or leaving this type are 64-bit or `f32`; the
/// engine's `usize` math stays inside.
///
/// UniFFI objects are `Arc`-shared across the FFI, so methods take `&self`
/// and the state sits behind a mutex (interior mutability) rather than
/// `&mut self` receivers — the same shape as any uniffi object, and the
/// sample app drives it from one thread anyway. A poisoned lock (a holder
/// panicking mid-mutation) is recovered rather than propagated because edits
/// never corrupt the state on their own.
pub struct EditorSession {
    state: Mutex<SessionState>,
}

/// The mutable document behind [`EditorSession`]'s lock.
struct SessionState {
    buffer: Buffer,
    cursor: EngineCursorPosition,
    scroll: crate::scroll::ScrollState,
}

impl Default for EditorSession {
    fn default() -> Self {
        Self::new()
    }
}

impl EditorSession {
    /// An empty document: one blank line, cursor at the top-left, a
    /// stationary camera. Wire `update_bounds` before rendering.
    pub fn new() -> Self {
        Self {
            state: Mutex::new(SessionState {
                buffer: Buffer::new(),
                cursor: EngineCursorPosition::default(),
                scroll: crate::scroll::ScrollState::new(),
            }),
        }
    }

    /// Locks the session state, recovering from a poisoned lock — the
    /// contents are still valid (edits never corrupt them), only the
    /// previous holder panicked while mutating.
    fn state(&self) -> MutexGuard<'_, SessionState> {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Current cursor position.
    pub fn cursor(&self) -> CursorPosition {
        CursorPosition::from_engine(self.state().cursor)
    }

    /// Moves the cursor, clamping both coordinates into the document —
    /// out-of-range rows land on the last line, out-of-range columns on
    /// its end. Rendering code can therefore forward any tap position
    /// straight here and always end up with a valid cursor.
    pub fn set_cursor(&self, position: CursorPosition) {
        let mut guard = self.state();
        let state = &mut *guard;
        let max_row = state.buffer.row_count().saturating_sub(1) as u64;
        let row = position.row.min(max_row) as usize;
        let column = position.column.min(state.buffer.line(row).len() as u64) as usize;
        state.cursor = EngineCursorPosition::new(row, column);
    }

    /// Inserts a character at the cursor and advances it. `ch` may carry
    /// more than one code point (some inputs hand over whole grapheme
    /// clusters); every code point is inserted, so multi-scope input can
    /// never silently truncate.
    pub fn insert_char(&self, ch: String) {
        let mut guard = self.state();
        let state = &mut *guard;
        for character in ch.chars() {
            crate::input::insert_char(&mut state.buffer, &mut state.cursor, character);
        }
    }

    /// Inserts a run of text (paste, IME commit) at the cursor and advances
    /// past it. Newlines split into their own logical rows, so whole
    /// paragraphs can be inserted in one call.
    pub fn insert_text(&self, text: String) {
        let mut guard = self.state();
        let state = &mut *guard;
        for character in text.chars() {
            if character == '\n' {
                crate::input::handle_new_line(&mut state.buffer, &mut state.cursor);
            } else {
                crate::input::insert_char(&mut state.buffer, &mut state.cursor, character);
            }
        }
    }

    /// Inserts a newline at the cursor: everything from the cursor becomes a
    /// new row below it, and the cursor moves to that row's start.
    pub fn newline(&self) {
        let mut guard = self.state();
        let state = &mut *guard;
        crate::input::handle_new_line(&mut state.buffer, &mut state.cursor);
    }

    /// Deletes the character before the cursor; at a row's start, merges
    /// that row into the previous one. A no-op at the document's start.
    pub fn backspace(&self) {
        let mut guard = self.state();
        let state = &mut *guard;
        crate::input::handle_backspace(&mut state.buffer, &mut state.cursor);
    }

    /// Replaces the whole document with `text`, splitting on `\n`. A
    /// trailing newline yields a trailing empty row, matching line-based
    /// loading semantics.
    pub fn replace_content(&self, text: String) {
        let mut state = self.state();
        state.buffer = Buffer::from_lines(text.split('\n'));
        state.cursor = EngineCursorPosition::default();
        state.scroll = crate::scroll::ScrollState::new();
    }

    /// Number of logical rows in the document (always at least one).
    pub fn row_count(&self) -> u64 {
        self.state().buffer.row_count() as u64
    }

    /// `row`'s text. Rows past the end report the last row, so callers
    /// drawing an out-of-range caret never crash.
    pub fn line_text(&self, row: u64) -> String {
        let state = self.state();
        let row = row.min(state.buffer.row_count().saturating_sub(1) as u64) as usize;
        state.buffer.line(row).as_str().to_string()
    }

    /// The whole document joined with `\n`.
    pub fn buffer_text(&self) -> String {
        let state = self.state();
        state
            .buffer
            .lines()
            .iter()
            .map(|line| line.as_str())
            .collect::<Vec<_>>()
            .join("\n")
    }

    // --- scroll camera pass-throughs (all delegate to ScrollState) ---

    /// Current horizontal scroll offset in pixels.
    pub fn scroll_x(&self) -> f32 {
        self.state().scroll.scroll_x()
    }

    /// Current vertical scroll offset in pixels.
    pub fn scroll_y(&self) -> f32 {
        self.state().scroll.scroll_y()
    }

    /// Recomputes scroll bounds after a content or viewport resize and
    /// re-clamps — call whenever the editor's size changes.
    pub fn update_bounds(
        &self,
        content_width: f32,
        content_height: f32,
        viewport_width: f32,
        viewport_height: f32,
    ) {
        self.state().scroll.update_bounds(
            content_width,
            content_height,
            viewport_width,
            viewport_height,
        );
    }

    /// Moves the camera just enough to keep the cursor rectangle visible
    /// with the editor's safety margin; returns whether scroll moved.
    pub fn ensure_visible(
        &self,
        cursor_x: f32,
        cursor_y: f32,
        line_height: f32,
        viewport_width: f32,
        viewport_height: f32,
    ) -> bool {
        self.state().scroll.ensure_visible(
            cursor_x,
            cursor_y,
            line_height,
            viewport_width,
            viewport_height,
        )
    }

    /// Raw one-finger pan in pixels, clamped to the bounds. Cancels an
    /// in-flight fling, like the old engine did on touch-down.
    pub fn scroll_by(&self, dx: f32, dy: f32) {
        self.state().scroll.scroll_by(dx, dy);
    }

    /// Sets the scroll position absolutely (pixels), clamped to the bounds,
    /// and cancels any in-flight fling. Pinch-zoom re-anchors the camera
    /// around its focal point with this (sora-editor's scale convention).
    pub fn set_scroll(&self, x: f32, y: f32) {
        self.state().scroll.set_scroll(x, y);
    }

    /// Starts a fling with the gesture's release velocity (pixels/second).
    pub fn start_fling(&self, velocity_x: f32, velocity_y: f32) {
        self.state().scroll.start_fling(velocity_x, velocity_y);
    }

    /// Advances an in-flight fling by `dt_seconds`; returns whether it is
    /// still moving. Call once per frame from the render loop.
    pub fn tick_fling(&self, dt_seconds: f32) -> bool {
        self.state().scroll.tick_fling(dt_seconds)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_session_is_one_blank_line_at_top_left() {
        let session = EditorSession::new();
        assert_eq!(session.row_count(), 1);
        assert_eq!(session.line_text(0), "");
        assert_eq!(session.cursor(), CursorPosition { row: 0, column: 0 });
    }

    #[test]
    fn typing_builds_text_and_advances_the_cursor() {
        let session = EditorSession::new();
        session.insert_text("hello".to_string());
        assert_eq!(session.buffer_text(), "hello");
        assert_eq!(session.cursor(), CursorPosition { row: 0, column: 5 });
        session.insert_char(" ".to_string());
        session.insert_char("!".to_string());
        assert_eq!(session.buffer_text(), "hello !");
        assert_eq!(session.cursor(), CursorPosition { row: 0, column: 7 });
    }

    #[test]
    fn newline_splits_into_a_second_row() {
        let session = EditorSession::new();
        session.insert_text("hello world".to_string());
        session.newline();
        assert_eq!(session.row_count(), 2);
        assert_eq!(session.line_text(0), "hello world");
        assert_eq!(session.line_text(1), "");
        assert_eq!(session.cursor(), CursorPosition { row: 1, column: 0 });
    }

    #[test]
    fn insert_text_splits_newlines_into_rows() {
        let session = EditorSession::new();
        session.insert_text("hello\nworld".to_string());
        assert_eq!(session.row_count(), 2);
        assert_eq!(session.line_text(0), "hello");
        assert_eq!(session.line_text(1), "world");
        assert_eq!(session.cursor(), CursorPosition { row: 1, column: 5 });
    }

    #[test]
    fn backspace_at_a_row_start_merges_rows() {
        let session = EditorSession::new();
        session.insert_text("hello".to_string());
        session.newline();
        session.insert_text("world".to_string());
        session.set_cursor(CursorPosition { row: 1, column: 0 });
        session.backspace();
        assert_eq!(session.row_count(), 1);
        assert_eq!(session.line_text(0), "helloworld");
    }

    #[test]
    fn set_cursor_clamps_row_and_column() {
        let session = EditorSession::new();
        session.insert_text("abc".to_string());
        session.newline();
        session.insert_text("def".to_string());
        session.set_cursor(CursorPosition {
            row: 99,
            column: 99,
        });
        // row 99 -> last row (1), column 99 -> its end (3)
        assert_eq!(session.cursor(), CursorPosition { row: 1, column: 3 });
    }

    #[test]
    fn replace_content_splits_lines_keeping_the_trailing_empty_row() {
        let session = EditorSession::new();
        session.replace_content("a\nb\n".to_string());
        assert_eq!(session.row_count(), 3);
        assert_eq!(session.line_text(0), "a");
        assert_eq!(session.line_text(1), "b");
        assert_eq!(session.line_text(2), "");
    }

    #[test]
    fn backspace_at_document_start_is_a_no_op() {
        let session = EditorSession::new();
        session.backspace();
        assert_eq!(session.buffer_text(), "");
        assert_eq!(session.cursor(), CursorPosition { row: 0, column: 0 });
    }
}
