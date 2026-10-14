package com.jabiz.quizbuks.wallet;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The addresses of Stripe's pages (Checkout, Connect onboarding) that QuizBuks sends a browser to. Such an address is
 * accepted only when it starts with the configured prefix, so the prefix itself must pin the host: a whole origin and
 * a path ending in {@code /}, so that nothing after it can change the host ({@code https://checkout.stripe.com} would
 * let {@code https://checkout.stripe.com.evil.example/} through).
 */
final class StripeUrls {

    /** The hosts a local stub may use plain http on (as the pages' {@code stripePage.ts}). */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    /**
     * {@code scheme://host[:port]/path}: no user information, query or fragment; the path takes the unreserved and
     * sub-delimiter characters, {@code :}, {@code @} and percent escapes, never a backslash or whitespace.
     */
    private static final Pattern PREFIX = Pattern.compile("(https?)://(" + LABEL + "(?:\\." + LABEL
        + ")*|\\[::1\\])(?::([0-9]{1,5}))?(/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*)");

    /**
     * What is wrong with an address prefix; null when it pins one origin: {@code https://host[:port]/…/}, or
     * {@code http://} for a stub on this machine ({@code localhost}, {@code 127.0.0.1}, {@code [::1]}).
     */
    static String prefixProblem(String prefix) {
        Matcher m = prefix == null ? null : PREFIX.matcher(prefix);
        if (m == null || !m.matches() || !prefix.endsWith("/")) {
            return "must be an absolute address scheme://host[:port]/… ending in '/', without user information,"
                + " query or fragment";
        }
        if (m.group(3) != null) {
            int port = Integer.parseInt(m.group(3));
            if (port < 1 || port > 65_535) {
                return "port " + m.group(3) + " is out of range";
            }
        }
        if ("http".equals(m.group(1)) && !LOOPBACK.contains(m.group(2))) {
            return "must be an https address (or a local stub's on localhost, 127.0.0.1 or [::1])";
        }
        return null;
    }

    /** Whether an address has no whitespace or control characters (it is followed by a browser as it is). */
    static boolean plain(String url) {
        return url.chars().noneMatch(c -> c <= ' ' || c == 0x7f);
    }

    private StripeUrls() {}
}
