//! The UniFFI bridge: this crate's Kotlin/Java-facing surface.
//!
//! One [`EditorSession`] owns the buffer, cursor, scroll camera, and per-row
//! wrap caches, so IME/edit/scroll flows are one call each instead of five
//! (the old Android code scattered the same state across `EditorState`,
//! `InputProcessor`, and `ScrollManager`). The engine itself is untouched —
//! this module composes [`crate::buffer`], [`crate::input`],
//! [`crate::scroll`], and [`crate::layout`] and widens sizes to the FFI
//! boundary (`u64`, `f32`).
//!
//! Geometry stays caller-measured: [`locate_tap`] and
//! [`cursor_screen_position`] take the visual lines a Compose layer already
//! computed via [`crate::layout::wrap_line`], so this crate keeps its
//! measure-free contract (mirrored from the internal API).
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
    fn to_engine(self) -> EngineCursorPosition {
        EngineCursorPosition::new(self.row as usize, self.column as usize)
    }
}

/// A point in content space, in pixels.
#[derive(Debug, Clone, PartialEq)]
pub struct Point {
    /// Horizontal offset from the content origin.
    pub x: f32,
    /// Vertical offset from the content origin.
    pub y: f32,
}

/// One measured, already-wrapped visual line of a logical row — the
/// smallest unit hit-testing works on. `char_widths` has one entry per
/// Unicode scalar value in `text`, the same convention as
/// `crate::layout::wrap_line`.
#[derive(Debug, Clone, PartialEq)]
pub struct VisualLine {
    /// Which logical row this visual line belongs to.
    pub row: u64,
    /// Byte offset of this visual line's first character within the row's
    /// full content (the wrap range start).
    pub byte_start: u64,
    /// This visual line's text (a slice of its row's content).
    pub text: String,
    /// Per-character measured widths, one entry per scalar value.
    pub char_widths: Vec<f32>,
}

/// A wrapped range of one logical row, as returned by
/// [`EditorSession::wrapped_lines`] — byte-range starting point for a
/// [`VisualLine`] when the caller measures the pieces of `text` itself.
#[derive(Debug, Clone, PartialEq)]
pub struct WrappedLine {
    /// Byte offset of this visual line's start in the row's content.
    pub byte_start: u64,
    /// Byte offset one past this visual line's last char in that content.
    pub byte_end: u64,
    /// This visual line's text slice.
    pub text: String,
}

// ---------------------------------------------------------------------
// Free functions: RTL detection and pure position geometry.

/// Whether `text`'s first strong-direction character is RTL — the bridge
/// equivalent of `crate::direction::is_rtl`, for aligning a single line.
pub fn is_rtl(text: String) -> bool {
    crate::direction::is_rtl(&text)
}

/// Drops the caller's `&[VisualLine]` into the engine's measurement
/// abstraction so `crate::hit_test::locate_tap` can run unmodified.
/// `row` on each supplied line must equal the value passed as `row` to
/// whatever produced them, and each logical row must contribute at least
/// one visual line (empty content yields one empty line, matching
/// `crate::layout::wrap_line`).
struct VisualLineSource<'a>(&'a [VisualLine]);

impl<'a> VisualLineSource<'a> {
    /// The visual lines recorded for logical `row`, in order.
    fn visual_lines_of(&self, row: usize) -> impl Iterator<Item = &'a VisualLine> {
        self.0.iter().filter(move |line| line.row == row as u64)
    }
}

impl<'a> crate::hit_test::VisualLineSource for VisualLineSource<'a> {
    fn visual_line_count(&self, row: usize) -> usize {
        self.visual_lines_of(row).count().max(1)
    }

    fn visual_line_text(&self, row: usize, visual_line_index: usize) -> &str {
        &self
            .visual_lines_of(row)
            .nth(visual_line_index)
            .expect("visual line index within the row's recorded lines")
            .text
    }

    fn visual_line_char_widths(&self, row: usize, visual_line_index: usize) -> &[f32] {
        &self
            .visual_lines_of(row)
            .nth(visual_line_index)
            .expect("visual line index within the row's recorded lines")
            .char_widths
    }

    fn visual_line_byte_start(&self, row: usize, visual_line_index: usize) -> usize {
        self.visual_lines_of(row)
            .nth(visual_line_index)
            .expect("visual line index within the row's recorded lines")
            .byte_start as usize
    }
}

/// Finds the (row, column) a tap at content-space (`adjusted_x`,
/// `adjusted_y`) lands on, given the caller's measured visual lines — the
/// bridge equivalent of `crate::hit_test::locate_tap`.
pub fn locate_tap(
    visual_lines: Vec<VisualLine>,
    row_count: u64,
    adjusted_x: f32,
    adjusted_y: f32,
    line_height: f32,
    top_margin: f32,
    left_margin: f32,
) -> CursorPosition {
    CursorPosition::from_engine(crate::hit_test::locate_tap(
        &VisualLineSource(&visual_lines),
        row_count as usize,
        adjusted_x,
        adjusted_y,
        line_height,
        top_margin,
        left_margin,
    ))
}

/// Computes the cursor's content-space position for rendering/scroll feed,
/// given the caller's measured visual lines — the bridge equivalent of
/// `crate::hit_test::cursor_screen_position`.
pub fn cursor_screen_position(
    visual_lines: Vec<VisualLine>,
    cursor: CursorPosition,
    line_height: f32,
    top_margin: f32,
    left_margin: f32,
) -> Point {
    let (x, y) = crate::hit_test::cursor_screen_position(
        &VisualLineSource(&visual_lines),
        cursor.to_engine(),
        line_height,
        top_margin,
        left_margin,
    );
    Point { x, y }
}

// ---------------------------------------------------------------------
// EditorSession: the facade the sample app (and any Kotlin client) drives.

/// A whole editing document state: buffer, cursor, scroll camera, and the
/// per-row wrap caches — created empty (one blank line) and mutated via the
/// IME-style methods. All coordinates entering or leaving this type are 64-bit
/// or `f32`; the engine's `usize` math stays inside.
///
/// UniFFI objects are `Arc`-shared across the FFI, so methods take `&self`
/// and the state sits behind a mutex (interior mutability) rather than
/// `&mut self` receivers — the same shape as any uniffi object, and the
/// sample app drives it from one thread anyway. A poisoned lock (a holder
/// panicking mid-mutation) is recovered rather than propagated because edits
/// never corrupt the state on their own.
///
/// `wrapped_lines` never measures: the caller supplies per-character widths
/// (and a viewport width in the same wrapping units), exactly as the
/// internal `crate::layout::wrap_line` contract demands.
pub struct EditorSession {
    state: Mutex<SessionState>,
}

/// The mutable document behind [`EditorSession`]'s lock.
struct SessionState {
    buffer: Buffer,
    cursor: EngineCursorPosition,
    scroll: crate::scroll::ScrollState,
    wrap_caches: Vec<Option<crate::layout::WrapCache>>,
    metrics_version: u64,
}

impl SessionState {
    /// Grows the wrap-cache list to match the buffer, padding the new rows
    /// with uncached entries (they compute on first wrap).
    fn ensure_cache_len(&mut self) {
        self.wrap_caches.resize(self.buffer.row_count(), None);
    }

    /// Marks every row from `row` upward as stale — edits at or below a row
    /// can only affect it and everything after it. Over-invalidation (a
    /// character added mid-row also dropping earlier rows' caches) is
    /// harmless; the next wrap recomputes. This is the single
    /// cache-invalidation point the session has, mirroring how the engine
    /// keeps caches caller-owned (`crate::buffer::Buffer` never sees them).
    fn invalidate_from(&mut self, row: usize) {
        self.wrap_caches.truncate(row);
    }
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
                wrap_caches: Vec::new(),
                metrics_version: 0,
            }),
        }
    }

    /// Locks the session state, recovering from a poisoned lock — the
    /// contents are still valid (edits never corrupt them), only the
    /// previous holder panicked while mutating.
    fn state(&self) -> MutexGuard<'_, SessionState> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
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
        let mut state = self.state();
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
        let mut state = self.state();
        let row = state.cursor.row;
        for character in ch.chars() {
            crate::input::insert_char(&mut state.buffer, &mut state.cursor, character);
        }
        state.invalidate_from(row);
    }

    /// Inserts a run of text (paste, IME commit) at the cursor and advances
    /// past it. Newlines split into their own logical rows, so whole
    /// paragraphs can be inserted in one call.
    pub fn insert_text(&self, text: String) {
        let mut state = self.state();
        let row = state.cursor.row;
        for character in text.chars() {
            if character == '\n' {
                crate::input::handle_new_line(&mut state.buffer, &mut state.cursor);
            } else {
                crate::input::insert_char(&mut state.buffer, &mut state.cursor, character);
            }
        }
        state.invalidate_from(row);
    }

    /// Inserts a newline at the cursor: everything from the cursor becomes a
    /// new row below it, and the cursor moves to that row's start.
    pub fn newline(&self) {
        let mut state = self.state();
        let row = state.cursor.row;
        crate::input::handle_new_line(&mut state.buffer, &mut state.cursor);
        state.invalidate_from(row);
    }

    /// Deletes the character before the cursor; at a row's start, merges
    /// that row into the previous one. A no-op at the document's start.
    pub fn backspace(&self) {
        let mut state = self.state();
        let row = state.cursor.row;
        crate::input::handle_backspace(&mut state.buffer, &mut state.cursor);
        // A merge shifts every row from the merged one down, so invalidate
        // one row earlier than the edit to cover both cases — a safe
        // superset (see `invalidate_from`).
        state.invalidate_from(row.saturating_sub(1));
    }

    /// Replaces the whole document with `text`, splitting on `\n` and
    /// dropping any prior wrap caches. A trailing newline yields a trailing
    /// empty row, matching line-based loading semantics.
    pub fn replace_content(&self, text: String) {
        let mut state = self.state();
        state.buffer = Buffer::from_lines(text.split('\n'));
        state.cursor = EngineCursorPosition::default();
        state.scroll = crate::scroll::ScrollState::new();
        state.wrap_caches.clear();
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

    /// Sets the version stamp that invalidates every wrap cache when font
    /// metrics change (pinch-zoom, typeface swap). Bumping it makes the
    /// next call to [`EditorSession::wrapped_lines`] recompute for every
    /// row.
    pub fn set_metrics_version(&self, version: u64) {
        let mut state = self.state();
        state.metrics_version = version;
        state.wrap_caches.clear();
    }

    /// Current font-metrics version (see [`EditorSession::set_metrics_version`]).
    pub fn metrics_version(&self) -> u64 {
        self.state().metrics_version
    }

    /// Computes (or returns the cached) wrap ranges for one logical row.
    ///
    /// `char_widths` must have one entry per scalar value in the row's
    /// content. The cache keys on `viewport_width`, `wrap_enabled`, and the
    /// current `metrics_version`, matching `crate::layout::WrapCache`, so
    /// repeated draws with unchanged inputs are free after the first.
    pub fn wrapped_lines(
        &self,
        row: u64,
        char_widths: Vec<f32>,
        viewport_width: u32,
        wrap_enabled: bool,
    ) -> Vec<WrappedLine> {
        let mut state = self.state();
        state.ensure_cache_len();
        let row = row.min(state.buffer.row_count().saturating_sub(1) as u64) as usize;
        let content = state.buffer.line(row).as_str();
        let ranges = crate::layout::wrap_line(
            &mut state.wrap_caches[row],
            content,
            &char_widths,
            viewport_width,
            wrap_enabled,
            state.metrics_version,
        );
        ranges
            .iter()
            .map(|range| {
                let start = range.start;
                let end = range.end;
                WrappedLine {
                    byte_start: start as u64,
                    byte_end: end as u64,
                    text: content[start..end].to_string(),
                }
            })
            .collect()
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

    /// Fixed two-row fixture mirroring `crate::hit_test`'s own: row 0 wraps
    /// into ["hello ", "world"], row 1 is "hi"; every char is 10px wide,
    /// `line_height` 20px, `top_margin` 100px, `left_margin` 40px.
    fn fixture_visual_lines() -> Vec<VisualLine> {
        vec![
            VisualLine {
                row: 0,
                byte_start: 0,
                text: "hello ".to_string(),
                char_widths: vec![10.0; 6],
            },
            VisualLine {
                row: 0,
                byte_start: 6,
                text: "world".to_string(),
                char_widths: vec![10.0; 5],
            },
            VisualLine {
                row: 1,
                byte_start: 0,
                text: "hi".to_string(),
                char_widths: vec![10.0; 2],
            },
        ]
    }

    #[test]
    fn is_rtl_wraps_the_engine_heuristic() {
        assert!(!is_rtl("hello".to_string()));
        assert!(is_rtl("مرحبا".to_string()));
    }

    #[test]
    fn locate_tap_first_character_of_first_visual_line() {
        let pos = locate_tap(fixture_visual_lines(), 2, 41.0, 105.0, 20.0, 100.0, 40.0);
        assert_eq!(pos, CursorPosition { row: 0, column: 0 });
    }

    #[test]
    fn locate_tap_lands_on_second_visual_line_with_byte_offset() {
        let pos = locate_tap(fixture_visual_lines(), 2, 46.0, 125.0, 20.0, 100.0, 40.0);
        assert_eq!(pos, CursorPosition { row: 0, column: 7 });
    }

    #[test]
    fn cursor_screen_position_at_row_start() {
        let point = cursor_screen_position(
            fixture_visual_lines(),
            CursorPosition { row: 1, column: 0 },
            20.0,
            100.0,
            40.0,
        );
        assert_eq!(point, Point { x: 40.0, y: 140.0 });
    }

    #[test]
    fn new_session_is_one_blank_line_at_top_left() {
        let session = EditorSession::new();
        assert_eq!(session.row_count(), 1);
        assert_eq!(session.line_text(0), "");
        assert_eq!(session.cursor(), CursorPosition { row: 0, column: 0 });
    }

    #[test]
    fn typing_builds_text_and_advances_the_cursor() {
        let mut session = EditorSession::new();
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
        let mut session = EditorSession::new();
        session.insert_text("hello world".to_string());
        session.newline();
        assert_eq!(session.row_count(), 2);
        assert_eq!(session.line_text(0), "hello world");
        assert_eq!(session.line_text(1), "");
        assert_eq!(session.cursor(), CursorPosition { row: 1, column: 0 });
    }

    #[test]
    fn insert_text_splits_newlines_into_rows() {
        let mut session = EditorSession::new();
        session.insert_text("hello\nworld".to_string());
        assert_eq!(session.row_count(), 2);
        assert_eq!(session.line_text(0), "hello");
        assert_eq!(session.line_text(1), "world");
        assert_eq!(session.cursor(), CursorPosition { row: 1, column: 5 });
    }

    #[test]
    fn backspace_at_a_row_start_merges_rows() {
        let mut session = EditorSession::new();
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
        let mut session = EditorSession::new();
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
        let mut session = EditorSession::new();
        session.replace_content("a\nb\n".to_string());
        assert_eq!(session.row_count(), 3);
        assert_eq!(session.line_text(0), "a");
        assert_eq!(session.line_text(1), "b");
        assert_eq!(session.line_text(2), "");
    }

    #[test]
    fn wrapped_lines_wraps_to_viewport_and_reports_byte_ranges() {
        let mut session = EditorSession::new();
        session.insert_text("hello world".to_string());
        let widths = vec![10.0; 11];
        let lines = session.wrapped_lines(0, widths, 120, true);
        assert_eq!(lines.len(), 6);
        assert_eq!(lines[0].byte_start, 0);
        assert_eq!(lines[0].byte_end, 2);
        assert_eq!(lines[0].text, "he");
        assert_eq!(lines[5].byte_start, 10);
        assert_eq!(lines[5].byte_end, 11);
        assert_eq!(lines[5].text, "d");
    }

    #[test]
    fn empty_line_wraps_to_a_single_empty_range() {
        let mut session = EditorSession::new();
        let lines = session.wrapped_lines(0, Vec::new(), 120, true);
        assert_eq!(lines.len(), 1);
        assert_eq!(lines[0].byte_start, 0);
        assert_eq!(lines[0].byte_end, 0);
        assert_eq!(lines[0].text, "");
    }

    #[test]
    fn backspace_at_document_start_is_a_no_op() {
        let mut session = EditorSession::new();
        session.backspace();
        assert_eq!(session.buffer_text(), "");
        assert_eq!(session.cursor(), CursorPosition { row: 0, column: 0 });
    }
}
