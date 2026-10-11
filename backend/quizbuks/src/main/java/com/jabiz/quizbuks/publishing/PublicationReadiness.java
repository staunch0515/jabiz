package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.QuizCompleteness;
import com.jabiz.quizbuks.content.QuizSnapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a draft publication may be submitted (M-31 – M-33; docs/quizbuks/plans/Q4-publishing.md, D-Q4-6). A draft
 * may be incomplete while it is edited; a submitted one may not. Every problem is reported at once, with the path of
 * the field; the content rules of the version (Q3's {@link QuizCompleteness}) are checked again on its snapshot,
 * under the path {@code version.}, so that a version made before a rule became stricter is stopped here.
 */
public final class PublicationReadiness {

    /** The policy of a publication as it stands; any part may be missing in a draft. */
    public record Draft(UUID versionId, String name, String regions, Instant startAt, Instant endAt,
        Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners) {}

    /**
     * @param version the snapshot of the chosen version; null without one
     * @param now     the operation time: a publication whose end has passed cannot be submitted
     */
    public static List<Violation> check(Draft draft, List<TierCoverage.Tier> tiers, QuizSnapshot version,
        Instant now) {
        List<Violation> problems = new ArrayList<>();
        Integer fullScore = version == null ? null : version.fullScore();
        if (draft.versionId() == null || version == null) {
            problems.add(new Violation("versionId", PublishingCodes.NO_VERSION, "Choose a version of a quiz",
                Map.of()));
        }
        if (draft.name() == null || draft.name().isBlank()) {
            problems.add(required("name"));
        }
        if (draft.regions() == null || draft.regions().isBlank()) {
            problems.add(required("regions"));
        }
        if (draft.startAt() == null) {
            problems.add(required("startAt"));
        }
        if (draft.endAt() == null) {
            problems.add(required("endAt"));
        } else if (!draft.endAt().isAfter(now)) {
            problems.add(new Violation("endAt", PublishingCodes.END_PASSED, "The end time has passed",
                Map.of("endAt", draft.endAt().toString())));
        }
        if (draft.startAt() != null && draft.endAt() != null) {
            if (!draft.startAt().isBefore(draft.endAt())) {
                problems.add(new Violation("endAt", PublishingCodes.TIME_ORDER, "The end must come after the start",
                    Map.of()));
            } else if (Duration.between(draft.startAt(), draft.endAt()).compareTo(PublishingLimits.MAX_RUNNING) > 0) {
                problems.add(new Violation("endAt", PublishingCodes.TOO_LONG, "A publication runs at most "
                    + PublishingLimits.MAX_RUNNING.toDays() + " days",
                    Map.of("days", PublishingLimits.MAX_RUNNING.toDays())));
            }
        }
        Integer limit = draft.timeLimitSec();
        if (limit == null) {
            problems.add(required("timeLimitSec"));
        } else if (limit < PublishingLimits.MIN_TIME_LIMIT_SEC || limit > PublishingLimits.MAX_TIME_LIMIT_SEC) {
            problems.add(new Violation("timeLimitSec", PublishingCodes.TIME_LIMIT_RANGE, "The time limit must be "
                + "between " + PublishingLimits.MIN_TIME_LIMIT_SEC + " and " + PublishingLimits.MAX_TIME_LIMIT_SEC
                + " seconds", Map.of("min", PublishingLimits.MIN_TIME_LIMIT_SEC,
                "max", PublishingLimits.MAX_TIME_LIMIT_SEC)));
        }
        if (draft.passScore() == null) {
            problems.add(required("passScore"));
        } else if (draft.passScore() < 0 || (fullScore != null && draft.passScore() > fullScore)) {
            problems.add(new Violation("passScore", PublishingCodes.PASS_SCORE_RANGE, "The pass mark must be between "
                + "0 and the full score " + fullScore, Map.of("fullScore", fullScore == null ? 0 : fullScore)));
        }
        problems.addAll(TierCoverage.check(tiers, fullScore));
        BigDecimal maxReward = TierCoverage.maxReward(tiers);
        if (draft.budgetCap() != null && maxReward != null && draft.budgetCap().compareTo(maxReward) < 0) {
            problems.add(new Violation("budgetCap", PublishingCodes.BUDGET_BELOW_MAX_REWARD, "The budget "
                + draft.budgetCap() + " is less than the highest reward " + maxReward,
                Map.of("budgetCap", draft.budgetCap(), "maxReward", maxReward)));
        }
        if (version != null) {
            QuizCompleteness.check(version).forEach(problem -> problems.add(new Violation(
                problem.field() == null ? "version" : "version." + problem.field(), problem.ruleCode(),
                problem.message(), problem.params())));
        }
        return List.copyOf(problems);
    }

    private static Violation required(String field) {
        return new Violation(field, PublishingCodes.FIELD_REQUIRED, field + " is required", Map.of("field", field));
    }

    private PublicationReadiness() {}
}
