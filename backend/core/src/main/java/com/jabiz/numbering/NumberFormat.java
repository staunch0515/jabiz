package com.jabiz.numbering;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a sequence's numbers are written (docs/design/18-numbering-approvals-tasks.md section 2): literal text with one
 * placeholder for the number, {@code {n}} or zero-padded {@code {n:6}}, and optionally {@code {scope}} for the scope
 * the number was drawn in. {@code JE-{n:4}} gives JE-0001; {@code SO-{scope}-{n:6}} in scope 2026 gives SO-2026-000001.
 * A number longer than its padding is written in full, never cut.
 */
public final class NumberFormat {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern NUMBER = Pattern.compile("n(?::([1-9]|1[0-9]))?");
    /** Literal text: letters, digits and a few separators, so that numbers are safe in files, URLs and reports. */
    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9._/#-]*");

    private sealed interface Part permits Literal, Counter, Scope {}

    private record Literal(String text) implements Part {}

    private record Counter(int width) implements Part {}

    private record Scope() implements Part {}

    private final String pattern;
    private final List<Part> parts;
    private final boolean usesScope;

    private NumberFormat(String pattern, List<Part> parts) {
        this.pattern = pattern;
        this.parts = List.copyOf(parts);
        this.usesScope = parts.stream().anyMatch(Scope.class::isInstance);
    }

    /** Parses a pattern; fails naming every problem. */
    public static NumberFormat parse(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Number format must not be blank");
        }
        List<Part> parts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int counters = 0;
        Matcher m = PLACEHOLDER.matcher(pattern);
        int at = 0;
        while (m.find()) {
            literal(pattern.substring(at, m.start()), parts, problems);
            String inner = m.group(1);
            Matcher number = NUMBER.matcher(inner);
            if (number.matches()) {
                counters++;
                parts.add(new Counter(number.group(1) == null ? 0 : Integer.parseInt(number.group(1))));
            } else if (inner.equals("scope")) {
                parts.add(new Scope());
            } else {
                problems.add("unknown placeholder {" + inner + "} (use {n}, {n:width} or {scope})");
            }
            at = m.end();
        }
        literal(pattern.substring(at), parts, problems);
        if (counters != 1) {
            problems.add("needs exactly one {n} or {n:width}, has " + counters);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Number format '" + pattern + "': " + String.join("; ", problems));
        }
        return new NumberFormat(pattern, parts);
    }

    private static void literal(String text, List<Part> parts, List<String> problems) {
        if (text.isEmpty()) {
            return;
        }
        if (!LITERAL.matcher(text).matches()) {
            problems.add("literal '" + text + "' may only contain letters, digits and . _ / # -");
        }
        parts.add(new Literal(text));
    }

    /** The number {@code value} drawn in {@code scope} (ignored unless the pattern has {@code {scope}}). */
    public String format(long value, String scope) {
        if (value < 1) {
            throw new IllegalArgumentException("Numbers start at 1, was " + value);
        }
        StringBuilder out = new StringBuilder();
        for (Part part : parts) {
            switch (part) {
                case Literal literal -> out.append(literal.text());
                case Counter counter -> {
                    String digits = Long.toString(value);
                    out.append("0".repeat(Math.max(counter.width() - digits.length(), 0))).append(digits);
                }
                case Scope ignored -> out.append(scope == null ? "" : scope);
            }
        }
        return out.toString();
    }

    public boolean usesScope() {
        return usesScope;
    }

    public String pattern() {
        return pattern;
    }

    @Override
    public String toString() {
        return pattern;
    }
}
