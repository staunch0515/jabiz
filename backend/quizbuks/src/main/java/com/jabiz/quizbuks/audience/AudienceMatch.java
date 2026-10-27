package com.jabiz.quizbuks.audience;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether a taker's profile matches a publication's conditions (docs/quizbuks/plans/Q39-audience.md, D-Q39-2,
 * D-Q39-8): every criterion the publication sets holds; an option criterion holds when the profile has any of the
 * options, a range when the profile's number, read by the criterion's measure at {@code year}, is within the bounds.
 * A criterion the profile tells nothing of does not hold. No condition at all matches everybody.
 *
 * <p>This is the one definition of matching: the start checks it here, and the templates that list and count
 * ({@code qb.app.feed}, {@code qb.app.publication}, {@code qb.system.audience-count}) write the same thing in SQL,
 * compared with it by {@code AudienceIT}. A dedicated matching server would replace those, not this meaning.
 */
public final class AudienceMatch {

    /** One stored row of a publication's conditions: an option, or a range. */
    public record ConditionRow(String key, String option, Integer min, Integer max) {}

    /** One stored row of a profile: an option, or a number. */
    public record ProfileRow(String key, String option, Integer number) {}

    public static boolean matches(Collection<ConditionRow> conditions, Collection<ProfileRow> profile, int year) {
        Map<String, List<ConditionRow>> byKey = new LinkedHashMap<>();
        for (ConditionRow row : conditions) {
            byKey.computeIfAbsent(row.key(), k -> new java.util.ArrayList<>()).add(row);
        }
        Map<String, Set<String>> options = new LinkedHashMap<>();
        Map<String, Integer> numbers = new LinkedHashMap<>();
        for (ProfileRow row : profile) {
            if (row.option() != null) {
                options.computeIfAbsent(row.key(), k -> new LinkedHashSet<>()).add(row.option());
            } else if (row.number() != null) {
                numbers.put(row.key(), row.number());
            }
        }
        for (Map.Entry<String, List<ConditionRow>> criterion : byKey.entrySet()) {
            if (!holds(criterion.getKey(), criterion.getValue(), options.getOrDefault(criterion.getKey(), Set.of()),
                numbers.get(criterion.getKey()), year)) {
                return false;
            }
        }
        return true;
    }

    private static boolean holds(String key, List<ConditionRow> rows, Set<String> told, Integer number, int year) {
        AudienceCriterion criterion = AudienceCriteria.find(key).orElse(null);
        for (ConditionRow row : rows) {
            if (row.option() != null && told.contains(row.option())) {
                return true;
            }
            if (row.min() != null && row.max() != null && number != null) {
                int measured = criterion == null ? number : criterion.measured(number, year);
                if (row.min() <= measured && measured <= row.max()) {
                    return true;
                }
            }
        }
        return false;
    }

    private AudienceMatch() {}
}
