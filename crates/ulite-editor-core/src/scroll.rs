//! The scroll camera: bounds, clamping, cursor visibility, and the fling
//! decay — the pure-math half of the old `ScrollManager`.

/// Camera-follow scroll state: bounds, clamped position, and the
/// keep-cursor-visible logic. Ports the pure-math parts of
/// `core/engine/ScrollManager.java`.
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
}

/// Pixels of safety margin kept between the cursor and the viewport edge
/// before the camera moves — same constant and same purpose as
/// `ScrollManager.SCROLL_OFFSET`.
pub const SCROLL_OFFSET: f32 = 50.0;

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
        if self.velocity_x.abs() < FLING_STOP_VELOCITY && self.velocity_y.abs() < FLING_STOP_VELOCITY {
            self.velocity_x = 0.0;
            self.velocity_y = 0.0;
            return false;
        }

        self.scroll_x = (self.scroll_x + self.velocity_x * dt_seconds).clamp(0.0, self.max_scroll_x);
        self.scroll_y = (self.scroll_y + self.velocity_y * dt_seconds).clamp(0.0, self.max_scroll_y);

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
    fn scroll_by_clamps_to_bounds() {
        let mut s = ScrollState::new();
        s.update_bounds(500.0, 500.0, 400.0, 400.0);
        s.scroll_by(10_000.0, -10_000.0);
        assert_eq!(s.scroll_x(), s.max_scroll_x);
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
