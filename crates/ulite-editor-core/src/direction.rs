//! Paragraph-direction detection: a first-strong-character heuristic for
//! RTL lines that replaces the old `java.text.Bidi` call — see the `is_rtl`
//! doc for the trade-off.

/// Returns true if `text`'s first strong-direction character is RTL
/// (one of the RTL scripts: Arabic, Hebrew, Syriac, Thaana, NKo,
/// Samaritan, Mandaic, and the rarer ancient/misc RTL scripts).
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
        0x0590..=0x05FF    // Hebrew
        | 0x0600..=0x06FF  // Arabic
        | 0x0700..=0x074F  // Syriac
        | 0x0750..=0x077F  // Arabic Supplement
        | 0x0780..=0x07BF  // Thaana
        | 0x07C0..=0x07FF  // NKo
        | 0x0800..=0x083F  // Samaritan
        | 0x0840..=0x085F  // Mandaic
        | 0x0860..=0x086F  // Syriac Supplement
        | 0x0870..=0x089F  // Arabic Extended-B
        | 0x08A0..=0x08FF  // Arabic Extended-A
        | 0xFB1D..=0xFDFF  // Hebrew/Arabic presentation forms A
        | 0xFE70..=0xFEFF  // Arabic presentation forms B
        | 0x103A0..=0x103DF // Old Persian
        | 0x10530..=0x1056F // Caucasian Albanian
        | 0x10840..=0x1085F // Imperial Aramaic
        | 0x10860..=0x1087F // Palmyrene
        | 0x10880..=0x108AF // Nabataean
        | 0x108E0..=0x108FF // Hatran
        | 0x10900..=0x1091F // Phoenician
        | 0x10920..=0x1093F // Lydian
        | 0x10980..=0x109FF // Meroitic (Hieroglyphs + Cursive)
        | 0x10A00..=0x10A5F // Kharoshthi
        | 0x10A60..=0x10A7F // Old South Arabian
        | 0x10A80..=0x10A9F // Old North Arabian
        | 0x10AC0..=0x10AFF // Manichaean
        | 0x10B00..=0x10B3F // Avestan
        | 0x10B40..=0x10B5F // Inscriptional Parthian
        | 0x10B60..=0x10B7F // Inscriptional Pahlavi
        | 0x10B80..=0x10BAF // Psalter Pahlavi
        | 0x10C00..=0x10C48 // Old Turkic
        | 0x10C80..=0x10CFF // Old Hungarian
        | 0x10D00..=0x10D3F // Hanifi Rohingya
        | 0x10E80..=0x10EBF // Yezidi
        | 0x10EC0..=0x10EFF // Arabic Extended-C
        | 0x10F00..=0x10F2F // Old Sogdian
        | 0x10F30..=0x10F6F // Sogdian
        | 0x10F70..=0x10FAF // Old Uyghur
        | 0x10FB0..=0x10FDF // Chorasmian
        | 0x10FE0..=0x10FFF // Elymaic
        | 0x1E800..=0x1E8DF // Mende Kikakui
        | 0x1E900..=0x1E95F // Adlam
        | 0x1EE00..=0x1EEFF // Arabic Mathematical Alphabetic Symbols
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

    #[test]
    fn newly_covered_rtl_scripts_are_detected() {
        for ch in [
            '\u{07C0}',  // NKo
            '\u{0800}',  // Samaritan
            '\u{0840}',  // Mandaic
            '\u{0860}',  // Syriac Supplement
            '\u{0870}',  // Arabic Extended-B
            '\u{103A0}', // Old Persian
            '\u{10840}', // Imperial Aramaic
            '\u{10900}', // Phoenician
            '\u{10A00}', // Kharoshthi
            '\u{10B00}', // Avestan
            '\u{10C00}', // Old Turkic
            '\u{10D00}', // Hanifi Rohingya
            '\u{10E80}', // Yezidi
            '\u{10EC0}', // Arabic Extended-C
            '\u{10F00}', // Old Sogdian
            '\u{10F70}', // Old Uyghur
            '\u{10FE0}', // Elymaic
            '\u{1E850}', // Mende Kikakui
            '\u{1E900}', // Adlam
        ] {
            assert!(
                is_rtl(&ch.to_string()),
                "U+{:04X} (in a silently-ignored RTL block) must read as RTL",
                ch as u32,
            );
        }
    }

    #[test]
    fn covered_rtl_blocks_do_not_swallow_latin() {
        for ch in ['a', 'Z', '1', '\u{03A9}'] {
            assert!(!is_rtl(&ch.to_string()));
        }
    }
}
