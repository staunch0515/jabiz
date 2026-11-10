package com.jabiz.quizbuks.tree;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-S4b: the features a Sponsor unlocks by lighting a node of a platform template Sponsors light, in any operator's
 * copy of it ({@link TreeGates#openForSponsors}): the reward methods {@code DIGITAL_PRIZE} and {@code OFFLINE_REDEEM}
 * (the controlled parameters {@code qb.tree.gate.digital-prize-node} / {@code offline-redeem-node}, empty by default
 * = closed). The parameters are read by the caller's own {@code LoadParams} step (under {@code paramsKey}); this adds
 * two reads, outside every lock: the gates' templates and nodes with the caller's lit records of those node ids and
 * revocations (one statement), then the maps those lit records are in (none without a lit record: no statement).
 */
public final class SponsorGates {

    /** The most lit records of a gate's node a Sponsor is read with (one per copy they lit it in, really). */
    static final int MAX_LITS = 100;

    private SponsorGates() {}

    /** Adds the reads of the gates {@code gateKeys}, whose parameters the step before read under {@code paramsKey}. */
    public static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String paramsKey,
        List<String> gateKeys, String prefix) {
        pb.step("Load the Sponsor gates' nodes and the caller's lighting of them", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("treeId", treeIds(gates(ctx, paramsKey, gateKeys))))
                    .limit(gateKeys.size()).build(), prefix + "trees"),
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE), ctx -> {
                    List<TreeGates.GateNode> gates = gates(ctx, paramsKey, gateKeys);
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("treeId", treeIds(gates)),
                        new QueryPredicate.In("nodeId", nodeIds(gates))))).limit(gateKeys.size()).build();
                }, prefix + "nodes"),
                QueryEntities.query(QbTrees.MY_SPONSOR_LITS, ctx -> {
                    List<TreeGates.GateNode> gates = gates(ctx, paramsKey, gateKeys);
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("subjectKind", gates.isEmpty() ? List.of() : List.of(QbTrees.SPONSOR)),
                        new QueryPredicate.In("nodeId", nodeIds(gates))))).limit(MAX_LITS * gateKeys.size()).build();
                }, prefix + "lits"),
                QueryEntities.query(QbTrees.MY_SPONSOR_REVOCATIONS, ctx -> EntityQuery.builder().where(new QueryPredicate.In(
                    "subjectKind", gates(ctx, paramsKey, gateKeys).isEmpty() ? List.of() : List.of(QbTrees.SPONSOR)))
                    .limit(TreeLighting.MAX_ROWS).build(), prefix + "revocations")))
            .step("Load the maps the caller lit the gates' nodes in", QueryEntities.<ProcessContext>of(
                QbTrees.defaultDataset(QbTrees.TREE), ctx -> {
                    Set<Object> treeIds = new LinkedHashSet<>();
                    for (EntityInstance lit : list(ctx, prefix + "lits")) {
                        treeIds.add(String.valueOf((Object) lit.get("treeId")));
                    }
                    return EntityQuery.builder().where(new QueryPredicate.In("treeId", List.copyOf(treeIds)))
                        .limit(Math.max(1, treeIds.size())).build();
                }, prefix + "litTrees"));
    }

    /** The gates among {@code gateKeys} open to the caller, from what {@link #load} read. */
    public static Set<String> open(ProcessContext ctx, String paramsKey, List<String> gateKeys, String prefix) {
        Set<String> open = new LinkedHashSet<>();
        ParamValues params = ctx.contains(paramsKey) ? ctx.get(paramsKey, ParamValues.class) : null;
        if (params == null) {
            return open;
        }
        Map<String, String> templateOf = new java.util.HashMap<>();
        for (EntityInstance tree : list(ctx, prefix + "litTrees")) {
            Object template = tree.get("templateTreeId");
            templateOf.put(String.valueOf((Object) tree.get("treeId")), template == null ? null : template.toString());
        }
        List<String> revoked = new ArrayList<>();
        for (EntityInstance revocation : list(ctx, prefix + "revocations")) {
            revoked.add(String.valueOf((Object) revocation.get("litId")));
        }
        for (String key : gateKeys) {
            TreeGates.GateNode gate = TreeGates.parse(params.find(key, Object.class).orElse(null)).orElse(null);
            if (gate == null) {
                continue;
            }
            EntityInstance tree = list(ctx, prefix + "trees").stream()
                .filter(row -> gate.treeId().equals(String.valueOf((Object) row.get("treeId")))).findFirst().orElse(null);
            EntityInstance node = list(ctx, prefix + "nodes").stream()
                .filter(row -> gate.treeId().equals(String.valueOf((Object) row.get("treeId")))
                    && gate.nodeId().equals(String.valueOf((Object) row.get("nodeId")))).findFirst().orElse(null);
            List<TreeGates.SponsorLit> lits = new ArrayList<>();
            for (EntityInstance lit : list(ctx, prefix + "lits")) {
                if (gate.nodeId().equals(String.valueOf((Object) lit.get("nodeId")))) {
                    String treeId = String.valueOf((Object) lit.get("treeId"));
                    lits.add(new TreeGates.SponsorLit(String.valueOf(lit.id()), treeId, templateOf.get(treeId)));
                }
            }
            if (TreeGates.openForSponsors(gate, tree == null ? null : new TreeGates.Tree(
                    String.valueOf((Object) tree.get("ownerKind")), String.valueOf((Object) tree.get("subjectKind")),
                    String.valueOf((Object) tree.get("status"))),
                node == null ? null : (Boolean) node.get("active"), lits, revoked)) {
                open.add(key);
            }
        }
        return open;
    }

    private static List<TreeGates.GateNode> gates(ProcessContext ctx, String paramsKey, List<String> gateKeys) {
        ParamValues params = ctx.contains(paramsKey) ? ctx.get(paramsKey, ParamValues.class) : null;
        if (params == null) {
            return List.of();
        }
        List<TreeGates.GateNode> gates = new ArrayList<>();
        for (String key : gateKeys) {
            TreeGates.parse(params.find(key, Object.class).orElse(null)).ifPresent(gates::add);
        }
        return gates;
    }

    private static List<Object> treeIds(List<TreeGates.GateNode> gates) {
        return gates.stream().map(gate -> (Object) gate.treeId()).distinct().toList();
    }

    private static List<Object> nodeIds(List<TreeGates.GateNode> gates) {
        return gates.stream().map(gate -> (Object) gate.nodeId()).distinct().toList();
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key, Object.class) : null;
        return value instanceof List<?> rows ? (List<EntityInstance>) rows : List.of();
    }
}
