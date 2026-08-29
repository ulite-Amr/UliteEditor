/// One logical (unwrapped) line of text.
///
/// The old code split this across two classes — `EditorState` held the
/// list of lines, `TextLineModel` held one line's content *and* its own
/// wrap cache (`core/state/EditorState.java`, `model/TextLineModel.java`).
/// Here the cache is not owned by the line: `layout::wrap_line` takes it
/// as a caller-supplied parameter, so it belongs to whichever layer lays
/// the buffer out. The earlier `pub(crate)` field in this file sat in a
/// crate with nothing wired to populate it — this is the outcome of that
/// audit (see `.project/ARCHITECTURE.md`).
#[derive(Debug, Clone, Default)]
pub struct Line {
    content: String,
}

impl Line {
    pub fn new(content: impl Into<String>) -> Self {
        Self {
            content: content.into(),
        }
    }

    pub fn as_str(&self) -> &str {
        &self.content
    }

    pub fn len(&self) -> usize {
        self.content.len()
    }

    pub fn is_empty(&self) -> bool {
        self.content.is_empty()
    }

    /// Single mutation point for the line's content — every edit goes
    /// through here. No wrap cache lives on the line anymore (see the
    /// struct doc), but this indirection is kept so that cache
    /// invalidation has exactly one place to land when the layout
    /// layer's cache returns, and so callers can't hold disjoint
    /// `&str`/`&mut` views of the same content by accident.
    fn content_mut(&mut self) -> &mut String {
        &mut self.content
    }
}

/// The buffer: an ordered list of logical lines. Always holds at least
/// one line — matches `EditorState`'s constructor, which seeded itself
/// with a single empty `TextLineModel` so the editor never has zero
/// lines to address a cursor into.
#[derive(Debug, Clone)]
pub struct Buffer {
    lines: Vec<Line>,
}

impl Default for Buffer {
    fn default() -> Self {
        Self::new()
    }
}

impl Buffer {
    pub fn new() -> Self {
        Self {
            lines: vec![Line::default()],
        }
    }

    pub fn row_count(&self) -> usize {
        self.lines.len()
    }

    pub fn line(&self, row: usize) -> &Line {
        &self.lines[row]
    }

    pub fn line_mut(&mut self, row: usize) -> &mut Line {
        &mut self.lines[row]
    }

    pub fn lines(&self) -> &[Line] {
        &self.lines
    }

    /// Inserts a byte at `column` in `row`'s content. `ch` is restricted
    /// to ASCII here deliberately — see `input::insert_str` for the
    /// general case. Kept as a thin wrapper so single-character insertion
    /// (the common typing path) doesn't allocate a temporary `String`.
    pub(crate) fn insert_char_at(&mut self, row: usize, column: usize, ch: char) {
        self.lines[row].content_mut().insert(column, ch);
    }

    /// Multi-character insertion — e.g. paste, or IME commit text.
    pub fn insert_str_at(&mut self, row: usize, column: usize, text: &str) {
        self.lines[row].content_mut().insert_str(column, text);
    }

    pub(crate) fn delete_range(&mut self, row: usize, start: usize, end: usize) {
        self.lines[row].content_mut().replace_range(start..end, "");
    }

    pub(crate) fn split_line(&mut self, row: usize, column: usize) {
        let tail = self.lines[row].content_mut().split_off(column);
        self.lines.insert(row + 1, Line::new(tail));
    }

    /// Merges `row` into `row - 1`, returning the byte column in the
    /// (now merged) previous line where `row`'s old content starts —
    /// callers use this to place the cursor, same as
    /// `InputProcessor.handleBackspace` did with `newCol`.
    pub(crate) fn merge_with_previous(&mut self, row: usize) -> usize {
        let removed = self.lines.remove(row);
        let previous = &mut self.lines[row - 1];
        let join_at = previous.len();
        previous.content_mut().push_str(removed.as_str());
        join_at
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_buffer_starts_with_one_empty_line() {
        let buffer = Buffer::new();
        assert_eq!(buffer.row_count(), 1);
        assert!(buffer.line(0).is_empty());
    }

    #[test]
    fn split_line_pushes_rest_onto_a_new_row() {
        let mut buffer = Buffer::new();
        buffer.insert_str_at(0, 0, "hello world");
        buffer.split_line(0, 5);
        assert_eq!(buffer.row_count(), 2);
        assert_eq!(buffer.line(0).as_str(), "hello");
        assert_eq!(buffer.line(1).as_str(), " world");
    }

    #[test]
    fn merge_with_previous_joins_lines_and_reports_the_join_point() {
        let mut buffer = Buffer::new();
        buffer.insert_str_at(0, 0, "hello");
        buffer.split_line(0, 5);
        let join_at = buffer.merge_with_previous(1);
        assert_eq!(buffer.row_count(), 1);
        assert_eq!(buffer.line(0).as_str(), "hello");
        assert_eq!(join_at, 5);
    }

    #[test]
    fn delete_range_removes_only_the_given_bytes() {
        let mut buffer = Buffer::new();
        buffer.insert_str_at(0, 0, "abcdef");
        buffer.delete_range(0, 1, 4);
        assert_eq!(buffer.line(0).as_str(), "aef");
    }

    #[test]
    fn deleting_everything_leaves_an_empty_line_rather_than_zero_lines() {
        let mut buffer = Buffer::new();
        buffer.insert_str_at(0, 0, "x");
        buffer.delete_range(0, 0, 1);
        assert_eq!(buffer.row_count(), 1);
        assert!(buffer.line(0).is_empty());
    }

    #[test]
    fn line_mut_content_mut_is_the_single_edit_gate() {
        let mut buffer = Buffer::new();
        buffer.line_mut(0).content_mut().push_str("hello");
        assert_eq!(buffer.line(0).as_str(), "hello");
    }

    #[test]
    fn inserts_into_the_middle_after_a_split_target_the_right_row() {
        let mut buffer = Buffer::new();
        buffer.insert_str_at(0, 0, "abc");
        buffer.split_line(0, 2);
        buffer.insert_str_at(1, 0, "xy");
        assert_eq!(buffer.line(0).as_str(), "ab");
        assert_eq!(buffer.line(1).as_str(), "xyc");
    }
}
