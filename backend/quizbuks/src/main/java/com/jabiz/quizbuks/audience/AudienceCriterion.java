package com.jabiz.quizbuks.audience;

import java.util.List;
import java.util.Objects;

/**
 * One kind of condition a publication may set on the takers' profiles (docs/quizbuks/plans/Q39-audience.md,
 * D-Q39-2). A taker's profile holds, under the same key, the options they chose ({@link Kind#SET}) or one number
 * ({@link Kind#RANGE}); a publication's condition names options (any of them matches) or bounds. A taker who told
 * nothing for the key never matches it: the more the profile tells, the more publications it opens.
 *
 * <p>Adding a criterion is registering one in {@link AudienceCriteria} with its messages; the matching
 * ({@link AudienceMatch}, the templates), the counting and the pages read these definitions and need no change.
 *
 * @param key        the name shared by the profile's rows and the publication's conditions
 * @param kind       options or a range
 * @param options    the options of a {@link Kind#SET} criterion, in the order the pages show them
 * @param multiple   whether a taker may choose several options (interests) or one (gender)
 * @param min        the lowest bound a publication may set ({@link Kind#RANGE})
 * @param max        the highest bound a publication may set ({@link Kind#RANGE})
 * @param measure    how a range reads the profile's number ({@link Kind#RANGE})
 * @param billingKey the item a publication using the criterion would be charged for (Q39c); null for a free one
 */
public record AudienceCriterion(String key, Kind kind, List<String> options, boolean multiple, Integer min, Integer max,
    Measure measure, String billingKey) {

    /** Options to choose among, or a number within bounds. */
    public enum Kind { SET, RANGE }

    /**
     * How a range criterion reads the profile's number: as it is, or as the years since it, so that an age follows
     * the calendar while the profile keeps the birth year. The templates read it from the condition's row.
     */
    public enum Measure { VALUE, YEARS_SINCE }

    public AudienceCriterion {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        options = List.copyOf(options == null ? List.of() : options);
        if (kind == Kind.SET && (options.isEmpty() || min != null || max != null || measure != null)) {
            throw new IllegalArgumentException("An option criterion has options and no bounds: " + key);
        }
        if (kind == Kind.RANGE && (!options.isEmpty() || min == null || max == null || min > max || measure == null)) {
            throw new IllegalArgumentException("A range criterion has bounds and a measure, no options: " + key);
        }
    }

    /** An option criterion: one option per taker, or several. */
    public static AudienceCriterion options(String key, boolean multiple, String billingKey, String... options) {
        return new AudienceCriterion(key, Kind.SET, List.of(options), multiple, null, null, null, billingKey);
    }

    /** A range criterion on a number of the profile, read by {@code measure}. */
    public static AudienceCriterion range(String key, int min, int max, Measure measure, String billingKey) {
        return new AudienceCriterion(key, Kind.RANGE, List.of(), false, min, max, measure, billingKey);
    }

    /** The number a range compares, from the profile's number at {@code year}. */
    public int measured(int profileNumber, int year) {
        return measure == Measure.YEARS_SINCE ? year - profileNumber : profileNumber;
    }
}
