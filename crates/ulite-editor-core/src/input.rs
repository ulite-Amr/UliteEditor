//! Text mutation operations (insert character, newline, backspace) applied
//! to a [`Buffer`] at a [`CursorPosition`] — the ported `InputProcessor`.

use crate::buffer::Buffer;
use crate::cursor::CursorPosition;

/// Inserts `ch` at the cursor and advances the cursor past it.
///
/// Ports `InputProcessor.insertChar` (`core/logic/InputProcessor.java`).
/// One deliberate behavior change from the Java: the old code indexed by
/// UTF-16 code unit (`StringBuilder.insert(col, c)`), which silently
/// splits characters outside the Basic Multilingual Plane. This indexes
/// by UTF-8 byte offset and always advances by the inserted character's
/// full byte length, so multi-byte input (Arabic, emoji, ...) can't land
/// mid-character. Everything else — insert-then-advance — is unchanged.
pub fn insert_char(buffer: &mut Buffer, cursor: &mut CursorPosition, ch: char) {
    buffer.insert_char_at(cursor.row, cursor.column, ch);
    cursor.column += ch.len_utf8();
}

/// Splits the current line at the cursor: everything from the cursor
/// onward becomes a new line right below, cursor moves to column 0 of
/// that new line. Ports `InputProcessor.handleNewLine`.
pub fn handle_new_line(buffer: &mut Buffer, cursor: &mut CursorPosition) {
    buffer.split_line(cursor.row, cursor.column);
    cursor.row += 1;
    cursor.column = 0;
}

/// Deletes one character (or merges with the previous line at column 0).
/// Ports `InputProcessor.handleBackspace`.
///
/// No-op at buffer start (row 0, column 0) — the old code also had no
/// explicit guard here, but `row > 0` in the `else if` already prevented
/// underflow; this makes the same case explicit rather than relying on
/// the same fallthrough.
pub fn handle_backspace(buffer: &mut Buffer, cursor: &mut CursorPosition) {
    if cursor.column > 0 {
        let line = buffer.line(cursor.row).as_str();
        let prev_char_len = line[..cursor.column]
            .chars()
            .next_back()
            .map(char::len_utf8)
            .unwrap_or(0);
        let deleted_from = cursor.column - prev_char_len;
        buffer.delete_range(cursor.row, deleted_from, cursor.column);
        cursor.column = deleted_from;
    } else if cursor.row > 0 {
        let join_at = buffer.merge_with_previous(cursor.row);
        cursor.row -= 1;
        cursor.column = join_at;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pos(row: usize, column: usize) -> CursorPosition {
        CursorPosition::new(row, column)
    }

    #[test]
    fn insert_char_advances_cursor() {
        let mut buffer = Buffer::new();
        let mut cursor = pos(0, 0);
        insert_char(&mut buffer, &mut cursor, 'h');
        insert_char(&mut buffer, &mut cursor, 'i');
        assert_eq!(buffer.line(0).as_str(), "hi");
        assert_eq!(cursor, pos(0, 2));
    }

    #[test]
    fn insert_multibyte_char_advances_by_full_length() {
        let mut buffer = Buffer::new();
        let mut cursor = pos(0, 0);
        insert_char(&mut buffer, &mut cursor, 'م'); // 2-byte UTF-8 char
        assert_eq!(buffer.line(0).as_str(), "م");
        assert_eq!(cursor, pos(0, 2));
    }

    #[test]
    fn new_line_splits_at_cursor() {
        let mut buffer = Buffer::new();
        buffer.insert_str_at(0, 0, "hello world");
        let mut cursor = pos(0, 5); // after "hello"
        handle_new_line(&mut buffer, &mut cursor);
        assert_eq!(buffer.row_count(), 2);
        assert_eq!(buffer.line(0).as_str(), "hello");
        assert_eq!(buffer.line(1).as_str(), " world");
        assert_eq!(cursor, pos(1, 0));
    }

    #[test]
    fn backspace_within_line_deletes_previous_char() {
        let mut buffer = Buffer::new();
        buffer.insert_str_at(0, 0, "hi");
        let mut cursor = pos(0, 2);
        handle_backspace(&mut buffer, &mut cursor);
        assert_eq!(buffer.line(0).as_str(), "h");
        assert_eq!(cursor, pos(0, 1));
    }

    #[test]
    fn backspace_at_column_zero_merges_with_previous_line() {
        let mut buffer = Buffer::new();
        buffer.insert_str_at(0, 0, "hello");
        let mut cursor = pos(0, 5);
        handle_new_line(&mut buffer, &mut cursor); // -> ["hello", ""]
        insert_char(&mut buffer, &mut cursor, '!'); // -> ["hello", "!"]
                                                    // cursor now at row 1, col 1 ("!" inserted at col 0)
        cursor.column = 0; // simulate cursor moved back to line start
        handle_backspace(&mut buffer, &mut cursor);
        assert_eq!(buffer.row_count(), 1);
        assert_eq!(buffer.line(0).as_str(), "hello!");
        assert_eq!(cursor, pos(0, 5)); // join point = old "hello".len()
    }

    #[test]
    fn backspace_at_buffer_start_is_a_no_op() {
        let mut buffer = Buffer::new();
        let mut cursor = pos(0, 0);
        handle_backspace(&mut buffer, &mut cursor);
        assert_eq!(buffer.row_count(), 1);
        assert_eq!(cursor, pos(0, 0));
    }
}
