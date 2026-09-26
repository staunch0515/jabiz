package com.jabiz.query.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal lexical view of a SQL template. {@link #mask} blanks out everything that is not SQL code (string
 * literals, quoted identifiers, dollar-quoted bodies, comments) while keeping every offset and line break, so that
 * placeholders, parameters and keywords can then be found with plain patterns without matching inside literals.
 */
public final class SqlText {

    private static final Pattern PLACEHOLDER =
        Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)(?:\\.([A-Za-z0-9_]+))?\\s*\\}\\}");
    /** {@code :name}, but not the second colon of a {@code ::} cast nor a slice bound such as {@code a[i:j]}. */
    private static final Pattern PARAMETER = Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern IN_PARAMETER = Pattern.compile("(?i)\\bIN\\s*\\(\\s*:([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    /** A {@code {{Entity}}} or {@code {{Entity.field}}} placeholder; {@code field} is null for the former. */
    public record Placeholder(int start, int end, String entity, String field) {}

    /** A {@code :name} parameter reference. */
    public record ParameterRef(int start, int end, String name) {}

    private SqlText() {}

    /** The SQL with literals, quoted identifiers and comments replaced by spaces; line breaks and offsets kept. */
    public static String mask(String sql) {
        char[] out = sql.toCharArray();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = i + 1;
                while (end < n) {
                    if (sql.charAt(end) == c) {
                        if (end + 1 < n && sql.charAt(end + 1) == c) {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                blank(out, i + 1, Math.min(end, n));
                i = end + 1;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(out, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int depth = 0;
                int end = i;
                while (end < n) {
                    if (sql.startsWith("/*", end)) {
                        depth++;
                        end += 2;
                    } else if (sql.startsWith("*/", end)) {
                        depth--;
                        end += 2;
                        if (depth == 0) {
                            break;
                        }
                    } else {
                        end++;
                    }
                }
                blank(out, i, Math.min(end, n));
                i = end;
            } else if (c == '$' && (i == 0 || !isWordChar(sql.charAt(i - 1)))) {
                Matcher tag = DOLLAR_TAG.matcher(sql).region(i, n);
                if (tag.lookingAt()) {
                    String delimiter = tag.group();
                    int close = sql.indexOf(delimiter, tag.end());
                    int end = close < 0 ? n : close;
                    blank(out, tag.end(), end);
                    i = close < 0 ? n : close + delimiter.length();
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    public static List<Placeholder> placeholders(String masked) {
        List<Placeholder> result = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(masked);
        while (m.find()) {
            result.add(new Placeholder(m.start(), m.end(), m.group(1), m.group(2)));
        }
        return result;
    }

    public static List<ParameterRef> parameters(String masked) {
        List<ParameterRef> result = new ArrayList<>();
        Matcher m = PARAMETER.matcher(masked);
        while (m.find()) {
            result.add(new ParameterRef(m.start(), m.end(), m.group(1)));
        }
        return result;
    }

    /** Parameters written as {@code IN (:name ...)}, which decision D7 forbids. */
    public static List<ParameterRef> inListParameters(String masked) {
        List<ParameterRef> result = new ArrayList<>();
        Matcher m = IN_PARAMETER.matcher(masked);
        while (m.find()) {
            result.add(new ParameterRef(m.start(), m.end(), m.group(1)));
        }
        return result;
    }

    /** Offsets of the given keywords (upper case) that appear outside any parentheses. */
    public static List<Integer> topLevelKeywords(String masked, Set<String> keywords) {
        List<Integer> result = new ArrayList<>();
        int depth = 0;
        int i = 0;
        while (i < masked.length()) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                depth--;
                i++;
            } else if (isWordStart(c) && (i == 0 || !isWordChar(masked.charAt(i - 1)))) {
                int end = i;
                while (end < masked.length() && isWordChar(masked.charAt(end))) {
                    end++;
                }
                if (depth == 0 && keywords.contains(masked.substring(i, end).toUpperCase(Locale.ROOT))) {
                    result.add(i);
                }
                i = end;
            } else {
                i++;
            }
        }
        return result;
    }

    /** Words of SQL code with their offsets: keywords, identifiers, aliases; parameters are excluded. */
    public static List<ParameterRef> words(String masked) {
        List<ParameterRef> result = new ArrayList<>();
        Matcher m = WORD.matcher(masked);
        while (m.find()) {
            int start = m.start();
            if (start > 0 && (isWordChar(masked.charAt(start - 1)) || masked.charAt(start - 1) == ':')) {
                continue;
            }
            result.add(new ParameterRef(start, m.end(), m.group()));
        }
        return result;
    }

    private static void blank(char[] out, int from, int to) {
        for (int i = from; i < to; i++) {
            if (out[i] != '\n') {
                out[i] = ' ';
            }
        }
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
