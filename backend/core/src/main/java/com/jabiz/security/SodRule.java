package com.jabiz.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A segregation-of-duties rule (docs/design/18-numbering-approvals-tasks.md section 4): nobody may hold a permission
 * of {@code left} and one of {@code right} at the same time (for example: preparing journal entries and approving
 * them). The {@code *} permission holds every permission; it is not checked where the rule is enforced, but is
 * always reported ({@link #coveredByAll}).
 *
 * @param ruleCode the rule's code
 * @param left     one group of permissions
 * @param right    the other group, disjoint from {@code left}
 */
public record SodRule(String ruleCode, Set<String> left, Set<String> right) {

    /** Permission codes a rule may name. */
    public static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9._:-]{1,200}");

    /** Most permissions of one group. */
    public static final int MAX_PERMISSIONS = 50;

    public SodRule {
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        left = Set.copyOf(left);
        right = Set.copyOf(right);
        List<String> problems = new ArrayList<>(problems(left, "left"));
        problems.addAll(problems(right, "right"));
        Set<String> both = new TreeSet<>(left);
        both.retainAll(right);
        if (!both.isEmpty()) {
            problems.add("permissions in both groups: " + String.join(", ", both));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("SoD rule " + ruleCode + ": " + String.join("; ", problems));
        }
    }

    /** Builds the rule from its stored form: each group a comma-separated list of permission codes. */
    public static SodRule of(String ruleCode, String left, String right) {
        return new SodRule(ruleCode, split(left), split(right));
    }

    /** Whether {@code permissions} (literally, ignoring {@code *}) include one of each group. */
    public boolean violatedBy(Collection<String> permissions) {
        return permissions.stream().anyMatch(left::contains) && permissions.stream().anyMatch(right::contains);
    }

    /** Whether {@code permissions} include {@code *}, and with it both groups. */
    public static boolean coveredByAll(Collection<String> permissions) {
        return permissions.contains("*");
    }

    /** Whether {@code permission} belongs to either group. */
    public boolean involves(String permission) {
        return left.contains(permission) || right.contains(permission);
    }

    /** Groups in their stored form: the codes sorted and comma-separated. */
    public static String join(Set<String> permissions) {
        return String.join(",", new TreeSet<>(permissions));
    }

    private static Set<String> split(String text) {
        if (text == null) {
            return Set.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        Arrays.stream(text.split(",")).map(String::strip).filter(code -> !code.isEmpty()).forEach(codes::add);
        return codes;
    }

    private static List<String> problems(Set<String> group, String name) {
        List<String> problems = new ArrayList<>();
        if (group.isEmpty()) {
            problems.add(name + " names no permission");
        }
        if (group.size() > MAX_PERMISSIONS) {
            problems.add(name + " names more than " + MAX_PERMISSIONS + " permissions");
        }
        group.stream().filter(code -> !PERMISSION.matcher(code).matches()).sorted()
            .forEach(code -> problems.add(name + ": '" + code + "' is not a permission code"));
        return problems;
    }
}
