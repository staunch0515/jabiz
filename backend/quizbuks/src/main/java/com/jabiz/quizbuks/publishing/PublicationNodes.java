package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.tree.QbTrees;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The node of a knowledge graph a publication is bound to (F-S2b1, docs/quizbuks/plans/impl/F-S2.md §1): a pass of
 * the publication lights the node, and the node's conditions before answering say who may start it. The save, the
 * copy and the submission read the node, its graph and whether the publisher is an operator's direct Sponsor in one
 * statement (none for a publication bound to no node), and report the same problems; the card freezes the binding.
 *
 * <p>A publication may be bound to a node in effect whose condition asks for a pass, of the publisher's own graph,
 * or, for an operator's direct Sponsor (the operator's official publisher, F-S1), of a platform graph Takers light or
 * (F-S4a, docs/quizbuks/plans/impl/F-S4.md §2) of the operator's own copy, which Sponsors light: such a publication is a
 * challenge the operator's Sponsors answer in the Sponsor app ({@link #forSponsors}). A platform template Sponsors light
 * is answered only through an operator's copy (the controller, 2026-11-10).
 */
public final class PublicationNodes {

    static final String NODE = "publication.treeNode";
    static final String TREE = "publication.tree";
    static final String DIRECT = "publication.directSponsor";

    private PublicationNodes() {}

    /** The graph and node named, or null when none is (both are needed: node ids are unique within a graph). */
    public record Binding(String treeId, String nodeId) {}

    static Binding binding(String treeId, String nodeId) {
        return treeId == null || treeId.isBlank() || nodeId == null || nodeId.isBlank() ? null
            : new Binding(treeId.strip(), nodeId.strip());
    }

    static Binding binding(EntityInstance publication) {
        return publication == null ? null : binding((String) publication.get("treeId"),
            (String) publication.get("treeNodeId"));
    }

    /** The node, its graph, and the operators whose direct Sponsor the caller is; nothing for no binding. */
    static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Binding> binding) {
        pb.step("Load the node bound to", QueryEntities.<ProcessContext>all(
            QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE), ctx -> {
                Binding b = binding.apply(ctx);
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("treeId", b == null ? List.of() : List.of(b.treeId())),
                    new QueryPredicate.Eq("nodeId", b == null ? "" : b.nodeId())))).limit(1).build();
            }, NODE),
            QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> {
                Binding b = binding.apply(ctx);
                return EntityQuery.builder().where(new QueryPredicate.In("treeId",
                    b == null ? List.of() : List.of(b.treeId()))).limit(1).build();
            }, TREE),
            QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR), ctx -> {
                Binding b = binding.apply(ctx);
                return EntityQuery.builder().where(new QueryPredicate.In("directSponsorId",
                    b == null ? List.of() : List.of(String.valueOf((Object) ctx.request().actorId())))).limit(1)
                    .build();
            }, DIRECT)));
    }

    /**
     * Why the publication of {@code publisherId} cannot be bound to the node (none when bound to none): not a node in
     * effect of a graph in use the publisher may bind to; no pass asked for; or Sponsors light it.
     */
    static List<Violation> problems(ProcessContext ctx, Object publisherId, String treeId, String nodeId) {
        if (binding(treeId, nodeId) == null) {
            return List.of();
        }
        EntityInstance node = first(ctx, NODE);
        EntityInstance tree = first(ctx, TREE);
        List<Violation> problems = new ArrayList<>();
        Map<String, Object> params = Map.of("treeId", treeId, "treeNodeId", nodeId);
        if (node == null || tree == null || !Boolean.TRUE.equals(node.get("active"))
            || !QbTrees.ACTIVE.equals(tree.get("status")) || !mayBind(tree, publisherId, first(ctx, DIRECT))) {
            problems.add(new Violation("treeNodeId", PublishingCodes.NODE_UNKNOWN, "No node in effect of a "
                + "knowledge graph this publication may be bound to", params));
            return problems;
        }
        if (QbTrees.PLATFORM.equals(tree.get("ownerKind")) && QbTrees.SPONSOR.equals(tree.get("subjectKind"))) {
            problems.add(new Violation("treeNodeId", PublishingCodes.NODE_SPONSOR_LIT, "Sponsors answer this "
                + "template's nodes through their operator's copy", params));
        } else if (!Boolean.TRUE.equals(node.get("bindable"))) {
            problems.add(new Violation("treeNodeId", PublishingCodes.NODE_NOT_BINDABLE, "The node's condition asks "
                + "for no pass of a publication", params));
        }
        return problems;
    }

    /**
     * The publisher's own graph, or (an operator's direct Sponsor's) a platform graph or the operator's own copy.
     * {@code direct} is the operator whose direct Sponsor the caller is, null when none.
     */
    private static boolean mayBind(EntityInstance tree, Object publisherId, EntityInstance direct) {
        Object kind = tree.get("ownerKind");
        if (QbTrees.SPONSOR.equals(kind)) {
            return Objects.equals(String.valueOf((Object) tree.get("ownerId")), String.valueOf(publisherId));
        }
        if (direct == null) {
            return false;
        }
        return QbTrees.PLATFORM.equals(kind) || QbTrees.OPERATOR.equals(kind)
            && Objects.equals(String.valueOf((Object) tree.get("ownerId")), String.valueOf(direct.id()));
    }

    /**
     * F-S4a: whether the publication's node, as {@link #load} read it, is of a map Sponsors light: a challenge for the
     * operator's Sponsors, not for Takers. False when bound to none (or to a node not found: its problems say so).
     */
    static boolean forSponsors(ProcessContext ctx) {
        EntityInstance tree = first(ctx, TREE);
        return tree != null && QbTrees.SPONSOR.equals(tree.get("subjectKind"));
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : List.of();
        return found == null || found.isEmpty() ? null : found.get(0);
    }
}
