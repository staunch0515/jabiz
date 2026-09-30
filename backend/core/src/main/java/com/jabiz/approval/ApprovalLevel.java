package com.jabiz.approval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One level of an approval (docs/design/18-numbering-approvals-tasks.md section 3.2): who may approve it and,
 * optionally, which number fact the approver's limit must cover.
 *
 * @param permission the permission an approver of this level holds
 * @param limitFact  a {@link FactType#NUMBER} fact of the subject; when given, the approver needs an approval limit for
 *                   the subject of at least its value. Null: no limit applies
 */
public record ApprovalLevel(String permission, String limitFact) {

    /** Most levels of one rule. */
    public static final int MAX_LEVELS = 5;

    /** Permission codes a level may name ({@code *} is not one: it would let anybody's administrator approve). */
    public static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9._:-]{1,200}");

    public ApprovalLevel {
        if (permission == null || !PERMISSION.matcher(permission).matches()) {
            throw new IllegalArgumentException("Level permission '" + permission + "' must match "
                + PERMISSION.pattern());
        }
    }

    /**
     * Parses the JSON form, a list of {@code {"permission": "...", "limitFact": "..."}}, against the subject. No level
     * at all is allowed: a matching rule without levels states that no approval is needed. Fails listing every
     * problem.
     */
    public static List<ApprovalLevel> parse(Object tree, ApprovalSubject subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        if (tree == null) {
            return List.of();
        }
        if (!(tree instanceof List<?> list)) {
            throw new IllegalArgumentException("levels must be a list");
        }
        List<String> problems = new ArrayList<>();
        if (list.size() > MAX_LEVELS) {
            problems.add("at most " + MAX_LEVELS + " levels");
        }
        List<ApprovalLevel> levels = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String at = "levels[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                problems.add(at + " must be an object");
                continue;
            }
            for (Object key : map.keySet()) {
                if (!List.of("permission", "limitFact").contains(key)) {
                    problems.add(at + ": unknown key '" + key + "'");
                }
            }
            Object permission = map.get("permission");
            if (!(permission instanceof String code) || !PERMISSION.matcher(code).matches()) {
                problems.add(at + ": permission '" + permission + "' must match " + PERMISSION.pattern());
                continue;
            }
            Object limitFact = map.get("limitFact");
            if (limitFact != null && subject.facts().get(String.valueOf(limitFact)) != FactType.NUMBER) {
                problems.add(at + ": limitFact '" + limitFact + "' is not a NUMBER fact of approval subject "
                    + subject.name());
                continue;
            }
            levels.add(new ApprovalLevel(code, limitFact == null ? null : String.valueOf(limitFact)));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return List.copyOf(levels);
    }
}
