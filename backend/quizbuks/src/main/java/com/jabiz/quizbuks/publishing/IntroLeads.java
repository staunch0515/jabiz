package com.jabiz.quizbuks.publishing;

import java.util.regex.Pattern;

/**
 * The beginning of a quiz's introduction as plain text, for the home's post (Q113b-1, docs/quizbuks/plans/impl/Q113.md
 * "Q113b-1 的决定"): frozen on the card when it goes live and when its version switches, in each language, so that the
 * feed never reads a version's whole content. The introduction is Markdown; its marks are dropped (an image entirely,
 * a link down to its text), its lines run together, and a longer text is cut to {@link #LENGTH} characters ending in
 * an ellipsis. Migration V134 fills the cards made before this with the same rules in SQL: change both together.
 * Pure Java.
 */
public final class IntroLeads {

    /** The longest lead, in characters (code points), the ellipsis included; the card's column is this long. */
    public static final int LENGTH = 160;

    private static final String SPACE = "[ \\t\\n\\r\\f]";
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern LIST_MARK = Pattern.compile("(?m)^" + SPACE + "*([-+]|[0-9]+\\.)" + SPACE + "+");
    private static final Pattern MARKS = Pattern.compile("[#*_>`~|]");
    private static final Pattern SPACES = Pattern.compile(SPACE + "+");

    private IntroLeads() {
    }

    /** The lead of an introduction; null for none, a blank one, or one of nothing but marks. */
    public static String of(String introduction) {
        if (introduction == null) {
            return null;
        }
        String text = IMAGE.matcher(introduction).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = LIST_MARK.matcher(text).replaceAll("");
        text = MARKS.matcher(text).replaceAll("");
        text = SPACES.matcher(text).replaceAll(" ");
        if (text.startsWith(" ")) {
            text = text.substring(1);
        }
        if (text.endsWith(" ")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            return null;
        }
        if (text.codePointCount(0, text.length()) <= LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, LENGTH - 1)) + "…";
    }
}
