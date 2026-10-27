package com.jabiz.quizbuks.audience;

/**
 * What a sponsor is told of how many takers match their conditions (docs/quizbuks/plans/Q39-audience.md, D-Q39-5):
 * never a small number, which narrowing the conditions one at a time could tie to a person; under
 * {@link #THRESHOLD} only that it is fewer, otherwise the count rounded down to {@link #STEP}.
 *
 * @param count     the rounded count; null when fewer than the threshold
 * @param fewerThan the threshold when the count is below it; null otherwise
 */
public record AudienceCount(Long count, Integer fewerThan) {

    public static final int THRESHOLD = 20;
    public static final int STEP = 10;

    public static AudienceCount of(long exact) {
        if (exact < THRESHOLD) {
            return new AudienceCount(null, THRESHOLD);
        }
        return new AudienceCount(exact - exact % STEP, null);
    }
}
