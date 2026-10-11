package com.jabiz.quizbuks.publishing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A draft publication after it is saved or copied, with what the wizard's last step shows (M-30, M-32, M-33;
 * docs/quizbuks/plans/Q4-publishing.md, D-Q4-6): every problem submitting it would be refused for, the full score of
 * its version, the highest reward and the most it can pay out.
 *
 * @param tierIds  the tiers, in their order
 * @param problems empty when the publication can be submitted
 */
public record SaveOutput(UUID publicationId, String status, long revision, Instant editedAt, Integer fullScore,
    BigDecimal maxReward, BigDecimal maxSpend, List<UUID> tierIds, List<Problem> problems) {

    public SaveOutput {
        tierIds = List.copyOf(tierIds);
        problems = List.copyOf(problems);
    }

    /** One problem the publication would be refused for at submission, the message in the caller's language. */
    public record Problem(String field, String code, String message, Map<String, Object> params) {}
}
