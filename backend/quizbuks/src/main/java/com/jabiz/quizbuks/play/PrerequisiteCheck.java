package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.publishing.QbPrerequisites;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a taker meets a publication's prerequisites (Q76, D-Q76-2): for each one, a finished attempt of theirs on
 * the required publication — {@code SUBMITTED} or {@code EXPIRED}, in progress does not count — and, for {@code
 * PASSED}, a pass. Learnt once is learnt: nothing expires, and the required publication may have ended. The one
 * definition; the templates {@code qb.app.feed}, {@code qb.app.publication} and {@code qb.system.audience-count} write
 * the same in SQL ({@code PrerequisiteIT} compares them).
 */
public final class PrerequisiteCheck {

    /** A required publication with its requirement, or (Q86) a certification to hold ({@code requiredPublicationId} null). */
    public record Prerequisite(UUID requiredPublicationId, String certification, String requirement) {
        public Prerequisite(UUID requiredPublicationId, String requirement) {
            this(requiredPublicationId, null, requirement);
        }
    }

    /** The taker's attempt as the check reads it. */
    public record Attempt(UUID publicationId, String status, Boolean passed) {}

    private PrerequisiteCheck() {}

    /** The prerequisites not met, in the given order; a certification counts as unmet (none held is assumed). */
    public static List<Prerequisite> unmet(List<Prerequisite> prerequisites, List<Attempt> attempts) {
        return unmet(prerequisites, attempts, Set.of());
    }

    /**
     * The prerequisites not met, in the given order; empty when every one is (or there are none).
     *
     * @param held the keys of the certifications the taker holds now (Q86, D-Q86-5: CertificationHolding's rule)
     */
    public static List<Prerequisite> unmet(List<Prerequisite> prerequisites, List<Attempt> attempts, Set<String> held) {
        Map<UUID, Attempt> finished = new HashMap<>();
        for (Attempt attempt : attempts) {
            if (attempt.publicationId() != null && AttemptStatus.finished(attempt.status())) {
                finished.put(attempt.publicationId(), attempt);
            }
        }
        List<Prerequisite> unmet = new ArrayList<>();
        for (Prerequisite prerequisite : prerequisites) {
            boolean met;
            if (prerequisite.certification() != null) {
                met = held.contains(prerequisite.certification());
            } else {
                Attempt attempt = finished.get(prerequisite.requiredPublicationId());
                met = attempt != null && (QbPrerequisites.ANSWERED.equals(prerequisite.requirement())
                    || Boolean.TRUE.equals(attempt.passed()));
            }
            if (!met) {
                unmet.add(prerequisite);
            }
        }
        return List.copyOf(unmet);
    }
}
