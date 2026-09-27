package com.jabiz.file;

import java.util.regex.Pattern;

/**
 * Clean-up of the names clients send with uploads (docs/design/14-files.md section 5). The result is only ever used
 * as the file name of a download, never in a storage path.
 */
public final class FileNames {

    /** Longest name kept, in code points. */
    public static final int MAX_LENGTH = 255;

    static final String FALLBACK = "file";

    private static final Pattern DISALLOWED = Pattern.compile("[^\\p{L}\\p{N} ._()-]");

    private FileNames() {}

    /**
     * Keeps the last path segment, replaces every character outside letters, digits, space and {@code . _ ( ) -}
     * with {@code _}, trims spaces, does not let the name start with a dot, and shortens it to {@link #MAX_LENGTH}
     * code points. A name that ends up empty becomes {@value #FALLBACK}.
     */
    public static String sanitize(String name) {
        if (name == null) {
            return FALLBACK;
        }
        String last = name;
        int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf('\\'));
        if (slash >= 0) {
            last = last.substring(slash + 1);
        }
        String cleaned = DISALLOWED.matcher(last).replaceAll("_").strip();
        if (cleaned.startsWith(".")) {
            cleaned = "_" + cleaned.substring(1);
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_LENGTH) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_LENGTH)).strip();
        }
        return cleaned.isEmpty() ? FALLBACK : cleaned;
    }
}
