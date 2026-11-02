package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a draft's prerequisites are refused for (Q76, D-Q76-4), every problem at once: more than
 * {@link QbPrerequisites#MAX_PREREQUISITES}, the publication itself, one given twice, one not the sponsor's, a draft,
 * one whose chain leads back to the publication (a cycle) or is longer than {@link QbPrerequisites#MAX_DEPTH}. Pure
 * Java.
 */
public final class PrerequisiteRules {

    /** One prerequisite as asked: the required publication and {@code PASSED} or {@code ANSWERED}. */
    public record Wanted(UUID publicationId, String requirement) {}

    /**
     * @param publicationId the draft whose prerequisites are saved
     * @param statuses      the sponsor's publications among the wanted ones, by id, with their status
     * @param chain         every other publication of the sponsor's prerequisites (publication to required ones);
     *                      the draft's own are replaced by {@code wanted}
     */
    public static List<Violation> problems(UUID publicationId, List<Wanted> wanted, Map<UUID, String> statuses,
        Map<UUID, List<UUID>> chain) {
        List<Violation> problems = new ArrayList<>();
        if (wanted.size() > QbPrerequisites.MAX_PREREQUISITES) {
            problems.add(new Violation("prerequisites", PublishingCodes.PREREQUISITE_TOO_MANY, "At most "
                + QbPrerequisites.MAX_PREREQUISITES + " prerequisites", Map.of("max", QbPrerequisites.MAX_PREREQUISITES)));
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < wanted.size(); i++) {
            UUID required = wanted.get(i).publicationId();
            String at = "prerequisites[" + i + "].publicationId";
            if (required.equals(publicationId)) {
                problems.add(new Violation(at, PublishingCodes.PREREQUISITE_SELF, "A publication is not its own "
                    + "prerequisite"));
            } else if (!seen.add(required)) {
                problems.add(new Violation(at, PublishingCodes.PREREQUISITE_REPEATED, "Given twice: " + required));
            } else if (!statuses.containsKey(required)) {
                problems.add(new Violation(at, PublishingCodes.PREREQUISITE_NOT_OWN, "Not one of the sponsor's "
                    + "publications: " + required));
            } else if (PublicationStatus.DRAFT.equals(statuses.get(required))) {
                problems.add(new Violation(at, PublishingCodes.PREREQUISITE_DRAFT, "A draft is no prerequisite: "
                    + required));
            } else {
                int depth = depth(required, publicationId, chain, new HashSet<>());
                if (depth < 0) {
                    problems.add(new Violation(at, PublishingCodes.PREREQUISITE_CYCLE, "Its prerequisites lead back "
                        + "to this publication"));
                } else if (depth + 1 > QbPrerequisites.MAX_DEPTH) {
                    problems.add(new Violation(at, PublishingCodes.PREREQUISITE_TOO_DEEP, "The chain of prerequisites "
                        + "would be longer than " + QbPrerequisites.MAX_DEPTH, Map.of("max", QbPrerequisites.MAX_DEPTH)));
                }
            }
        }
        return problems;
    }

    /**
     * The length of the longest chain of prerequisites from {@code from} (0 without any), or -1 when one leads to
     * {@code target}. {@code path} holds the publications on the way, so that a cycle elsewhere (one stored before
     * these rules, or through a publication gone back to draft) is not followed round; {@code known} the depths found,
     * so that a publication many others require is walked once.
     */
    static int depth(UUID from, UUID target, Map<UUID, List<UUID>> chain, Set<UUID> path) {
        return depth(from, target, chain, path, new java.util.HashMap<>());
    }

    private static int depth(UUID from, UUID target, Map<UUID, List<UUID>> chain, Set<UUID> path,
        Map<UUID, Integer> known) {
        if (from.equals(target)) {
            return -1;
        }
        if (known.containsKey(from)) {
            return known.get(from);
        }
        if (path.contains(from)) {
            return 0;
        }
        if (path.size() > QbPrerequisites.MAX_DEPTH) {
            return QbPrerequisites.MAX_DEPTH + 1;
        }
        path.add(from);
        int longest = 0;
        for (UUID next : chain.getOrDefault(from, List.of())) {
            int depth = depth(next, target, chain, path, known);
            if (depth < 0) {
                path.remove(from);
                return -1;
            }
            longest = Math.max(longest, depth + 1);
        }
        path.remove(from);
        known.put(from, longest);
        return longest;
    }

    private PrerequisiteRules() {}
}
