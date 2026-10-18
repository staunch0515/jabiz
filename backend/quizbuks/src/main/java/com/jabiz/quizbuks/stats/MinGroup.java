package com.jabiz.quizbuks.stats;

/**
 * The minimum group of the sponsors' analytics (docs/quizbuks/plans/Q9e-analytics.md, D-Q9e-4, E-4): the figures a
 * person's answers could be read from (each question's rate, the average time, the timeouts) are shown only for at
 * least {@code k} completions, and change only after at least {@code k} new ones, so that two looks a completion apart
 * never tell one person's answers. The parameter {@code qb.stats.min-group} sets {@code k}, never below
 * {@link #FLOOR}; the templates apply the same rule in SQL ({@code greatest(3, …5)}), which the tests check.
 *
 * <p>Unlike the plan's first wording, the recount after a publication ends obeys the same rule: showing the exact final
 * figures whatever their difference to the last shown ones would let the sponsor read the last few completions'
 * answers (fewer than {@code k}) from the two looks. The final figures are shown when they differ by {@code k} or more.
 * The average time is over the attempts submitted in time only, so it changes after {@code k} new ones of those (not
 * of all completions: {@code k} new completions of which one was in time would tell that one's time).
 */
public final class MinGroup {

    /** The lowest {@code k}, whatever the parameter says. */
    public static final int FLOOR = 3;
    /** {@code k} when the parameter is not set or not a whole number. */
    public static final int DEFAULT = 5;

    /** {@code k} for the parameter's value (null or not a whole number of up to 9 digits: the default). */
    public static int of(Object configured) {
        Integer value = null;
        if (configured instanceof Number number) {
            value = number.intValue();
        } else if (configured != null && String.valueOf(configured).matches("[0-9]{1,9}")) {
            value = Integer.parseInt(String.valueOf(configured));
        }
        return Math.max(FLOOR, value == null ? DEFAULT : value);
    }

    /**
     * Whether the totals of {@code analysed} completions replace the ones shown, which were of {@code shown}: only
     * when at least {@code k} completions are new.
     */
    public static boolean publish(long analysed, long shown, int k) {
        return analysed - shown >= Math.max(FLOOR, k);
    }

    /** Whether figures over a group of {@code group} completions may be shown at all. */
    public static boolean show(long group, int k) {
        return group >= Math.max(FLOOR, k);
    }

    private MinGroup() {}
}
