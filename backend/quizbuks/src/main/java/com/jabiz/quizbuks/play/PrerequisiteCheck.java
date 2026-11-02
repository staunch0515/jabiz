package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.publishing.QbPrerequisites;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Whether a taker meets a publication's prerequisites (Q76, D-Q76-2): a prerequisite is met by a finished attempt of
 * the required publication ({@code SUBMITTED} or {@code EXPIRED}), and {@code PASSED} needs that attempt passed too;
 * no expiry, a required publication ended since still counts. All of them must be met. The templates
 * {@code qb.app.feed}, {@code qb.app.publication} and {@code qb.system.audience-count} write the same rule
 * ({@code PrerequisiteIT} compares them). Pure Java.
 */
public final class PrerequisiteCheck {

    /** A prerequisite: the publication required and {@code PASSED} or {@code ANSWERED}. */
    public record Prerequisite(String requiredPublicationId, String requirement) {}

    /** One of the taker's attempts: its publication, its status and whether it passed. */
    public record Attempt(String publicationId, String status, Boolean passed) {}

    /** The required publications not met, in the prerequisites' order; empty when all are. */
    public static List<String> unmet(List<Prerequisite> prerequisites, List<Attempt> attempts) {
        List<String> unmet = new ArrayList<>();
        for (Prerequisite prerequisite : prerequisites) {
            boolean met = attempts.stream().anyMatch(attempt ->
                Objects.equals(attempt.publicationId(), prerequisite.requiredPublicationId())
                    && AttemptStatus.finished(attempt.status())
                    && (QbPrerequisites.ANSWERED.equals(prerequisite.requirement())
                        || Boolean.TRUE.equals(attempt.passed())));
            if (!met) {
                unmet.add(prerequisite.requiredPublicationId());
            }
        }
        return unmet;
    }

    private PrerequisiteCheck() {}
}
