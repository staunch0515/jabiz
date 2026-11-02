package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.publishing.QbPrerequisites;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a taker meets a publication's prerequisites (Q76, D-Q76-2): for each one, a finished attempt of theirs on
 * the required publication — {@code SUBMITTED} or {@code EXPIRED}, in progress does not count — and, for {@code
 * PASSED}, a pass. Learnt once is learnt: nothing expires, and the required publication may have ended. The one
 * definition; the templates {@code qb.app.feed}, {@code qb.app.publication} and {@code qb.system.audience-count} write
 * the same in SQL ({@code PrerequisiteIT} compares them).
 */
public final class PrerequisiteCheck {

    public record Prerequisite(UUID requiredPublicationId, String requirement) {}

    /** The taker's attempt as the check reads it. */
    public record Attempt(UUID publicationId, String status, Boolean passed) {}

    private PrerequisiteCheck() {}

    /** The prerequisites not met, in the given order; empty when every one is (or there are none). */
    public static List<Prerequisite> unmet(List<Prerequisite> prerequisites, List<Attempt> attempts) {
        Map<UUID, Attempt> finished = new HashMap<>();
        for (Attempt attempt : attempts) {
            if (attempt.publicationId() != null && AttemptStatus.finished(attempt.status())) {
                finished.put(attempt.publicationId(), attempt);
            }
        }
        List<Prerequisite> unmet = new ArrayList<>();
        for (Prerequisite prerequisite : prerequisites) {
            Attempt attempt = finished.get(prerequisite.requiredPublicationId());
            boolean met = attempt != null
                && (QbPrerequisites.ANSWERED.equals(prerequisite.requirement()) || Boolean.TRUE.equals(attempt.passed()));
            if (!met) {
                unmet.add(prerequisite);
            }
        }
        return List.copyOf(unmet);
    }
}
