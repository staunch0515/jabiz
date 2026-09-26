package com.jabiz.entity;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The finite set of exportable rule kinds the client implements (docs/design/02-metamodel.md section 3, decision
 * D15). An exported rule of any other kind fails when the entity is built: the client could not check it, and the
 * two sides would silently disagree. A new kind needs a client validator in the same change.
 */
public final class RuleKinds {

    public static final String RANGE = "RANGE";
    public static final String SCALE = "SCALE";
    public static final String LENGTH = "LENGTH";
    public static final String PATTERN = "PATTERN";
    public static final String NOT_FUTURE = "NOT_FUTURE";
    public static final String REQUIRED = "REQUIRED";

    public static final Set<String> ALL = Set.of(RANGE, SCALE, LENGTH, PATTERN, NOT_FUTURE, REQUIRED);

    /** Unicode general categories: \p{..} names both Java and JavaScript read as the same category. */
    private static final Pattern CATEGORY = Pattern.compile(
        "L|Lu|Ll|Lt|Lm|Lo|M|Mn|Mc|Me|N|Nd|Nl|No|P|Pc|Pd|Ps|Pe|Pi|Pf|Po|S|Sm|Sc|Sk|So|Z|Zs|Zl|Zp|C|Cc|Cf|Co|Cn");

    /** Escapes with the same meaning in both: classes that are ASCII-only on both sides, and escaped syntax. */
    private static final String PORTABLE_ESCAPES = "dDwWbBntrfv^$\\.*+?()[]{}|/";

    private RuleKinds() {}

    /** Throws when an exported rule's kind is not one the client implements. */
    public static void requireKnown(String code, String kind) {
        if (!ALL.contains(kind)) {
            throw new IllegalArgumentException("Rule " + code + " has kind " + kind
                                               + ", which clients cannot check; exportable kinds are " + ALL.stream().sorted().toList()
                                               + " (use a server-only rule otherwise)");
        }
    }

    /**
     * Throws when {@code regex} is invalid or not read the same way by JavaScript (with the {@code u} flag). The check
     * is an allowlist, since the dialects differ in many small ways: escapes are limited to ASCII classes
     * ({@code \d \w \b} and their negations), control characters, four-digit Unicode escapes, escaped syntax characters,
     * {@code \-} inside a class, and {@code \p{..}}/{@code \P{..}} with a Unicode general category ({@code \s} is not
     * portable: JavaScript counts every Unicode space); groups are limited to {@code (?:}, {@code (?=} and {@code (?!};
     * possessive quantifiers, class intersections and nested classes are refused.
     */
    public static void checkPortablePattern(String code, String regex) {
        if (regex == null || regex.isEmpty()) {
            throw new IllegalArgumentException("Rule " + code + ": PATTERN needs a regular expression");
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Rule " + code + ": invalid regular expression: " + e.getDescription(), e);
        }
        String problem = portabilityProblem(regex);
        if (problem != null) {
            throw new IllegalArgumentException("Rule " + code + ": regular expression uses " + problem
                                               + ", which JavaScript does not read the same way");
        }
    }

    private static String portabilityProblem(String regex) {
        boolean inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\') {
                if (i + 1 >= regex.length()) {
                    return "a trailing \\";
                }
                char e = regex.charAt(i + 1);
                if (e == 'p' || e == 'P') {
                    int close = regex.indexOf('}', i);
                    if (i + 2 >= regex.length() || regex.charAt(i + 2) != '{' || close < 0
                        || !CATEGORY.matcher(regex.substring(i + 3, close)).matches()) {
                        return "\\" + e + " other than a Unicode general category";
                    }
                    i = close;
                    continue;
                }
                if (e == 'u' && i + 5 < regex.length() && regex.substring(i + 2, i + 6).matches("[0-9A-Fa-f]{4}")) {
                    i += 5;
                    continue;
                }
                boolean portable = PORTABLE_ESCAPES.indexOf(e) >= 0 || (inClass && e == '-');
                if (!portable || (e == 'b' || e == 'B') && inClass) {
                    return "\\" + e;
                }
                i++;
                continue;
            }
            if (inClass) {
                if (c == '[') {
                    return "a nested character class";
                }
                if (c == '&' && i + 1 < regex.length() && regex.charAt(i + 1) == '&') {
                    return "a class intersection (&&)";
                }
                if (c == ']') {
                    inClass = false;
                }
                continue;
            }
            if (c == '[') {
                inClass = true;
                // A leading ] or ^] is literal in Java but closes the class in JavaScript.
                int next = i + 1 < regex.length() && regex.charAt(i + 1) == '^' ? i + 2 : i + 1;
                if (next < regex.length() && regex.charAt(next) == ']') {
                    return "a class starting with ]";
                }
                i = next - 1;
                continue;
            }
            if (c == '(' && i + 1 < regex.length() && regex.charAt(i + 1) == '?') {
                String group = regex.substring(i, Math.min(i + 3, regex.length()));
                if (!group.equals("(?:") && !group.equals("(?=") && !group.equals("(?!")) {
                    return group;
                }
                continue;
            }
            if ((c == '*' || c == '+' || c == '?' || c == '}') && i + 1 < regex.length()
                && regex.charAt(i + 1) == '+') {
                return "the possessive quantifier " + c + "+";
            }
        }
        return null;
    }
}
