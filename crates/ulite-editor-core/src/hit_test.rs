//! Pure tap/position geometry over the [`VisualLineSource`] measurement
//! abstraction: where a tap lands ((row, column)) and where a cursor sits
//! (content-space x/y). Owns neither the buffer nor text measurement.

use crate::cursor::CursorPosition;

/// Everything hit-testing needs to know about a row's already-wrapped
/// visual lines, without this crate owning measurement or the buffer
/// itself. Compose (or a test fixture) implements this over whatever it
/// already has — `layout::wrap_line` output plus a `TextMeasurer` — so
/// this module stays pure geometry.
pub trait VisualLineSource {
    /// Number of visual (wrapped) lines the logical row at `row` renders
    /// as. Empty content still counts as 1, matching `wrap_line`.
    fn visual_line_count(&self, row: usize) -> usize;

    /// The text content of one visual line within a row.
    fn visual_line_text(&self, row: usize, visual_line_index: usize) -> &str;

    /// Per-character measured widths for that same visual line, one
    /// entry per Unicode scalar value, same convention as
    /// `layout::wrap_line`'s `char_widths` parameter.
    fn visual_line_char_widths(&self, row: usize, visual_line_index: usize) -> &[f32];

    /// Byte offset of this visual line's first character within the
    /// logical row's full content — i.e. `WrapCache`'s range start for
    /// that visual line.
    fn visual_line_byte_start(&self, row: usize, visual_line_index: usize) -> usize;
}

/// Y coordinate (in content space) where `row` starts rendering.
/// Mirrors the `currentY` accumulation loop at the top of both `onTap`
/// and `syncCursorVisualsAndScroll` — both walked every prior row
/// summing `wrapped.size() * fontSpacing`; factored out here since both
/// old methods duplicated it.
pub fn row_top_y(
    source: &impl VisualLineSource,
    row: usize,
    line_height: f32,
    top_margin: f32,
) -> f32 {
    let mut y = top_margin;
    for r in 0..row {
        y += source.visual_line_count(r) as f32 * line_height;
    }
    y
}

/// Finds the (row, column) the point (`adjusted_x`, `adjusted_y`) lands
/// on — both already in content space (scroll offset added in), same
/// precondition as `onTap`'s `adjustedX`/`adjustedY`. `row_count` is the
/// buffer's total row count, needed to know where to stop the row scan.
///
/// Ports `NoteScribeEngine.onTap` in two stages, same as the original:
/// find the row by walking cumulative row heights, then find the column
/// by walking measured character widths within the tapped visual line,
/// snapping to whichever side of a character the tap is closer to.
pub fn locate_tap(
    source: &impl VisualLineSource,
    row_count: usize,
    adjusted_x: f32,
    adjusted_y: f32,
    line_height: f32,
    top_margin: f32,
    left_margin: f32,
) -> CursorPosition {
    // Stage 1: which row.
    let mut row = 0usize;
    let mut current_y = top_margin;
    for r in 0..row_count {
        let row_height = source.visual_line_count(r) as f32 * line_height;
        if adjusted_y >= current_y && adjusted_y < current_y + row_height {
            row = r;
            break;
        }
        current_y += row_height;
        row = r; // if we fall through to the last row, land there — same
                 // fallthrough behavior `onTap` had (loop just ends with
                 // the last assigned `row`)
    }
    let row_start_y = row_top_y(source, row, line_height, top_margin);

    // Stage 2: which visual line within the row.
    let visual_count = source.visual_line_count(row);
    let relative_y = adjusted_y - row_start_y;
    let visual_index = ((relative_y / line_height) as isize)
        .clamp(0, visual_count as isize - 1) as usize;

    // Stage 3: which column within that visual line.
    let text = source.visual_line_text(row, visual_index);
    let widths = source.visual_line_char_widths(row, visual_index);
    let byte_start = source.visual_line_byte_start(row, visual_index);

    let mut current_x = left_margin;
    let mut byte_offset = 0usize;
    for (char_idx, (offset, _ch)) in text.char_indices().enumerate() {
        let width = widths.get(char_idx).copied().unwrap_or(0.0);
        if adjusted_x < current_x + width / 2.0 {
            byte_offset = offset;
            return CursorPosition::new(row, byte_start + byte_offset);
        }
        current_x += width;
        byte_offset = offset + _ch.len_utf8();
    }

    CursorPosition::new(row, byte_start + byte_offset)
}

/// Computes the cursor's on-screen (content-space) position for
/// rendering and for feeding into `scroll::ScrollState::ensure_visible`.
/// Ports `NoteScribeEngine.syncCursorVisualsAndScroll`'s geometry
/// (everything except the animation tween and the actual
/// `ensure_visible` call, which stay in `CursorManager`/Compose and
/// `scroll.rs` respectively).
pub fn cursor_screen_position(
    source: &impl VisualLineSource,
    cursor: CursorPosition,
    line_height: f32,
    top_margin: f32,
    left_margin: f32,
) -> (f32, f32) {
    let mut y = row_top_y(source, cursor.row, line_height, top_margin);
    let visual_count = source.visual_line_count(cursor.row);

    for visual_index in 0..visual_count {
        let byte_start = source.visual_line_byte_start(cursor.row, visual_index);
        let text = source.visual_line_text(cursor.row, visual_index);
        let byte_end = byte_start + text.len();

        if cursor.column <= byte_end {
            let widths = source.visual_line_char_widths(cursor.row, visual_index);
            let mut x = left_margin;
            for (char_idx, (offset, _)) in text.char_indices().enumerate() {
                if byte_start + offset >= cursor.column {
                    break;
                }
                x += widths.get(char_idx).copied().unwrap_or(0.0);
            }
            return (x, y);
        }
        y += line_height;
    }

    (left_margin, y)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Fixed two-row fixture: row 0 wraps into ["hello ", "world"],
    /// row 1 is a single unwrapped visual line "hi". Every character is
    /// 10px wide, `line_height` 20px, `top_margin` 100px, `left_margin`
    /// 40px — round numbers so expected positions are easy to hand-check.
    struct Fixture;

    impl VisualLineSource for Fixture {
        fn visual_line_count(&self, row: usize) -> usize {
            match row {
                0 => 2,
                1 => 1,
                _ => panic!("no such row in fixture"),
            }
        }

        fn visual_line_text(&self, row: usize, visual_line_index: usize) -> &str {
            match (row, visual_line_index) {
                (0, 0) => "hello ",
                (0, 1) => "world",
                (1, 0) => "hi",
                _ => panic!("no such visual line in fixture"),
            }
        }

        fn visual_line_char_widths(&self, row: usize, visual_line_index: usize) -> &[f32] {
            const SIX: [f32; 6] = [10.0; 6];
            const FIVE: [f32; 5] = [10.0; 5];
            const TWO: [f32; 2] = [10.0; 2];
            match (row, visual_line_index) {
                (0, 0) => &SIX,
                (0, 1) => &FIVE,
                (1, 0) => &TWO,
                _ => panic!("no such visual line in fixture"),
            }
        }

        fn visual_line_byte_start(&self, row: usize, visual_line_index: usize) -> usize {
            match (row, visual_line_index) {
                (0, 0) => 0,
                (0, 1) => 6, // "hello ".len()
                (1, 0) => 0,
                _ => panic!("no such visual line in fixture"),
            }
        }
    }

    #[test]
    fn row_top_y_accumulates_prior_row_heights() {
        let f = Fixture;
        assert_eq!(row_top_y(&f, 0, 20.0, 100.0), 100.0);
        // row 0 has 2 visual lines * 20px = 40px
        assert_eq!(row_top_y(&f, 1, 20.0, 100.0), 140.0);
    }

    #[test]
    fn tap_on_first_visual_line_first_char() {
        let f = Fixture;
        let pos = locate_tap(&f, 2, 41.0, 105.0, 20.0, 100.0, 40.0);
        // x=41 is just past left_margin(40), within half of first 10px
        // char -> lands before it (byte_offset 0)
        assert_eq!(pos, CursorPosition::new(0, 0));
    }

    #[test]
    fn tap_on_second_visual_line_lands_with_byte_offset_from_row_start() {
        let f = Fixture;
        // row 0 spans y=[100,140); second visual line is y=[120,140)
        let pos = locate_tap(&f, 2, 46.0, 125.0, 20.0, 100.0, 40.0);
        // within "world": x=46 is past the midpoint of the first char
        // (40+5=45) but before the midpoint of the second (50+5=55), so
        // it snaps after 1 char -> byte_start(6) + 1 = col 7. Matches
        // `onTap`'s snap-to-nearer-side rule exactly.
        assert_eq!(pos.row, 0);
        assert_eq!(pos.column, 7);
    }

    #[test]
    fn cursor_screen_position_at_row_start() {
        let f = Fixture;
        let (x, y) = cursor_screen_position(&f, CursorPosition::new(1, 0), 20.0, 100.0, 40.0);
        assert_eq!(y, 140.0); // after row 0's 40px
        assert_eq!(x, 40.0);
    }

    #[test]
    fn cursor_screen_position_mid_visual_line() {
        let f = Fixture;
        // row 0, column 8 -> within "world" (byte_start=6), 2 chars in
        let (x, y) = cursor_screen_position(&f, CursorPosition::new(0, 8), 20.0, 100.0, 40.0);
        assert_eq!(y, 120.0); // second visual line of row 0
        assert_eq!(x, 40.0 + 20.0); // left_margin + 2 chars * 10px
    }
}
