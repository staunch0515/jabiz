package com.jabiz.quizbuks.tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a subject has lit, and what a node is to a person (F-S2b1, F-S2d), in plain Java: a node is lit while a lit
 * record of it has no revocation (lighting is permanent in the first launch, A2-12: no validity, no rule version). A
 * node is named by its graph and its id together: ids are unique within a graph only (an operator's copy keeps its
 * template's ids). One definition; the templates that decide who may see or start a publication bound to a node
 * ({@code qb.app.feed}, {@code qb.app.publication}, {@code qb.app.publication-tiers}, {@code qb.app.inbox},
 * {@code qb.system.audience-count}) write the same rule, compared by {@code TreeLightsIT}.
 */
public final class TreeLights {

    private TreeLights() {}

    /**
     * A node of a graph. Written {@code <graph>/<node>} where a node row keeps what must be lit before answering; an
     * entry without the graph (a reference that names no single graph) names no node.
     */
    public record NodeRef(String treeId, String nodeId) {

        /** The written form. */
        public String key() {
            return treeId + "/" + nodeId;
        }

        /** The node an entry names, if it names one. */
        public static Optional<NodeRef> parse(String text) {
            if (text == null) {
                return Optional.empty();
            }
            int slash = text.indexOf('/');
            if (slash <= 0 || slash == text.length() - 1 || text.indexOf('/', slash + 1) >= 0) {
                return Optional.empty();
            }
            return Optional.of(new NodeRef(text.substring(0, slash), text.substring(slash + 1)));
        }
    }

    /** A lit record as the rule sees it. */
    public record Lit(String litId, String treeId, String nodeId) {}

    /**
     * What a node is to a person (the spec's three states): its prerequisites (what must be lit before answering) not
     * all lit; open to be lit (a challenge, under way or not; F-S3 shows finer states of it); or lit.
     */
    public enum State { UNMET, OPEN, LIT }

    /** The nodes lit now: those with a lit record not revoked (revocations name lit records). */
    public static Set<NodeRef> litNow(Collection<Lit> lits, Collection<String> revokedLitIds) {
        Set<String> revoked = Set.copyOf(revokedLitIds);
        Set<NodeRef> lit = new LinkedHashSet<>();
        for (Lit record : lits) {
            if (!revoked.contains(record.litId())) {
                lit.add(new NodeRef(record.treeId(), record.nodeId()));
            }
        }
        return Set.copyOf(lit);
    }

    /**
     * The entries to be lit before answering that are not lit now, in their order; an entry that names no node is
     * never lit (the daily check reports it).
     */
    public static List<String> unmet(List<String> beforeAnswer, Set<NodeRef> litNow) {
        List<String> unmet = new ArrayList<>();
        for (String entry : beforeAnswer) {
            if (!NodeRef.parse(entry).map(litNow::contains).orElse(false)) {
                unmet.add(entry);
            }
        }
        return List.copyOf(unmet);
    }

    /** What the node is to a person with these nodes lit. */
    public static State state(NodeRef node, List<String> beforeAnswer, Set<NodeRef> litNow) {
        if (litNow.contains(node)) {
            return State.LIT;
        }
        return unmet(beforeAnswer, litNow).isEmpty() ? State.OPEN : State.UNMET;
    }

    /**
     * Whether a person sees a publication (F-S2d): one bound to a node whose prerequisites they have not all lit is
     * as if it did not exist, unless they have an attempt of it already (their own result stays theirs). Only the
     * prerequisites count: a revocation does not reach the nodes lit after it (no cascade), and a publication of such
     * a node is hidden again by its own prerequisites.
     *
     * @param beforeAnswer the bound node's prerequisites (null: the publication is bound to no node)
     */
    public static boolean visible(List<String> beforeAnswer, Set<NodeRef> litNow, boolean attempted) {
        return beforeAnswer == null || attempted || unmet(beforeAnswer, litNow).isEmpty();
    }
}
