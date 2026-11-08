package com.jabiz.quizbuks.tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a subject has lit (F-S2b1), in plain Java: a node is lit while a lit record of it has no revocation (lighting
 * is permanent in the first launch, A2-12: no validity, no rule version). One definition; the templates that decide who
 * may start a publication bound to a node ({@code qb.app.feed}, {@code qb.app.publication},
 * {@code qb.system.audience-count}) write the same rule, compared by {@code TreeLightsIT}.
 */
public final class TreeLights {

    private TreeLights() {}

    /** A lit record as the rule sees it. */
    public record Lit(String litId, String nodeId) {}

    /** The nodes lit now: those with a lit record not revoked (revocations name lit records). */
    public static Set<String> litNow(Collection<Lit> lits, Collection<String> revokedLitIds) {
        Set<String> revoked = Set.copyOf(revokedLitIds);
        Set<String> lit = new LinkedHashSet<>();
        for (Lit record : lits) {
            if (!revoked.contains(record.litId())) {
                lit.add(record.nodeId());
            }
        }
        return Set.copyOf(lit);
    }

    /** The nodes to be lit before answering that are not lit now, in their order. */
    public static List<String> unmet(List<String> beforeAnswer, Set<String> litNow) {
        List<String> unmet = new ArrayList<>();
        for (String node : beforeAnswer) {
            if (!litNow.contains(node)) {
                unmet.add(node);
            }
        }
        return List.copyOf(unmet);
    }
}
