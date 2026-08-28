use crate::layout::WrapCache;

/// One logical (unwrapped) line of text plus its wrap cache.
///
/// The old code split this across two classes — `EditorState` held the
/// list of lines, `TextLineModel` held one line's content *and* its own
/// wrap cache (`core/state/EditorState.java`, `model/TextLineModel.java`).
/// Kept together here for the same reason the old code kept them
/// together: the cache's validity is defined entirely in terms of this
/// line's own content, so there's no benefit to storing it elsewhere.
#[derive(Debug, Clone, Default)]
pub struct Line {
    content: String,
    pub(crate) wrap_cache: Option<WrapCache>,
}

impl Line {
    pub fn new(content: impl Into<String>) -> Self {
        Self {
            content: content.into(),
            wrap_cache: None,
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

    /// Any mutation invalidates the wrap cache — same rule as
    /// `TextLineModel.isCacheValid`, which compared the live content
    /// against a stored snapshot on every wrap request. Invalidating here,
    /// at the single mutation point, is equivalent but doesn't need the
    /// snapshot string kept around and re-compared each time.
    fn content_mut(&mut self) -> &mut String {
        self.wrap_cache = None;
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
