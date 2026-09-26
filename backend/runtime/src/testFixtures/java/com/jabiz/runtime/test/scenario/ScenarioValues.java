package com.jabiz.runtime.test.scenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Variables and value comparison of scenarios (docs/design/07-quality.md section 3.1).
 * <ul>
 *   <li>{@code ${name}} refers to a saved variable: a string that is exactly one reference takes the variable's value
 *       and type, a reference inside a longer string is replaced by the value's text. An unknown name is an
 *       error.</li>
 *   <li>{@code $.a.b[0]} is a path into a process output (maps and lists).</li>
 *   <li>Expected values match actual ones by meaning: numbers by value ({@code 0.1} matches {@code 0.1000}),
 *       instants by the time they denote whatever the offset, maps by containment (the actual map may have more
 *       keys), lists element by element.</li>
 * </ul>
 */
public final class ScenarioValues {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
    private static final Pattern SEGMENT = Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)]");

    private ScenarioValues() {}

    /** {@code raw} with every variable reference resolved, recursively through maps and lists. */
    public static Object resolve(Object raw, Map<String, Object> variables) {
        if (raw instanceof String text) {
            Matcher whole = REFERENCE.matcher(text);
            if (whole.matches()) {
                return variable(whole.group(1), variables);
            }
            Matcher matcher = REFERENCE.matcher(text);
            StringBuilder resolved = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(resolved,
                    Matcher.quoteReplacement(String.valueOf(variable(matcher.group(1), variables))));
            }
            matcher.appendTail(resolved);
            return resolved.toString();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, value) -> resolved.put(String.valueOf(key), resolve(value, variables)));
            return resolved;
        }
        if (raw instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            list.forEach(value -> resolved.add(resolve(value, variables)));
            return resolved;
        }
        return raw;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolveMap(Map<String, Object> raw, Map<String, Object> variables) {
        return (Map<String, Object>) resolve(raw, variables);
    }

    private static Object variable(String name, Map<String, Object> variables) {
        if (!variables.containsKey(name)) {
            throw new IllegalArgumentException("unknown variable ${" + name + "}; saved so far: "
                + variables.keySet());
        }
        return variables.get(name);
    }

    /**
     * The value at {@code path} ({@code $}, {@code $.a}, {@code $.a[0].b}) in {@code root}.
     *
     * @throws IllegalArgumentException when the path is malformed or leads nowhere
     */
    public static Object extract(Object root, String path) {
        if (!path.startsWith("$")) {
            throw new IllegalArgumentException("a path starts with $: " + path);
        }
        Object current = root;
        String rest = path.substring(1);
        if (rest.startsWith(".")) {
            rest = rest.substring(1);
        }
        Matcher matcher = SEGMENT.matcher(rest);
        int consumed = 0;
        while (matcher.find()) {
            String between = rest.substring(consumed, matcher.start());
            if (!between.isEmpty() && !between.equals(".")) {
                throw new IllegalArgumentException("malformed path " + path);
            }
            consumed = matcher.end();
            if (matcher.group(1) != null) {
                if (!(current instanceof Map<?, ?> map) || !map.containsKey(matcher.group(1))) {
                    throw new IllegalArgumentException("path " + path + " leads nowhere at '" + matcher.group(1) + "'");
                }
                current = map.get(matcher.group(1));
            } else {
                int index = Integer.parseInt(matcher.group(2));
                if (!(current instanceof List<?> list) || index >= list.size()) {
                    throw new IllegalArgumentException("path " + path + " leads nowhere at [" + index + "]");
                }
                current = list.get(index);
            }
        }
        if (consumed != rest.length()) {
            throw new IllegalArgumentException("malformed path " + path);
        }
        return current;
    }

    /** Whether {@code actual} matches {@code expected} (see the class comment). */
    public static boolean matches(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected instanceof Map<?, ?> wanted) {
            if (!(actual instanceof Map<?, ?> found)) {
                return false;
            }
            for (Map.Entry<?, ?> entry : wanted.entrySet()) {
                if (!found.containsKey(entry.getKey()) || !matches(entry.getValue(), found.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof List<?> wanted) {
            if (!(actual instanceof List<?> found) || wanted.size() != found.size()) {
                return false;
            }
            for (int i = 0; i < wanted.size(); i++) {
                if (!matches(wanted.get(i), found.get(i))) {
                    return false;
                }
            }
            return true;
        }
        BigDecimal expectedNumber = number(expected);
        BigDecimal actualNumber = number(actual);
        if (expectedNumber != null && actualNumber != null) {
            return expectedNumber.compareTo(actualNumber) == 0;
        }
        Instant expectedTime = instant(expected);
        Instant actualTime = instant(actual);
        if (expectedTime != null && actualTime != null) {
            return expectedTime.equals(actualTime);
        }
        return String.valueOf(expected).equals(String.valueOf(actual));
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && text.matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(text);
        }
        return null;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime time) {
            return time.toInstant();
        }
        if (value instanceof String text && text.length() >= 20 && Character.isDigit(text.charAt(0))) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
