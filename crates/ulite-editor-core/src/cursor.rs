//! The [`CursorPosition`] type — a (row, column) pair shared by input,
//! hit-testing, scrolling, and wrapping.

/// A cursor position as a (row, column) pair into the buffer's logical
/// lines. `column` is a UTF-8 byte offset into that line's content —
/// callers that need character or grapheme offsets convert at the edge,
/// this type stays byte-indexed so it composes directly with `&str`
/// slicing everywhere else in this crate.
///
/// Mirrors the (cursorRow, cursorCol) pair `EditorState` held directly;
/// pulled into its own type here because more call sites need to pass a
/// position around than the old code had (hit-testing, scrolling, and
/// wrapping all need one).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct CursorPosition {
    /// Logical row into the buffer's line list.
    pub row: usize,
    /// UTF-8 byte offset into that row's content (see the struct doc).
    pub column: usize,
}

impl CursorPosition {
    /// Creates a position from a row and a byte column.
    pub fn new(row: usize, column: usize) -> Self {
        Self { row, column }
    }
}
