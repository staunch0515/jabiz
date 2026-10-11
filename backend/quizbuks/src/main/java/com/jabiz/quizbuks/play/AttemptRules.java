package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.publishing.PublicationRules;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The rules of starting and finishing an attempt, in one place (docs/quizbuks/plans/Q5-answering.md, D-Q5-1,
 * D-Q5-3, D-Q5-5, D-Q5-6). The feed template writes the same conditions in SQL.
 */
public final class AttemptRules {

    /** A publication's counters as the start and the end of an attempt read them. */
    public record Counts(long starts, long completions, long winners, BigDecimal reserved, BigDecimal spent) {

        /** Attempts started and not finished: each holds a winner's place and the highest reward. */
        public long inProgress() {
            return starts - completions;
        }
    }

    /** What a new attempt needs of a publication: its limits and its highest reward. */
    public record Limits(BigDecimal budgetCap, Integer maxWinners, BigDecimal maxReward) {}

    /** When an attempt started at {@code startedAt} must be submitted, the time limit from then on. */
    public static Instant deadline(Instant startedAt, int timeLimitSec) {
        return startedAt.plusSeconds(timeLimitSec);
    }

    /** Whether the time to answer is over: no more saving, and a submission must come within the grace. */
    public static boolean pastDeadline(Instant deadline, Instant now) {
        return now.isAfter(deadline);
    }

    /**
     * Whether a submission comes too late: more than {@link PlayLimits#SUBMIT_GRACE} after the deadline. From then on
     * the attempt is finished with its saved answers instead.
     */
    public static boolean tooLate(Instant deadline, Instant now) {
        return now.isAfter(deadline.plus(PlayLimits.SUBMIT_GRACE));
    }

    /**
     * Why the publication takes no new attempt for want of budget or winners' places, or null when it takes one
     * (D-Q5-3): attempts in progress reserve the highest reward and a winner's place each, so neither limit is ever
     * exceeded however they end.
     */
    public static String limitRefusal(Limits limits, Counts counts) {
        if (limits.budgetCap() != null && counts.spent().add(counts.reserved()).add(limits.maxReward())
            .compareTo(limits.budgetCap()) > 0) {
            return PlayCodes.BUDGET_FULL;
        }
        if (limits.maxWinners() != null && counts.winners() + counts.inProgress() >= limits.maxWinners()) {
            return PlayCodes.QUOTA_FULL;
        }
        return null;
    }

    /**
     * Whether the sponsor can pay the highest reward now (D-Q5-5): nothing pending and the balance covers it. Read
     * without the wallet's lock when an attempt starts, so it is a soft check: a reward that cannot be paid at the end
     * becomes pending.
     */
    public static boolean sponsorPays(BigDecimal balance, long pendingCount, BigDecimal maxReward) {
        return pendingCount == 0 && balance.compareTo(maxReward) >= 0;
    }

    /** The publication as a taker sees it at {@code now}: the facts of every condition of the feed. */
    public record Card(String sponsorId, String status, String regions, Instant startAt, Instant endAt,
        Limits limits) {}

    /**
     * Why the caller cannot start an attempt of the publication, or null when they can: their own publication, closed
     * (not live or not within its time), not aimed at their regions, budget or winners used up, or a sponsor who cannot
     * pay. The first that applies.
     *
     * @param userRegions the regions of the caller's country; {@code GLOBAL} without one
     */
    public static String startRefusal(String userId, String userRegions, Card card, Counts counts,
        BigDecimal sponsorBalance, long sponsorPending, Instant now) {
        if (card.sponsorId().equals(userId)) {
            return PlayCodes.OWN_PUBLICATION;
        }
        if (!PublicationRules.acceptsNewAttempts(card.status(), card.startAt(), card.endAt(), now)) {
            return PlayCodes.NOT_OPEN;
        }
        if (!Regions.overlaps(userRegions, card.regions())) {
            return PlayCodes.NOT_VISIBLE;
        }
        String limit = limitRefusal(card.limits(), counts);
        if (limit != null) {
            return limit;
        }
        if (!sponsorPays(sponsorBalance, sponsorPending, card.limits().maxReward())) {
            return PlayCodes.FUNDS_SHORT;
        }
        return null;
    }

    private AttemptRules() {}
}
