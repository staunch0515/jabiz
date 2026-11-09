package com.jabiz.quizbuks.help;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What of an AI answer is kept (F-C10: only same-site links; the answer is shown as plain text and links): links are
 * the site's own paths ({@code /help/…}) or addresses of the site's host, turned into paths; every other address is
 * taken out of the text, a Markdown link keeping its words. Pure, so that the job's outcome (the last word) and the
 * tests apply the same rule.
 */
public final class HelpAnswers {

    /** The most links one answer keeps. */
    public static final int MAX_LINKS = 5;
    /** The longest path kept. */
    public static final int MAX_PATH = 200;

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]\\n]{0,200})]\\(([^)\\s]{1,500})\\)");
    private static final Pattern ADDRESS = Pattern.compile(
        "(?i)\\b(?:[a-z][a-z0-9+.-]{1,20}://|www\\.)[^\\s<>\"'()\\[\\]{}（）「」【】、，。]+");
    private static final Pattern MAIL = Pattern.compile("(?i)\\bmailto:\\S+");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9._~!$&'()*+,;=:@%/?#-]*");

    private HelpAnswers() {}

    /** The site's host of an address such as {@code https://quizbuks.com}; null when there is none. */
    public static String hostOf(String siteUrl) {
        if (siteUrl == null || siteUrl.isBlank() || siteUrl.contains("@")) {
            return null;
        }
        try {
            String host = URI.create(siteUrl.strip()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The links kept: the site's paths, at most {@link #MAX_LINKS}, each once, in order. */
    public static List<String> links(List<String> links, Set<String> siteHosts) {
        Set<String> kept = new LinkedHashSet<>();
        if (links != null) {
            for (String link : links) {
                String path = sitePath(link, siteHosts);
                if (path != null && kept.size() < MAX_LINKS) {
                    kept.add(path);
                }
            }
        }
        return List.copyOf(kept);
    }

    /**
     * The answer's text with every address that is not the site's taken out: a Markdown link keeps its words (and the
     * site's path in brackets when it is the site's), a bare address of the site becomes its path, any other goes.
     */
    public static String text(String text, Set<String> siteHosts) {
        if (text == null) {
            return "";
        }
        Matcher markdown = MARKDOWN_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (markdown.find()) {
            String path = sitePath(markdown.group(2), siteHosts);
            String words = markdown.group(1).strip();
            markdown.appendReplacement(out, Matcher.quoteReplacement(path == null ? words
                : words.isEmpty() ? path : words + " (" + path + ")"));
        }
        markdown.appendTail(out);
        String withoutMail = MAIL.matcher(out.toString()).replaceAll("");
        Matcher address = ADDRESS.matcher(withoutMail);
        StringBuilder cleaned = new StringBuilder();
        while (address.find()) {
            String path = sitePath(address.group(), siteHosts);
            address.appendReplacement(cleaned, Matcher.quoteReplacement(path == null ? "" : path));
        }
        address.appendTail(cleaned);
        return cleaned.toString().replaceAll("[ \\t]{2,}", " ").strip();
    }

    /** The answer's text cut at {@code max} characters (with an ellipsis), whole code points only. */
    public static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = max - 1;
        if (Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    /** The site's path a link stands for; null when it is not the site's. */
    static String sitePath(String link, Set<String> siteHosts) {
        if (link == null) {
            return null;
        }
        String value = link.strip();
        if (value.isEmpty() || value.length() > 500) {
            return null;
        }
        if (value.startsWith("/")) {
            return path(value);
        }
        String candidate = value.regionMatches(true, 0, "www.", 0, 4) ? "https://" + value : value;
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme)) || host == null || uri.getUserInfo() != null
                || siteHosts == null || !siteHosts.contains(host)) {
                return null;
            }
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            String rest = (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            return path(path + rest);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A path of the site's: one leading slash, no scheme-relative address, no backslash, nothing but URL characters. */
    private static String path(String value) {
        if (value.startsWith("//") || value.contains("\\") || value.length() > MAX_PATH
            || !PATH.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    /** The links as stored: one a line. */
    public static String joined(List<String> links) {
        return links == null || links.isEmpty() ? null : String.join("\n", links);
    }

    /** The links as stored, back in a list. */
    public static List<String> split(Object stored) {
        List<String> links = new ArrayList<>();
        if (stored != null) {
            for (String line : stored.toString().split("\n")) {
                if (!line.isBlank()) {
                    links.add(line.strip());
                }
            }
        }
        return links;
    }
}
