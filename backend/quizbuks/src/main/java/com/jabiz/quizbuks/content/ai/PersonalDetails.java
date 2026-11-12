package com.jabiz.quizbuks.content.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes a person's contact details out of a text taken from a website (F-C7, P5): e-mail addresses (also written
 * {@code info＠…}, {@code info[at]…}, {@code info(at)…}, with an internationalised domain), telephone numbers (also
 * fullwidth, with any hyphen, in brackets, without separators, {@code +81…}) and social accounts (a link to a social
 * service's page, {@code LINE: @name}). The text is matched in its NFKC form with every hyphen made one; a text
 * that holds nothing to remove is returned exactly as it was, so that ordinary writing (a size, a season, a part
 * number, a price with dots) is never touched.
 *
 * <p>A telephone number starts with {@code 0} or {@code +} (or a bracket and a {@code 0}), is not the tail of a longer
 * word or number, and has {@value #PHONE_MIN_DIGITS} to {@value #PHONE_MAX_DIGITS} digits in all, groups split by
 * single spaces, dots, hyphens or brackets.
 */
public final class PersonalDetails {

    /** The text, and whether anything was removed from it. */
    public record Cleaned(String text, boolean changed) {}

    static final int PHONE_MIN_DIGITS = 10;
    static final int PHONE_MAX_DIGITS = 13;

    private static final String LOCAL = "[\\p{L}\\p{N}._%+-]+";
    private static final String DOMAIN = "[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.(?:[A-Za-z]{2,}|xn--[A-Za-z0-9-]+)";
    private static final Pattern EMAIL = Pattern.compile(
        LOCAL + "(?:\\s?@\\s?|\\[\\s?at\\s?]|\\(\\s?at\\s?\\)|\\{\\s?at\\s?})" + DOMAIN, Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_TARGET = Pattern.compile("\\]\\((?:mailto|tel):[^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\((https?://[^)\\s]*)\\)");
    private static final Pattern BARE_LINK = Pattern.compile("https?://[^\\s)\\]>]+", Pattern.CASE_INSENSITIVE);
    private static final String SERVICE = "(?:LINE|ＬＩＮＥ|Twitter|Instagram|IG|Facebook|FB|WeChat|WhatsApp|Telegram|TikTok"
        + "|ライン|ツイッター|インスタグラム|インスタ|フェイスブック|微信|微博)";
    // ASCII only: after a colon, ordinary writing ("LINE: 毎日更新") is not an account
    private static final String HANDLE = "@?[A-Za-z0-9._-]{2,}";
    private static final Pattern ACCOUNT = Pattern.compile(
        "(?:" + SERVICE + "\\s*(?:ID|アカウント|账号|帐号|帳號)?\\s*[:：]\\s*" + HANDLE
            + "|" + SERVICE + "\\s*(?:ID\\s*)?@[A-Za-z0-9._-]{2,}"
            + "|\\bX\\s*:?\\s*@[A-Za-z0-9._-]{2,})", Pattern.CASE_INSENSITIVE);
    private static final List<String> SOCIAL_HOSTS = hosts();

    private static List<String> hosts() {
        try (InputStream in = PersonalDetails.class.getResourceAsStream("/quizbuks/ai/social-hosts.txt")) {
            if (in == null) {
                throw new IllegalStateException("quizbuks/ai/social-hosts.txt is missing");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT)).toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The text without a person's contact details; the very text when it holds none. */
    public static Cleaned clean(String text) {
        if (text == null) {
            return new Cleaned(null, false);
        }
        String value = normalize(text);
        String start = value;
        value = LINK_TARGET.matcher(value).replaceAll("](#)");
        value = socialLinks(value);
        value = ACCOUNT.matcher(value).replaceAll("");
        value = EMAIL.matcher(value).replaceAll("");
        value = phones(value);
        if (value.equals(start) && !LINK_TARGET.matcher(start).find()) {
            return new Cleaned(text, false);
        }
        value = value.replaceAll("[ \\t]{2,}", " ").replaceAll("[ \\t]+([.,;:、。])", "$1").strip();
        return new Cleaned(value, true);
    }

    /** The text without a person's contact details. */
    public static String without(String text) {
        return clean(text).text();
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replaceAll("[\\u2010-\\u2015\\u2212\\uFE58\\uFE63\\uFF0D]", "-");
    }

    // ---- social accounts ---------------------------------------------------------------------------------------------

    private static String socialLinks(String text) {
        Matcher link = MARKDOWN_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (link.find()) {
            link.appendReplacement(out, Matcher.quoteReplacement(social(link.group(2)) ? link.group(1) : link.group()));
        }
        link.appendTail(out);
        Matcher bare = BARE_LINK.matcher(out.toString());
        StringBuilder result = new StringBuilder();
        while (bare.find()) {
            bare.appendReplacement(result, Matcher.quoteReplacement(social(bare.group()) ? "" : bare.group()));
        }
        bare.appendTail(result);
        return result.toString();
    }

    private static boolean social(String url) {
        String rest = url.substring(url.indexOf("://") + 3).toLowerCase(Locale.ROOT);
        int end = 0;
        while (end < rest.length() && "/?#:".indexOf(rest.charAt(end)) < 0) {
            end++;
        }
        String host = rest.substring(0, end);
        return SOCIAL_HOSTS.stream().anyMatch(known -> host.equals(known) || host.endsWith("." + known));
    }

    // ---- telephone numbers -------------------------------------------------------------------------------------------

    private static String phones(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int end = starts(text, i) ? phoneEnd(text, i) : -1;
            if (end > i && !hasYear(text, i, end)) {
                i = end;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }

    /** Whether a number may start here: a {@code 0}, {@code +}, or bracket and {@code 0}, not inside a word or a number. */
    private static boolean starts(String text, int at) {
        char c = text.charAt(at);
        boolean lead = c == '+' ? at + 1 < text.length() && Character.isDigit(text.charAt(at + 1))
            : c == '0' || c == '(' && at + 1 < text.length() && text.charAt(at + 1) == '0';
        if (!lead) {
            return false;
        }
        if (at == 0) {
            return true;
        }
        char before = text.charAt(at - 1);
        if (before < 128 && Character.isLetter(before)) {
            // "tel03-…", "fax0312345678": a label written against the number; any other ASCII word before it is a code
            return labelled(text, at);
        }
        if (Character.isDigit(before)) {
            return false;
        }
        // 2026.01.31, 2025.04.01-2026.03.31: the tail of a dotted date or period is not a number
        if (before == '.' && at >= 2 && Character.isDigit(text.charAt(at - 2))) {
            return false;
        }
        return "/_=#?&%-".indexOf(before) < 0;
    }

    private static boolean labelled(String text, int at) {
        return at >= 3 && (text.regionMatches(true, at - 3, "tel", 0, 3) || text.regionMatches(true, at - 3, "fax", 0, 3));
    }

    /**
     * The end of the number starting here, or -1: the longest run of digit groups (single separators between them)
     * that has 10 to 13 digits at a group's end.
     */
    private static int phoneEnd(String text, int at) {
        int length = text.length();
        int i = at;
        if (text.charAt(i) == '+') {
            i++;
        }
        if (i < length && text.charAt(i) == '(') {
            i++;
        }
        int digits = 0;
        int best = -1;
        while (i < length) {
            char c = text.charAt(i);
            if (digit(c)) {
                if (++digits > PHONE_MAX_DIGITS) {
                    break;
                }
                i++;
                if (digits >= PHONE_MIN_DIGITS && !(i < length && digit(text.charAt(i)))) {
                    best = i;
                }
                continue;
            }
            // a separator: up to three characters, one hyphen-like (hyphen, dot, slash, long-vowel mark, middle dot),
            // up to two spaces or brackets; a digit follows
            int j = i;
            int dashes = 0;
            int others = 0;
            while (j < length && j - i < 3 && " .-/ー・()".indexOf(text.charAt(j)) >= 0) {
                if (".-/ー・".indexOf(text.charAt(j)) >= 0) {
                    dashes++;
                } else {
                    others++;
                }
                if (dashes > 1 || others > 2) {
                    break;
                }
                j++;
            }
            if (j == i || dashes > 1 || others > 2 || j >= length || !digit(text.charAt(j))) {
                break;
            }
            i = j;
        }
        return best;
    }

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");

    /**
     * Whether the run holds a four-digit year (19xx, 20xx) written with slashes or dots, as in a date or a period
     * ({@code 03/04/2026 - 05/06/2026}, {@code 01.04.2026 - 30.09.2026}): not a number.
     */
    private static boolean hasYear(String text, int from, int to) {
        Matcher year = YEAR.matcher(text.substring(from, to));
        while (year.find()) {
            String run = text.substring(from, to);
            boolean before = year.start() > 0 && "/.".indexOf(run.charAt(year.start() - 1)) >= 0;
            boolean after = year.end() < run.length() && "/.".indexOf(run.charAt(year.end())) >= 0;
            if (before || after) {
                return true;
            }
        }
        return false;
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private PersonalDetails() {}
}
