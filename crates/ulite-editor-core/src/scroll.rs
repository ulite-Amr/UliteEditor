//! The scroll camera: bounds, clamping, cursor visibility, and the fling
//! decay — the pure-math half of the old `ScrollManager`.

/// Camera-follow scroll state: bounds, clamped position, and the
/// keep-cursor-visible logic. Ports the pure-math parts of
/// `core/engine/ScrollManager.java`, plus one deliberate extension,
/// `follow_caret_after_edit` (`ScrollManager` never existed as a base for
/// it — see the method doc), for the anchored-while-typing fix.
///
/// What's *not* ported: `ScrollManager` delegated its fling deceleration
/// to `android.widget.OverScroller`, a platform class whose internal
/// friction/velocity curve isn't in this repo to read — there's no old
/// source for it, so there's nothing to port faithfully. `fling` below
/// is new physics (simple exponential decay), not a translation. If the
/// exact old feel matters, that's a follow-up task: tune `FLING_FRICTION`
/// against a device, not something to guess about here.
#[derive(Debug, Clone)]
pub struct ScrollState {
    scroll_x: f32,
    scroll_y: f32,
    max_scroll_x: f32,
    max_scroll_y: f32,
    velocity_x: f32,
    velocity_y: f32,
    // The caret's content-space y from the previous `follow_caret_after_edit`
    // call — the reference point for the bottom-band anchoring (set every
    // call, never reset; taps go through `ensure_visible` and leave it alone,
    // and the band check self-corrects anyway because it compares against the
    // current scroll).
    follow_anchor_y: Option<f32>,
}

/// Pixels of safety margin kept between the cursor and the viewport edge
/// before the camera moves — same constant and same purpose as
/// `ScrollManager.SCROLL_OFFSET`.
pub const SCROLL_OFFSET: f32 = 50.0;

/// Fraction of the viewport height that defines the *typing band*: once the
/// caret's bottom sits in the lower half of the screen while the user types,
/// `follow_caret_after_edit` pins the caret row and slides the camera down
/// exactly one line per committed edit. Without the band, the ported
/// `ensureVisible` held the camera frozen while typing past the content
/// bottom (up to half a viewport of dead room) and then snapped it down by
/// several lines at once — the on-device "viewport jumps / upper content is
/// pushed out abruptly" glitch.
const FOLLOW_BAND_FRACTION: f32 = 0.5;

/// Per-second velocity decay multiplier for `fling`/`tick_fling`. New
/// value, not ported — see the struct doc comment.
const FLING_FRICTION_PER_SECOND: f32 = 0.05;

/// Below this speed a fling is considered finished, matching the intent
/// (not the exact threshold) of `OverScroller.computeScrollOffset()`
/// returning false once its internal animation ends.
const FLING_STOP_VELOCITY: f32 = 4.0;

impl Default for ScrollState {
    fn default() -> Self {
        Self {
            scroll_x: 0.0,
            scroll_y: 0.0,
            max_scroll_x: 0.0,
            max_scroll_y: 0.0,
            velocity_x: 0.0,
            velocity_y: 0.0,
            follow_anchor_y: None,
        }
    }
}

impl ScrollState {
    /// A stationary camera at (0, 0) with zero scroll bounds — call
    /// `update_bounds` before use.
    pub fn new() -> Self {
        Self::default()
    }

    /// Current horizontal scroll — pixels scrolled away from the
    /// content-space origin.
    pub fn scroll_x(&self) -> f32 {
        self.scroll_x
    }

    /// Current vertical scroll — pixels scrolled away from the
    /// content-space origin.
    pub fn scroll_y(&self) -> f32 {
        self.scroll_y
    }

    /// Recomputes scroll bounds from content and viewport size. Keeps the
    /// same "half a viewport of extra room past the end" behavior as
    /// `updateBounds`'s `extraBottom`/`extraEnd`, and the same
    /// re-clamp-after-resize behavior.
    pub fn update_bounds(
        &mut self,
        content_width: f32,
        content_height: f32,
        viewport_width: f32,
        viewport_height: f32,
    ) {
        let extra_end = viewport_width / 2.0;
        let extra_bottom = viewport_height / 2.0;

        self.max_scroll_x = (content_width - viewport_width + extra_end).max(0.0);
        self.max_scroll_y = (content_height - viewport_height + extra_bottom).max(0.0);

        self.scroll_x = self.scroll_x.min(self.max_scroll_x);
        self.scroll_y = self.scroll_y.min(self.max_scroll_y);
    }

    /// Moves the camera just enough to keep (`cursor_x`, `cursor_y`) —
    /// `line_height` tall — inside the viewport with `SCROLL_OFFSET`
    /// margin on every edge. Direct port of `ensureVisible`: same four
    /// edge checks, same order, same clamp-at-the-end. Returns whether
    /// scroll actually moved, so callers know whether a redraw is needed
    /// (replaces the old code's direct `viewActions.requestRedraw()`
    /// side effect — this crate has no view to redraw, so it reports the
    /// fact and lets the caller decide).
    pub fn ensure_visible(
        &mut self,
        cursor_x: f32,
        cursor_y: f32,
        line_height: f32,
        viewport_width: f32,
        viewport_height: f32,
    ) -> bool {
        let mut needs_scroll = false;

        if cursor_y + line_height > self.scroll_y + viewport_height - SCROLL_OFFSET {
            self.scroll_y = cursor_y + line_height - viewport_height + SCROLL_OFFSET;
            needs_scroll = true;
        } else if cursor_y < self.scroll_y + SCROLL_OFFSET {
            self.scroll_y = cursor_y - SCROLL_OFFSET;
            needs_scroll = true;
        }

        if cursor_x > self.scroll_x + viewport_width - SCROLL_OFFSET {
            self.scroll_x = cursor_x - viewport_width + SCROLL_OFFSET;
            needs_scroll = true;
        } else if cursor_x < self.scroll_x + SCROLL_OFFSET {
            self.scroll_x = cursor_x - SCROLL_OFFSET;
            needs_scroll = true;
        }

        self.scroll_x = self.scroll_x.clamp(0.0, self.max_scroll_x);
        self.scroll_y = self.scroll_y.clamp(0.0, self.max_scroll_y);

        needs_scroll
    }

    /// Camera-follow for typed edits: keeps the caret's row pinned exactly
    /// where it is on screen for as long as it works in the bottom *typing
    /// band* (see `FOLLOW_BAND_FRACTION`), then falls back to the ordinary
    /// margin behavior (`ensure_visible`, below) and the final clamp. This
    /// is the smooth complement to `ensure_visible`: that one only acts once the
    /// caret crosses an edge, so typing at the bottom froze the view and then
    /// snapped it down by several lines at once. Here the camera translates
    /// by the caret's own movement each edit (line-by-line on Enter, the
    /// merged line's height on Backspace-merge), which removes both the snap
    /// and the "deleted text reappears" lurch — `ensure_visible` on deletion
    /// re-clamped the view down a line as the content height shrank.
    ///
    /// Deliberate divergence from the ported `ScrollManager`, requested on
    /// device; the reason lives in `.project/PROGRESS.md`. Idempotent for a
    /// given caret: repeating the call with unchanged inputs translates the
    /// camera by zero. Returns whether scroll actually moved, and cancels an
    /// in-flight fling when the follow does move (the old engine aborted its
    /// `OverScroller` on camera corrections too).
    pub fn follow_caret_after_edit(
        &mut self,
        caret_x: f32,
        caret_y: f32,
        line_height: f32,
        viewport_width: f32,
        viewport_height: f32,
    ) -> bool {
        let mut moved = false;

        if let Some(anchor_y) = self.follow_anchor_y {
            // The band test compares the previously-anchored caret against
            // this call's scroll, so an intervening drag that moved the caret
            // out of the band simply lets following lapse until typing brings
            // it back.
            let band_floor = viewport_height * FOLLOW_BAND_FRACTION;
            if anchor_y + line_height - self.scroll_y >= band_floor {
                let travel = caret_y - anchor_y;
                if travel != 0.0 {
                    self.scroll_y += travel;
                    moved = true;
                    // Camera corrections abort the whole fling, matching the
                    // old engine aborting its OverScroller on them — zero
                    // both axes, not just the one we moved.
                    self.velocity_x = 0.0;
                    self.velocity_y = 0.0;
                }
            }
        }
        self.follow_anchor_y = Some(caret_y);

        // Every remaining edge (including horizontal) and the final clamp
        // come from the original margin behavior.
        moved |= self.ensure_visible(
            caret_x,
            caret_y,
            line_height,
            viewport_width,
            viewport_height,
        );

        moved
    }

    /// Direct drag/pan input, in pixels, clamped to the bounds.
    ///
    /// Ports `ScrollManager.scrollBy`. Unlike `ensure_visible` this never
    /// looks at the cursor — it's raw one-finger panning, and canceling an
    /// in-flight fling here mirrors `ScrollManager` stopping the
    /// `OverScroller` on touch-down.
    pub fn scroll_by(&mut self, dx: f32, dy: f32) {
        self.velocity_x = 0.0;
        self.velocity_y = 0.0;
        self.scroll_x = (self.scroll_x + dx).clamp(0.0, self.max_scroll_x);
        self.scroll_y = (self.scroll_y + dy).clamp(0.0, self.max_scroll_y);
    }

    /// Sets the scroll position absolutely, clamped to the bounds, and
    /// cancels any in-flight fling.
    ///
    /// There is no old counterpart: the old code only ever scrolled
    /// relatively (`scrollBy`) or via `OverScroller`/`ensureVisible`.
    /// Pinch-zoom (new scope) needs absolute positioning so it can re-anchor
    /// the camera around the focal point of a scale gesture — sora-editor's
    /// convention is `newScroll = (oldScroll + focus) * scaleFactor - focus`.
    pub fn set_scroll(&mut self, x: f32, y: f32) {
        self.velocity_x = 0.0;
        self.velocity_y = 0.0;
        self.scroll_x = x.clamp(0.0, self.max_scroll_x);
        self.scroll_y = y.clamp(0.0, self.max_scroll_y);
    }

    /// Starts a fling with the given release velocity (pixels/second).
    /// New physics, see the struct doc comment — not a port of
    /// `OverScroller.fling`.
    pub fn start_fling(&mut self, velocity_x: f32, velocity_y: f32) {
        self.velocity_x = velocity_x;
        self.velocity_y = velocity_y;
    }

    /// Advances the fling by `dt_seconds` (call once per frame from the
    /// Compose side's `withFrameNanos` loop). Returns whether the fling
    /// is still moving — false once it's decayed below
    /// `FLING_STOP_VELOCITY`, which callers can use the way the old code
    /// used `computeScroll`'s return value.
    pub fn tick_fling(&mut self, dt_seconds: f32) -> bool {
        if self.velocity_x.abs() < FLING_STOP_VELOCITY
            && self.velocity_y.abs() < FLING_STOP_VELOCITY
        {
            self.velocity_x = 0.0;
            self.velocity_y = 0.0;
            return false;
        }

        self.scroll_x =
            (self.scroll_x + self.velocity_x * dt_seconds).clamp(0.0, self.max_scroll_x);
        self.scroll_y =
            (self.scroll_y + self.velocity_y * dt_seconds).clamp(0.0, self.max_scroll_y);

        let decay = (1.0 - FLING_FRICTION_PER_SECOND).powf(dt_seconds * 60.0);
        self.velocity_x *= decay;
        self.velocity_y *= decay;

        // Stop dead at a clamped edge instead of continuing to "push"
        // against a bound with no visible effect.
        if self.scroll_x <= 0.0 || self.scroll_x >= self.max_scroll_x {
            self.velocity_x = 0.0;
        }
        if self.scroll_y <= 0.0 || self.scroll_y >= self.max_scroll_y {
            self.velocity_y = 0.0;
        }

        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn update_bounds_adds_half_viewport_past_content_end() {
        let mut s = ScrollState::new();
        s.update_bounds(1000.0, 2000.0, 400.0, 800.0);
        assert_eq!(s.max_scroll_x, 1000.0 - 400.0 + 200.0);
        assert_eq!(s.max_scroll_y, 2000.0 - 800.0 + 400.0);
    }

    #[test]
    fn update_bounds_never_goes_negative_for_small_content() {
        let mut s = ScrollState::new();
        s.update_bounds(10.0, 10.0, 400.0, 800.0);
        assert_eq!(s.max_scroll_x, 0.0);
        assert_eq!(s.max_scroll_y, 0.0);
    }

    #[test]
    fn ensure_visible_scrolls_down_when_cursor_below_viewport() {
        let mut s = ScrollState::new();
        s.update_bounds(500.0, 5000.0, 400.0, 800.0);
        let moved = s.ensure_visible(50.0, 900.0, 30.0, 400.0, 800.0);
        assert!(moved);
        assert_eq!(s.scroll_y(), 900.0 + 30.0 - 800.0 + SCROLL_OFFSET);
    }

    #[test]
    fn ensure_visible_is_noop_when_cursor_already_comfortably_visible() {
        let mut s = ScrollState::new();
        s.update_bounds(500.0, 5000.0, 400.0, 800.0);
        let moved = s.ensure_visible(200.0, 400.0, 30.0, 400.0, 800.0);
        assert!(!moved);
        assert_eq!(s.scroll_y(), 0.0);
    }

    #[test]
    fn follow_anchors_the_caret_row_in_the_bottom_band() {
        let mut s = ScrollState::new();
        s.update_bounds(1000.0, 20_000.0, 400.0, 800.0);
        // Caret bottom (630) sits in the lower half of the empty viewport
        // without forcing `ensure_visible` (which only acts past 750), so
        // this call just seeds the anchor and does nothing.
        let moved = s.follow_caret_after_edit(50.0, 600.0, 30.0, 400.0, 800.0);
        assert!(!moved);
        assert_eq!(s.scroll_y(), 0.0);
        // Each Enter pushes the caret down a line and the camera follows by
        // exactly one line height — line-by-line, never a snap.
        let moved = s.follow_caret_after_edit(50.0, 630.0, 30.0, 400.0, 800.0);
        assert!(moved);
        assert_eq!(s.scroll_y(), 30.0);
        let moved = s.follow_caret_after_edit(50.0, 660.0, 30.0, 400.0, 800.0);
        assert!(moved);
        assert_eq!(s.scroll_y(), 60.0);
        assert_eq!(s.velocity_y, 0.0);
    }

    #[test]
    fn follow_backspaces_merge_traverses_up_one_line_at_a_time() {
        let mut s = ScrollState::new();
        s.update_bounds(1000.0, 20_000.0, 400.0, 800.0);
        // First call: caret at 800, `ensure_visible` scrolls 80 to keep it
        // visible; the follow anchor is seeded at 800.
        let moved = s.follow_caret_after_edit(50.0, 800.0, 30.0, 400.0, 800.0);
        assert!(moved);
        assert_eq!(s.scroll_y(), 80.0);
        // Backspace merges a line, so the content shrinks (20_000 -> 800) in
        // the same pass. The old camera re-clamped to the shorter content
        // end on that shrink — the "deleted text reappears" lurch; the follow
        // instead translates up by exactly the merged line's height.
        s.update_bounds(1000.0, 800.0, 400.0, 800.0);
        let moved = s.follow_caret_after_edit(50.0, 770.0, 30.0, 400.0, 800.0);
        assert!(moved);
        assert_eq!(s.scroll_y(), 50.0);
    }

    #[test]
    fn follow_is_idempotent_for_an_unchanged_caret() {
        let mut s = ScrollState::new();
        s.update_bounds(1000.0, 20_000.0, 400.0, 800.0);
        s.follow_caret_after_edit(50.0, 850.0, 30.0, 400.0, 800.0);
        let scroll_y = s.scroll_y();
        let moved = s.follow_caret_after_edit(50.0, 850.0, 30.0, 400.0, 800.0);
        assert!(!moved);
        assert_eq!(s.scroll_y(), scroll_y);
    }

    #[test]
    fn follow_leaves_the_upper_screen_alone() {
        let mut s = ScrollState::new();
        s.update_bounds(1000.0, 20_000.0, 400.0, 800.0);
        // Caret near the top: no follow (band requires the lower half) and no
        // margin move.
        let moved = s.follow_caret_after_edit(50.0, 300.0, 30.0, 400.0, 800.0);
        assert!(!moved);
        assert_eq!(s.scroll_y(), 0.0);
        let moved = s.follow_caret_after_edit(50.0, 330.0, 30.0, 400.0, 800.0);
        assert!(!moved);
        assert_eq!(s.scroll_y(), 0.0);
    }

    #[test]
    fn follow_clamps_to_the_content_bounds() {
        let mut s = ScrollState::new();
        // max_scroll_y = 1800 - 800 + 400 = 1400.
        s.update_bounds(1000.0, 1800.0, 400.0, 800.0);
        s.follow_caret_after_edit(50.0, 1600.0, 30.0, 400.0, 800.0);
        // A big paste slams the caret 700px down; the camera must follow but
        // never exceed the content-end bound.
        let moved = s.follow_caret_after_edit(50.0, 2300.0, 30.0, 400.0, 800.0);
        assert!(moved);
        assert_eq!(s.scroll_y(), s.max_scroll_y);
    }

    #[test]
    fn scroll_by_clamps_to_bounds() {
        let mut s = ScrollState::new();
        s.update_bounds(500.0, 500.0, 400.0, 400.0);
        s.scroll_by(10_000.0, -10_000.0);
        assert_eq!(s.scroll_x(), s.max_scroll_x);
        assert_eq!(s.scroll_y(), 0.0);
    }

    #[test]
    fn set_scroll_clamps_absolutely_and_cancels_fling() {
        let mut s = ScrollState::new();
        s.update_bounds(1000.0, 1000.0, 400.0, 400.0);
        s.start_fling(500.0, 500.0);
        s.set_scroll(200.0, 30_000.0);
        assert_eq!(s.scroll_x(), 200.0);
        assert_eq!(s.scroll_y(), s.max_scroll_y);
        assert_eq!(s.velocity_x, 0.0);
        assert_eq!(s.velocity_y, 0.0);
        s.set_scroll(-500.0, -500.0);
        assert_eq!(s.scroll_x(), 0.0);
        assert_eq!(s.scroll_y(), 0.0);
    }

    #[test]
    fn fling_decays_to_a_stop() {
        let mut s = ScrollState::new();
        s.update_bounds(10_000.0, 10_000.0, 400.0, 800.0);
        s.start_fling(2000.0, 0.0);
        let mut ticks = 0;
        while s.tick_fling(1.0 / 60.0) {
            ticks += 1;
            assert!(ticks < 10_000, "fling never settled");
        }
        assert_eq!(s.velocity_x, 0.0);
    }
}
