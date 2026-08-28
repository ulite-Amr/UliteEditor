use std::ops::Range;

/// A line's wrap result, cached against the inputs it was computed from.
///
/// The old `TextLineModel` cache also snapshotted the line's content
/// string to detect edits (`cachedContentSnapshot`). Here `Line`
/// (`buffer.rs`) clears its own cache on every mutation instead, so this
/// struct only needs to track the *layout* inputs that can change without
/// the content changing: viewport width, wrap mode, and font metrics.
/// `metrics_version` covers that last one — bump it whenever font size or
/// typeface changes (e.g. pinch-zoom) and every cache is invalidated on
/// the next wrap call without this crate needing to know what a "font"
/// is.
#[derive(Debug, Clone)]
pub struct WrapCache {
    viewport_width: u32,
    wrap_enabled: bool,
    metrics_version: u64,
    visual_lines: Vec<Range<usize>>,
}

/// Computes (or returns the cached) visual-line byte ranges for one
/// logical line.
///
/// `char_widths` must have one entry per `Unicode` scalar value in
/// `content`, in order — this is measurement data the caller (Compose,
/// via `TextMeasurer`) owns; this crate never measures text itself, only
/// decides where to break given measurements it's handed. That split is
/// the direct fix for the old code's dependency on `android.graphics.Paint`
/// for both measuring *and* breaking (`LayoutManager.wrapText`, which
/// called `paint.breakText` — a single Android-only call this crate has
/// no equivalent of, by design).
///
/// Returns byte ranges into `content` — empty content yields a single
/// empty range, matching `LayoutManager`'s explicit empty-line handling
/// in both wrap modes.
pub fn wrap_line<'cache>(
    cache: &'cache mut Option<WrapCache>,
    content: &str,
    char_widths: &[f32],
    viewport_width: u32,
    wrap_enabled: bool,
    metrics_version: u64,
) -> &'cache [Range<usize>] {
    let cache_hit = matches!(
        cache,
        Some(c) if c.viewport_width == viewport_width
            && c.wrap_enabled == wrap_enabled
            && c.metrics_version == metrics_version
    );

    if !cache_hit {
        let visual_lines = compute_wrap(content, char_widths, viewport_width, wrap_enabled);
        *cache = Some(WrapCache {
            viewport_width,
            wrap_enabled,
            metrics_version,
            visual_lines,
        });
    }

    &cache.as_ref().unwrap().visual_lines
}

fn compute_wrap(
    content: &str,
    char_widths: &[f32],
    viewport_width: u32,
    wrap_enabled: bool,
) -> Vec<Range<usize>> {
    if content.is_empty() {
        return vec![0..0];
    }

    if !wrap_enabled {
        // Mode A in the old code: no wrap, the whole line is one visual
        // line no matter how long — fastest path, matches
        // `LayoutManager.wrapText`'s `!wordWrapEnabled` branch.
        // The element really is a range (a visual line), so clippy's
        // single_range_in_vec_init suggestion (collect a `Vec<usize>` of
        // indices) would change the type — allowed deliberately.
        #[allow(clippy::single_range_in_vec_init)]
        return vec![0..content.len()];
    }

    // Mode B: greedy character-fill wrap. `Paint.breakText` measures
    // forward from a start index and returns how many characters fit —
    // it does not look for word boundaries, so neither does this. Same
    // trade-off the old code made (simple, fast, can break mid-word).
    let max_width = if viewport_width > 100 {
        (viewport_width - 100) as f32
    } else {
        viewport_width as f32
    };

    let mut ranges = Vec::new();
    let mut char_iter = content.char_indices().peekable();
    let mut width_idx = 0usize;
    let mut segment_start = 0usize;
    let mut segment_width = 0.0f32;
    let mut chars_in_segment = 0usize;

    while let Some(&(byte_idx, _ch)) = char_iter.peek() {
        let ch_width = char_widths.get(width_idx).copied().unwrap_or(0.0);
        let would_overflow = segment_width + ch_width > max_width;

        if would_overflow && chars_in_segment > 0 {
            // Cut before this character — mirrors `breakText` returning
            // fewer chars than would overflow. `count == 0` guard in the
            // old code (infinite-loop prevention) is structurally
            // impossible here: we only ever close a segment once it has
            // at least one character in it.
            let segment_end = byte_idx;
            ranges.push(segment_start..segment_end);
            segment_start = segment_end;
            segment_width = 0.0;
            chars_in_segment = 0;
            continue; // re-evaluate the same character against a fresh segment
        }

        segment_width += ch_width;
        chars_in_segment += 1;
        width_idx += 1;
        char_iter.next();
    }

    ranges.push(segment_start..content.len());
    ranges
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_line_yields_single_empty_range() {
        let mut cache = None;
        let ranges = wrap_line(&mut cache, "", &[], 500, true, 0);
        // Single-element slice holding one visual-line range; clippy's
        // single_range_in_vec_init wants a range-to-index-Vec here, which
        // is a different type — allowed deliberately.
        #[allow(clippy::single_range_in_vec_init)]
        assert_eq!(ranges, &[0..0]);
    }

    #[test]
    fn no_wrap_mode_never_splits() {
        let mut cache = None;
        let widths = vec![10.0; 50];
        let ranges = wrap_line(&mut cache, &"a".repeat(50), &widths, 100, false, 0);
        assert_eq!(ranges.len(), 1);
        assert_eq!(ranges[0], 0..50);
    }

    #[test]
    fn wrap_mode_splits_when_width_exceeded() {
        let mut cache = None;
        // 10 chars, each 20px wide, viewport 220 -> max_width = 120
        // -> 6 chars fit per visual line (120 / 20 = 6)
        let content = "abcdefghij";
        let widths = vec![20.0; 10];
        let ranges = wrap_line(&mut cache, content, &widths, 220, true, 0);
        assert_eq!(ranges, &[0..6, 6..10]);
    }

    #[test]
    fn always_takes_at_least_one_char_even_if_it_overflows() {
        let mut cache = None;
        // single character wider than the viewport itself
        let ranges = wrap_line(&mut cache, "a", &[500.0], 100, true, 0);
        // Same single-element-of-range case as the empty line above.
        #[allow(clippy::single_range_in_vec_init)]
        assert_eq!(ranges, &[0..1]);
    }

    #[test]
    fn cache_hit_skips_recompute_until_inputs_change() {
        let mut cache = None;
        let widths = vec![20.0; 10];
        let first = wrap_line(&mut cache, "abcdefghij", &widths, 220, true, 0).to_vec();
        // same inputs -> cache reused (can't directly observe recompute,
        // but result must stay identical)
        let second = wrap_line(&mut cache, "abcdefghij", &widths, 220, true, 0).to_vec();
        assert_eq!(first, second);

        // metrics_version bump (e.g. zoom) invalidates even with
        // everything else unchanged
        let third = wrap_line(&mut cache, "abcdefghij", &widths, 220, true, 1).to_vec();
        assert_eq!(third, first); // same widths given, so same result — but it *did* recompute
    }
}
