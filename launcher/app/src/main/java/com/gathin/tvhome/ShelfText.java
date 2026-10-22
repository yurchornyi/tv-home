package com.gathin.tvhome;

/**
 * Tidies a title for the top shelf. YouTube and channel titles come with emoji, hashtags, "‼", runs of "!!!", a chain of
 * " | " parts and whole words in capitals; Apple TV shows a calm line instead. This keeps the words: emoji and
 * decorative symbols go (the shelf's font has no glyphs for them and would draw boxes), hashtags go, a long chain of
 * parts keeps its first two, and repeated punctuation is cut to one. Capitals stay as written: a name in a shouted
 * title cannot be told from the shouting.
 */
final class ShelfText {

    private ShelfText() { }

    static String clean(String title) {
        if (title == null) return "";
        StringBuilder out = new StringBuilder(title.length());
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);
            if (keep(cp)) out.appendCodePoint(cp);
            else out.append(' ');
        }
        String s = out.toString()
                .replaceAll("(?<=^|\\s)#[\\p{L}\\p{N}_]+", " ")    // hashtags
                .replaceAll("(?<=^|\\s)![\\p{L}]+", " ")            // stream chat commands ("!vpn")
                .replaceAll("([!?.,])\\1+", "$1")                   // "!!!" -> "!"
                .replaceAll("\\s+([!?.,:;])", "$1")                 // no space before punctuation
                .replaceAll("\\s{2,}", " ")
                .trim();
        // "Match | 2nd half | League | Stream link": the first two parts say what it is.
        String[] parts = s.split("\\s*\\|\\s*");
        if (parts.length > 2 && s.length() > 40) s = parts[0] + " | " + parts[1];
        s = s.replaceAll("^[\\s|:\\-–—]+|[\\s|:\\-–—]+$", "");
        return s.isEmpty() ? title.trim() : s;
    }

    /** Text size in sp for a cleaned title: large for a short name, smaller as it grows, so two lines always do. */
    static float size(String title) {
        int n = title.length();
        if (n <= 26) return 30f;
        if (n <= 48) return 26f;
        return 22f;
    }

    private static boolean keep(int cp) {
        if (Character.isLetterOrDigit(cp) || Character.isSpaceChar(cp)) return true;
        int type = Character.getType(cp);
        // Emoji, pictographs, dingbats, arrows and the like are "other symbol"; variation selectors are marks.
        if (type == Character.OTHER_SYMBOL || type == Character.SURROGATE || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED || type == Character.FORMAT || type == Character.CONTROL) return false;
        if (cp == 0x203C || cp == 0x2049) return false;   // "‼" "⁉"
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) return cp < 0xFE00 || cp > 0xFE0F;
        return true;   // punctuation, quotes, dashes, currency, maths stay
    }
}
