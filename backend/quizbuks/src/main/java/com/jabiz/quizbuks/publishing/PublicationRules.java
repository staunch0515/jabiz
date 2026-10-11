package com.jabiz.quizbuks.publishing;

import java.time.Instant;

import static com.jabiz.quizbuks.publishing.PublicationStatus.DRAFT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.ENDED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.LIVE;
import static com.jabiz.quizbuks.publishing.PublicationStatus.PENDING_REVIEW;
import static com.jabiz.quizbuks.publishing.PublicationStatus.RUNNING;
import static com.jabiz.quizbuks.publishing.PublicationStatus.SCHEDULED;

/**
 * The time rules of a publication (docs/quizbuks/plans/Q4-publishing.md, D-Q4-1, D-Q4-7), in one place. The state
 * says what people and money decided; the times say the rest. A publication takes new attempts only while it is
 * {@code LIVE} and {@code startAt <= now < endAt}: once its end has come it takes none, even before the minute's tick
 * marks it {@code ENDED}. Q5 starts attempts under the publication's lock with {@link #acceptsNewAttempts}, and the
 * feed template writes the same condition in SQL.
 */
public final class PublicationRules {

    /** Whether the publication takes a new attempt at {@code now}. */
    public static boolean acceptsNewAttempts(String status, Instant startAt, Instant endAt, Instant now) {
        return LIVE.equals(status) && startAt != null && endAt != null && !now.isBefore(startAt)
            && now.isBefore(endAt);
    }

    /**
     * The state the minute's tick moves the publication to at {@code now}: a running one ends at its end time, a
     * scheduled one goes live at its start time, one still in review at its end goes back to its draft; any other
     * stays as it is (the state itself is returned).
     */
    public static String next(String status, Instant startAt, Instant endAt, Instant now) {
        // Never reviewed before its end: nothing to approve any more (the tick withdraws the review).
        if (PENDING_REVIEW.equals(status) && endAt != null && !now.isBefore(endAt)) {
            return DRAFT;
        }
        if (RUNNING.contains(status) && endAt != null && !now.isBefore(endAt)) {
            return ENDED;
        }
        if (SCHEDULED.equals(status) && startAt != null && !now.isBefore(startAt)) {
            return LIVE;
        }
        return status;
    }

    /**
     * When the tick must next look at a publication in {@code status}: a scheduled one at its start, one running or in
     * review at its end; never (null) a draft or an ended one.
     */
    public static Instant nextDue(String status, Instant startAt, Instant endAt) {
        if (SCHEDULED.equals(status)) {
            return startAt;
        }
        return RUNNING.contains(status) || PENDING_REVIEW.equals(status) ? endAt : null;
    }

    /**
     * Where a publication goes when it is let run at {@code now}, after an approval or a pause: before its start it
     * waits ({@code SCHEDULED}), within its time it is {@code LIVE}, after its end it is {@code ENDED}.
     */
    public static String resumeTo(Instant startAt, Instant endAt, Instant now) {
        if (!now.isBefore(endAt)) {
            return ENDED;
        }
        return now.isBefore(startAt) ? SCHEDULED : LIVE;
    }

    private PublicationRules() {}
}
