/// Returns true if `text`'s first strong-direction character is RTL
/// (Arabic, Hebrew, and their extended blocks).
///
/// The old code used `java.text.Bidi` (`core/utils/TextDirectionHelper.java`),
/// a full Unicode Bidi Algorithm implementation — overkill for "which way
/// does this line start", and exactly the kind of platform dependency
/// AGENTS.md asks to move out of Android/Java and into this crate. This
/// is a first-strong-character heuristic, not a full Bidi implementation:
/// it answers the question the old code was actually used for (line
/// paragraph direction) without pulling in bidi class reordering, which
/// nothing in the old module used anyway.
///
/// Weak/neutral characters (digits, punctuation, whitespace) are skipped
/// when looking for the first strong character, same as `Bidi`'s own
/// paragraph-direction resolution does.
pub fn is_rtl(text: &str) -> bool {
    for ch in text.chars() {
        if is_strong_rtl(ch) {
            return true;
        }
        if is_strong_ltr(ch) {
            return false;
        }
        // neutral/weak — keep scanning
    }
    false
}

fn is_strong_rtl(ch: char) -> bool {
    matches!(ch as u32,
        0x0590..=0x05FF   // Hebrew
        | 0x0600..=0x06FF // Arabic
        | 0x0700..=0x074F // Syriac
        | 0x0750..=0x077F // Arabic Supplement
        | 0x0780..=0x07BF // Thaana
        | 0x08A0..=0x08FF // Arabic Extended-A
        | 0xFB1D..=0xFDFF // Hebrew/Arabic presentation forms A
        | 0xFE70..=0xFEFF // Arabic presentation forms B
    )
}

fn is_strong_ltr(ch: char) -> bool {
    ch.is_alphabetic() && !is_strong_rtl(ch)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_string_is_not_rtl() {
        assert!(!is_rtl(""));
    }

    #[test]
    fn arabic_text_is_rtl() {
        assert!(is_rtl("مرحبا"));
    }

    #[test]
    fn english_text_is_not_rtl() {
        assert!(!is_rtl("hello"));
    }

    #[test]
    fn leading_digits_dont_hide_the_following_arabic() {
        assert!(is_rtl("123 مرحبا"));
    }

    #[test]
    fn leading_digits_dont_hide_the_following_latin() {
        assert!(!is_rtl("123 hello"));
    }

    #[test]
    fn digits_only_is_not_rtl() {
        assert!(!is_rtl("12345"));
    }
}
