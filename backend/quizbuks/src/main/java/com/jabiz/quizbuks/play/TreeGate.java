package com.jabiz.quizbuks.play;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.tree.QbTrees;
import com.jabiz.quizbuks.tree.TreeLights;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The start of a publication bound to a knowledge graph's node (F-S2b1): the node must be in effect, and the nodes its
 * condition wants lit before answering must be lit by the caller now ({@link TreeLights}). The node's row (with what
 * it wants lit before answering, written when its version went into effect) and the caller's lit records and
 * revocations are read in one statement before the lock, only for a bound card: an ordinary publication's queries are
 * empty and send nothing, so its statement budget is unchanged.
 */
public final class TreeGate {

    static final String NODE = "play.treeNode";
    static final String LITS = "play.treeLits";
    static final String REVOCATIONS = "play.treeRevocations";
    /** The most lit records and revocations a person is read with (a graph has at most a few hundred nodes). */
    static final int MAX_ROWS = 5000;

    private TreeGate() {}

    static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, EntityInstance> card) {
        pb.step("Load the node and the caller's lit nodes", QueryEntities.<ProcessContext>all(
            QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE), ctx -> {
                EntityInstance bound = bound(card.apply(ctx));
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("treeId", bound == null ? List.of() : List.of((Object) bound.get("treeId"))),
                    new QueryPredicate.Eq("nodeId", bound == null ? "" : (Object) bound.get("treeNodeId"))))).limit(1).build();
            }, NODE),
            QueryEntities.query(QbTrees.MY_LITS, ctx -> EntityQuery.builder().where(new QueryPredicate.In(
                "subjectKind", bound(card.apply(ctx)) == null ? List.of() : List.of(QbTrees.USER)))
                .limit(MAX_ROWS).build(), LITS),
            QueryEntities.query(QbTrees.MY_REVOCATIONS, ctx -> EntityQuery.builder().where(new QueryPredicate.In(
                "subjectKind", bound(card.apply(ctx)) == null ? List.of() : List.of(QbTrees.USER)))
                .limit(MAX_ROWS).build(), REVOCATIONS)));
    }

    /** Refuses the start: the node is out of effect (422 {@code QB_TREE_NODE_CLOSED}), or not unlocked. */
    static void refuse(ProcessContext ctx, String field, EntityInstance card) {
        if (bound(card) == null) {
            return;
        }
        EntityInstance node = PlaySteps.first(ctx, NODE);
        if (node == null || !Boolean.TRUE.equals(node.get("active"))) {
            ctx.reject(new Violation(field, PlayCodes.TREE_NODE_CLOSED, "The knowledge graph's node this publication "
                + "is bound to is no longer in effect", Map.of()));
            return;
        }
        List<String> unmet = TreeLights.unmet(TreeProcesses.nodeIds(node.get("beforeAnswer")), litNow(ctx));
        if (!unmet.isEmpty()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("nodes", unmet);
            params.put("unmet", String.valueOf(unmet.size()));
            ctx.reject(new Violation(field, PlayCodes.TREE_NOT_UNLOCKED, "A node to be lit before answering is not "
                + "lit yet", params));
        }
    }

    /** The nodes the caller has lit now, from what was read. */
    static java.util.Set<String> litNow(ProcessContext ctx) {
        return TreeLights.litNow(PlaySteps.list(ctx, LITS).stream().map(row -> new TreeLights.Lit(
                String.valueOf(row.id()), String.valueOf((Object) row.get("nodeId")))).toList(),
            PlaySteps.list(ctx, REVOCATIONS).stream().map(row -> String.valueOf((Object) row.get("litId"))).toList());
    }

    private static EntityInstance bound(EntityInstance card) {
        return card == null || card.get("treeNodeId") == null ? null : card;
    }
}
