package com.jabiz.quizbuks.sponsorpackage;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON values as the sponsor page's package code sees them (F-LAUNCH Q1, docs/quizbuks/plans/impl/F-LAUNCH.md §1.2):
 * a JSON object is a {@code Map<String, Object>}, an array a {@code List<Object>}, a number any {@link Number}, as
 * Jackson reads untyped JSON. What a request takes is counted as the page counts it ({@code JSON.stringify}, UTF-8):
 * the byte limits of a content's steps are cut by it, so the plan's steps are the same on both sides.
 */
public final class PackageJson {

    private PackageJson() {}

    /** Whether the value is a JSON object (the page's {@code isObject}). */
    public static boolean isObject(Object value) {
        return value instanceof Map<?, ?>;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    /** An array's items, or none when the value is not one. */
    public static List<Object> items(Object value) {
        return value instanceof List<?> ? array(value) : List.of();
    }

    /** A JSON number that is a whole number (the page's {@code Number.isInteger}). */
    public static boolean isInteger(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = number.doubleValue();
            return !Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        return true;
    }

    /** The value's JavaScript truthiness (the page's {@code Boolean(value)}). */
    public static boolean truthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        return true;
    }

    /**
     * The text without the white space and line ends JavaScript's {@code String.prototype.trim} removes (its set,
     * not Java's: no-break spaces and the byte-order mark go, the information separators stay).
     */
    public static String jsTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && jsSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && jsSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean jsSpace(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0xA0 || c == 0x1680
            || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000
            || c == 0xFEFF;
    }

    /**
     * The value with every object's keys in JavaScript's order: the array indices ({@code "0"}, {@code "12"}: canonical
     * whole numbers below 2^32 - 1) ascending first, then the others as written. The page iterates a package's
     * objects in this order ({@code Object.entries}, {@code Object.values}), so the plan reads them so too.
     */
    public static Object jsOrdered(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : jsKeys(map.keySet())) {
                out.put(key, jsOrdered(map.get(key)));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(jsOrdered(item)));
            return out;
        }
        return value;
    }

    /** The keys in JavaScript's order (see {@link #jsOrdered}). */
    static List<String> jsKeys(Iterable<?> keys) {
        List<String> indices = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (Object key : keys) {
            String text = String.valueOf(key);
            (arrayIndex(text) ? indices : others).add(text);
        }
        indices.sort((a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)));
        indices.addAll(others);
        return indices;
    }

    private static boolean arrayIndex(String key) {
        return key.matches("0|[1-9][0-9]{0,9}") && Long.parseLong(key) < 4_294_967_295L;
    }

    /** A JSON object of the pairs given, in their order. */
    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** A copy of a JSON object, its keys in their order. */
    public static Map<String, Object> copy(Map<String, Object> value) {
        return new LinkedHashMap<>(value);
    }

    /**
     * The document as a request sends it: its {@code files} without their data (the page's {@code withoutData}: the
     * files go up first, the request names them).
     */
    public static Map<String, Object> withoutData(Map<String, Object> document) {
        Object files = document.get("files");
        if (!isObject(files)) {
            return document;
        }
        Map<String, Object> stripped = new LinkedHashMap<>();
        object(files).forEach((key, entry) -> {
            if (isObject(entry)) {
                Map<String, Object> rest = copy(object(entry));
                rest.remove("data");
                stripped.put(key, rest);
            } else {
                stripped.put(key, entry);
            }
        });
        Map<String, Object> out = copy(document);
        out.put("files", stripped);
        return out;
    }

    /** The bytes a document takes in a request: its JSON without the files' data, in UTF-8 (the page's {@code bytesOf}). */
    public static long bytesOf(Map<String, Object> document) {
        return byteLength(withoutData(document));
    }

    /** The UTF-8 bytes of {@code JSON.stringify(value)}. */
    public static long byteLength(Object value) {
        return write(value, false).getBytes(StandardCharsets.UTF_8).length;
    }

    /** {@code JSON.stringify(value)}, as JavaScript writes it (numbers, escapes); the keys in their order. */
    public static String stringify(Object value) {
        return write(value, false);
    }

    /**
     * A digest of the value with every object's keys sorted (UTF-16 order, as JavaScript's sort; written back into an
     * object, so the array indices come first, ascending, as {@link #jsOrdered}): two sides that make
     * the same content in another key order give the same digest. The comparison tests of the plan use it.
     */
    public static String digest(Object value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(write(value, true)
                .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The value's JSON with every object's keys sorted (what {@link #digest} hashes). */
    static String canonical(Object value) {
        return write(value, true);
    }

    private static String write(Object value, boolean sorted) {
        StringBuilder out = new StringBuilder();
        write(value, sorted, out);
        return out.toString();
    }

    private static void write(Object value, boolean sorted, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Boolean b -> out.append(b);
            case String s -> quote(s, out);
            case Number n -> out.append(number(n));
            case Map<?, ?> map -> {
                List<String> written = new ArrayList<>();
                map.keySet().forEach(key -> written.add(String.valueOf(key)));
                if (sorted) {
                    written.sort(String::compareTo);
                }
                List<String> keys = sorted ? jsKeys(written) : written;
                out.append('{');
                boolean first = true;
                for (String key : keys) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(key, out);
                    out.append(':');
                    write(map.get(key), sorted, out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), sorted, out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
        }
    }

    /** A string as {@code JSON.stringify} quotes it: the short escapes, other controls and lone surrogates as \\u. */
    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(i + 1))) {
                        out.append(c).append(text.charAt(++i));
                    } else if (Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A number as JavaScript writes it ({@code Number.prototype.toString}; not finite: {@code null}). */
    static String number(Number value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            long n = value.longValue();
            if (Math.abs(n) < (1L << 53)) {
                return Long.toString(n);
            }
        }
        double d = value instanceof BigInteger || value instanceof BigDecimal ? new BigDecimal(value.toString())
            .doubleValue() : value.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        if (d == 0) {
            return "0";
        }
        // The shortest digits that read back as this double, and where the point goes. Java (19 on) gives the shortest
        // of at least two digits; where one digit reads back too, JavaScript writes that one (5e-324, not 4.9e-324).
        double abs = Math.abs(d);
        BigDecimal exact = new BigDecimal(Double.toString(abs)).stripTrailingZeros();
        if (exact.precision() == 2) {
            BigDecimal one = new BigDecimal(abs).round(new MathContext(1, RoundingMode.HALF_EVEN));
            if (one.doubleValue() == abs) {
                exact = one.stripTrailingZeros();
            }
        }
        String digits = exact.unscaledValue().toString();
        int k = digits.length();
        int n = k - exact.scale();
        String sign = d < 0 ? "-" : "";
        if (k <= n && n <= 21) {
            return sign + digits + "0".repeat(n - k);
        }
        if (0 < n && n <= 21) {
            return sign + digits.substring(0, n) + "." + digits.substring(n);
        }
        if (-6 < n && n <= 0) {
            return sign + "0." + "0".repeat(-n) + digits;
        }
        String exponent = (n - 1 >= 0 ? "+" : "-") + Math.abs(n - 1);
        return sign + digits.charAt(0) + (k > 1 ? "." + digits.substring(1) : "") + "e" + exponent;
    }
}
