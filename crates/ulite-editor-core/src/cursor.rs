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
    pub row: usize,
    pub column: usize,
}

impl CursorPosition {
    pub fn new(row: usize, column: usize) -> Self {
        Self { row, column }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_is_top_left() {
        assert_eq!(CursorPosition::default(), CursorPosition::new(0, 0));
    }

    #[test]
    fn new_sets_row_and_column() {
        let pos = CursorPosition::new(3, 12);
        assert_eq!(pos.row, 3);
        assert_eq!(pos.column, 12);
    }

    #[test]
    fn it_is_copy_so_mutating_a_copy_leaves_the_original_untouched() {
        let original = CursorPosition::new(2, 5);
        let mut copy = original;
        copy.row = 9;
        assert_eq!(original, CursorPosition::new(2, 5));
    }

    #[test]
    fn equals_compares_both_fields() {
        assert_eq!(CursorPosition::new(1, 2), CursorPosition::new(1, 2));
        assert_ne!(CursorPosition::new(1, 2), CursorPosition::new(2, 1));
    }
}
