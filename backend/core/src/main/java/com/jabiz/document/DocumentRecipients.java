package com.jabiz.document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The addresses a document is sent to (docs/design/22-documents.md section 5): read from the layout's recipients
 * column - one or several addresses separated by commas, semicolons or line breaks - and compared without regard to
 * case. Only plain addresses ({@code name@example.com}) are taken: no display names, no line breaks, at most 320
 * characters, so that nothing but an address ever reaches a mail header.
 */
public final class DocumentRecipients {

    /** The most addresses one send may have. */
    public static final int MAX = 10;
    private static final int MAX_LENGTH = 320;
    private static final Pattern SEPARATORS = Pattern.compile("[,;\\r\\n]+");
    // Local part and domain of RFC 5322's dot-atom form: what plain addresses look like in practice.
    private static final Pattern ADDRESS = Pattern.compile(
        "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
            + "@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");

    private DocumentRecipients() {}

    /** The addresses in a column's value, trimmed, without empty entries or repeats (first spelling kept). */
    public static List<String> split(Object value) {
        if (value == null) {
            return List.of();
        }
        Map<String, String> addresses = new LinkedHashMap<>();
        for (String part : SEPARATORS.split(String.valueOf(value))) {
            String address = part.trim();
            if (!address.isEmpty()) {
                addresses.putIfAbsent(key(address), address);
            }
        }
        return List.copyOf(addresses.values());
    }

    /** Whether the text is one plain address. */
    public static boolean valid(String address) {
        return address != null && address.length() <= MAX_LENGTH && ADDRESS.matcher(address).matches();
    }

    /** The given addresses that are not among the allowed ones, ignoring case. */
    public static List<String> outside(List<String> addresses, List<String> allowed) {
        List<String> keys = allowed.stream().map(DocumentRecipients::key).toList();
        List<String> outside = new ArrayList<>();
        for (String address : addresses) {
            if (!keys.contains(key(address))) {
                outside.add(address);
            }
        }
        return outside;
    }

    /** The addresses without repeats, ignoring case, first spelling kept. */
    public static List<String> distinct(List<String> addresses) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String address : addresses) {
            seen.putIfAbsent(key(address), address);
        }
        return List.copyOf(seen.values());
    }

    private static String key(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
