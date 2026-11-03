package com.jabiz.quizbuks.publishing;

import com.jabiz.quizbuks.certification.Certifications;

import com.jabiz.entity.Violation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a draft's prerequisites must be (Q76, D-Q76-1, D-Q76-4), in one place: at most {@link
 * QbPrerequisites#MAX_PREREQUISITES}, each a publication of the same sponsor that was submitted (not a draft), not the
 * publication itself, none twice, and no cycle through the sponsor's chain — a publication must not come to require
 * itself, however far round. Every problem is reported at once, by the path of the entry ({@code prerequisites[i]}).
 */
public final class PrerequisiteRules {

    /** One prerequisite as asked for. */
    public record Wanted(UUID requiredPublicationId, String certification, String requirement) {
        public Wanted(UUID requiredPublicationId, String requirement) {
            this(requiredPublicationId, null, requirement);
        }
    }

    /** An edge of the sponsor's chain: {@code publicationId} requires {@code requiredPublicationId}. */
    public record Edge(UUID publicationId, UUID requiredPublicationId) {}

    /** A publication of the sponsor's as the check needs it: only whether it is still a draft. */
    public record Target(UUID publicationId, String status) {}

    private PrerequisiteRules() {}

    /**
     * @param publicationId the draft being edited
     * @param wanted        its prerequisites to be
     * @param targets       the sponsor's publications among those wanted, by id (one missing is not the sponsor's)
     * @param edges         every edge of the sponsor's chain (the draft's own are replaced by {@code wanted})
     */
    public static List<Violation> problems(UUID publicationId, List<Wanted> wanted, Map<UUID, Target> targets,
        List<Edge> edges) {
        return problems(publicationId, wanted, targets, edges, Set.of());
    }

    /**
     * As {@link #problems(UUID, List, Map, List)}, with the keys of the sponsor's own active certifications (Q88a,
     * D-Q88-4: a publication may require one of those too, never another sponsor's or a retired one).
     */
    public static List<Violation> problems(UUID publicationId, List<Wanted> wanted, Map<UUID, Target> targets,
        List<Edge> edges, Set<String> ownCertifications) {
        List<Violation> problems = new ArrayList<>();
        if (wanted.size() > QbPrerequisites.MAX_PREREQUISITES) {
            problems.add(new Violation("prerequisites", PublishingCodes.PREREQUISITE_TOO_MANY, "A publication requires "
                + "at most " + QbPrerequisites.MAX_PREREQUISITES + " others",
                Map.of("max", QbPrerequisites.MAX_PREREQUISITES)));
        }
        Set<UUID> seen = new HashSet<>();
        Set<String> certifications = new HashSet<>();
        List<UUID> accepted = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            Wanted w = wanted.get(i);
            String at = "prerequisites[" + i + "]";
            // Q86 (D-Q86-5): a certification instead of a publication: one of the catalog's that takers hold.
            if ((w.requiredPublicationId() == null) == (w.certification() == null)) {
                problems.add(new Violation(at, PublishingCodes.PREREQUISITE_ONE_OF, "A prerequisite names a "
                    + "publication or a certification, one of the two", Map.of()));
                continue;
            }
            if (w.certification() != null) {
                if (!Certifications.forTakers(w.certification()) && !ownCertifications.contains(w.certification())) {
                    problems.add(new Violation(at, PublishingCodes.PREREQUISITE_CERTIFICATION, "Not a certification "
                        + "takers can hold", Map.of("certification", w.certification())));
                } else if (!certifications.add(w.certification())) {
                    problems.add(new Violation(at, PublishingCodes.PREREQUISITE_REPEATED, "The same certification is "
                        + "required twice", Map.of("certification", w.certification())));
                }
                continue;
            }
            if (!seen.add(w.requiredPublicationId())) {
                problems.add(new Violation(at, PublishingCodes.PREREQUISITE_REPEATED, "The same publication is "
                    + "required twice", Map.of("publicationId", w.requiredPublicationId().toString())));
                continue;
            }
            if (w.requiredPublicationId().equals(publicationId)) {
                problems.add(new Violation(at, PublishingCodes.PREREQUISITE_SELF, "A publication cannot require "
                    + "itself", Map.of()));
                continue;
            }
            Target target = targets.get(w.requiredPublicationId());
            if (target == null) {
                problems.add(new Violation(at, PublishingCodes.PREREQUISITE_NOT_OWN, "Only the sponsor's own "
                    + "publications can be required", Map.of("publicationId", w.requiredPublicationId().toString())));
                continue;
            }
            if (PublicationStatus.DRAFT.equals(target.status())) {
                problems.add(new Violation(at, PublishingCodes.PREREQUISITE_DRAFT, "A draft cannot be required: submit "
                    + "it first", Map.of("publicationId", w.requiredPublicationId().toString())));
                continue;
            }
            accepted.add(w.requiredPublicationId());
        }
        // The chain as it would be: the sponsor's edges without the draft's own, plus the wanted ones. From each
        // wanted publication, following what it requires, the draft must not come up (D-Q76-4).
        Map<UUID, List<UUID>> requires = new HashMap<>();
        for (Edge edge : edges) {
            if (!publicationId.equals(edge.publicationId())) {
                requires.computeIfAbsent(edge.publicationId(), k -> new ArrayList<>()).add(edge.requiredPublicationId());
            }
        }
        for (int i = 0; i < wanted.size(); i++) {
            UUID start = wanted.get(i).requiredPublicationId();
            if (start == null || !accepted.contains(start)) {
                continue;
            }
            Reach reach = reaches(requires, start, publicationId);
            if (reach == Reach.CYCLE) {
                problems.add(new Violation("prerequisites[" + i + "]", PublishingCodes.PREREQUISITE_CYCLE,
                    "Requiring this publication would make a cycle: it requires this one, however far round",
                    Map.of("publicationId", start.toString())));
            } else if (reach == Reach.TOO_DEEP) {
                problems.add(new Violation("prerequisites[" + i + "]", PublishingCodes.PREREQUISITE_TOO_DEEP,
                    "The chain of prerequisites is longer than " + QbPrerequisites.MAX_DEPTH,
                    Map.of("max", QbPrerequisites.MAX_DEPTH)));
            }
        }
        return List.copyOf(problems);
    }

    enum Reach { CLEAR, CYCLE, TOO_DEEP }

    /** Walks what {@code start} requires, breadth first; whether {@code target} is reached within the depth limit. */
    static Reach reaches(Map<UUID, List<UUID>> requires, UUID start, UUID target) {
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> frontier = new ArrayDeque<>(List.of(start));
        for (int depth = 0; !frontier.isEmpty(); depth++) {
            if (depth > QbPrerequisites.MAX_DEPTH) {
                return Reach.TOO_DEEP;
            }
            Deque<UUID> next = new ArrayDeque<>();
            for (UUID node : frontier) {
                if (node.equals(target)) {
                    return Reach.CYCLE;
                }
                if (visited.add(node)) {
                    next.addAll(requires.getOrDefault(node, List.of()));
                }
            }
            frontier = next;
        }
        return Reach.CLEAR;
    }
}
