package com.jabiz.approval;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The hash an approval is bound to (docs/design/18-numbering-approvals-tasks.md section 3.3): SHA-256 (hex) of a
 * canonical text of the content. Canonical: map keys sorted, lists in order, numbers without trailing zeros
 * ({@code 1.0} and {@code 1.00} are the same amount), date-times as the instant they denote (UTC), other values by
 * their text. Two contents that differ in anything but key order, number scale or time zone hash differently.
 */
public final class ContentHash {

    private ContentHash() {}

    public static String of(Map<String, ?> content) {
        StringBuilder text = new StringBuilder();
        write(content, text);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The canonical text (for tests and diagnostics). */
    static String canonical(Map<String, ?> content) {
        StringBuilder text = new StringBuilder();
        write(content, text);
        return text.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((key, item) -> {
                    if (sorted.put(String.valueOf(key), item) != null) {
                        throw new IllegalArgumentException("Content has two keys written " + key);
                    }
                });
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(entry.getKey(), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case Iterable<?> list -> {
                out.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(item, out);
                }
                out.append(']');
            }
            case Boolean bool -> out.append(bool);
            case BigDecimal decimal -> number(decimal, out);
            case Double d -> number(BigDecimal.valueOf(d), out);
            case Float f -> number(new BigDecimal(f.toString()), out);
            case Number number -> number(new BigDecimal(number.toString()), out);
            case OffsetDateTime time -> string(time.toInstant().toString(), out);
            case ZonedDateTime time -> string(time.toInstant().toString(), out);
            default -> string(value.toString(), out);
        }
    }

    private static void number(BigDecimal value, StringBuilder out) {
        out.append(value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString());
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
