package com.jabiz.quizbuks.settings;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A public certification page's name (Q77, D-Q77-5): {@code /taker/u/<name>}, 3 to 30 lowercase letters, digits and
 * inner hyphens, not one of the words the site uses. Pure Java.
 */
public final class PublicHandles {

    public static final int MAX_LENGTH = 30;

    public static final String FORMAT = "QB_PUBLIC_HANDLE_FORMAT";
    public static final String RESERVED = "QB_PUBLIC_HANDLE_RESERVED";
    public static final String TAKEN = "QB_PUBLIC_HANDLE_TAKEN";
    public static final String NO_HANDLE = "QB_PUBLIC_PAGE_NO_HANDLE";

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{1,28}[a-z0-9])");

    /** Words that would read as the site's own: its pages, roles and brand. */
    static final Set<String> RESERVED_WORDS = Set.of("admin", "administrator", "api", "app", "help", "legal", "login",
        "logout", "me", "official", "operator", "quizbuks", "root", "settings", "signup", "sponsor", "staff",
        "support", "system", "taker", "verify", "www");

    /** The name as stored: trimmed and lowercased; null when blank. */
    public static String normalized(String given) {
        if (given == null || given.isBlank()) {
            return null;
        }
        return given.strip().toLowerCase(Locale.ROOT);
    }

    /** What the name is refused for, {@link #FORMAT} or {@link #RESERVED}; null when it may be used. */
    public static String problem(String normalized) {
        if (normalized == null || !PATTERN.matcher(normalized).matches() || normalized.contains("--")) {
            return FORMAT;
        }
        return RESERVED_WORDS.contains(normalized) ? RESERVED : null;
    }

    private PublicHandles() {}
}
