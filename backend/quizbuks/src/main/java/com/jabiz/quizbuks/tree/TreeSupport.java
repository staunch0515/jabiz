package com.jabiz.quizbuks.tree;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Support's read of one person's lighting on one knowledge graph (F-S3, docs/quizbuks/plans/impl/F-S3.md §2 and §8):
 * a read only process, so that every read is in the platform's record of processes (whose input summary holds the two
 * ids only). Content administrators read any graph Takers light; an operator's member reads its Sponsors' graphs and
 * the platform's (the entry scope; another's graph is not found). It gives the lit records, their revocations and what
 * each node is to the person ({@link TreeLights.State}); the finer states of the pages need what the person can start
 * now, which only the person's own reading knows.
 */
public final class TreeSupport {

    public static final String PROCESS = "QB_SUPPORT_TREE_STATE";

    static final String INPUT = "support.input";
    static final String TREE = "support.tree";
    static final String ASSIGNMENT = "support.assignment";
    static final String NODES = "support.nodes";
    static final String DEFS = "support.defs";
    static final String LITS = "support.lits";
    static final String REVOKED = "support.revoked";
    static final String OUTPUT = "support.output";

    private TreeSupport() {}

    /**
     * @param userId the person
     * @param treeId the graph
     */
    public record Input(@NotBlank @Size(max = QbTrees.OWNER_LENGTH) String userId,
        @NotBlank @Size(max = QbTrees.ID_LENGTH) String treeId) {}

    /** A lit record of the node and, if revoked, when and why. */
    public record LitView(String litId, Object litAt, Object revokedAt, String revokeReason) {}

    /**
     * A node in effect and what it is to the person: {@code LIT}, {@code OPEN} (prerequisites lit) or {@code UNMET}
     * (with the prerequisites not lit, {@code <graph>/<node>}).
     */
    public record NodeView(String nodeId, Object name, String state, List<String> unmet, List<LitView> lits) {}

    public record Output(String treeId, String ownerKind, String ownerId, List<NodeView> nodes) {}

    public static ProcessDefinition<Input, Output, ProcessContext> process() {
        return ProcessDefinition.define(PROCESS, 1, Input.class, Output.class, ProcessContext.class, pb -> {
            pb.description("Shows support one person's lighting on one knowledge graph (read only; every read is "
                    + "recorded).")
                .permissions(QbPermissions.TREE_STATE_READ)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, Output.class));
            CallerChecks.refuseBanned(pb);
            OperatorChecks.memberWhenScoped(pb);
            pb.step("Load the graph", QueryEntities.<ProcessContext>of(QbTrees.defaultDataset(QbTrees.TREE),
                    ctx -> byId("treeId", input(ctx).treeId()), TREE))
                .step("Load the Sponsor's operator, the nodes and the person's records", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> {
                        EntityInstance tree = first(ctx, TREE);
                        return EntityQuery.builder().where(new QueryPredicate.In("userId", tree != null
                            && QbTrees.SPONSOR.equals(tree.get("ownerKind"))
                            ? List.of(String.valueOf((Object) tree.get("ownerId"))) : List.of())).limit(1).build();
                    }, ASSIGNMENT),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("treeId", List.of(input(ctx).treeId())),
                            new QueryPredicate.Eq("active", true)))).build(), NODES),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE_DEF), ctx -> {
                        EntityInstance tree = first(ctx, TREE);
                        Object version = tree == null ? null : tree.get("currentVersionId");
                        return EntityQuery.builder().where(new QueryPredicate.In("versionId", version == null
                            ? List.of() : List.of(String.valueOf(version)))).build();
                    }, DEFS),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.LIT), ctx -> subject(input(ctx).userId()),
                        LITS),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOCATION),
                        ctx -> subject(input(ctx).userId()), REVOKED)))
                .compute("Show the person's lighting", (metadata, ctx) -> ctx.put(OUTPUT, show(ctx)));
        });
    }

    static Output show(ProcessContext ctx) {
        Input input = input(ctx);
        EntityInstance tree = first(ctx, TREE);
        // Graphs Takers light only; an operator's member reads its Sponsors' graphs and the platform's, never another
        // operator's: anything else is not found, as if it did not exist.
        if (tree == null || !QbTrees.TAKER.equals(tree.get("subjectKind"))
            || QbTrees.OPERATOR.equals(tree.get("ownerKind"))) {
            throw notFound(input);
        }
        com.jabiz.context.EntryScope scope = ctx.request().entryScope();
        if (scope != null && scope.is(QbOperators.SCOPE) && QbTrees.SPONSOR.equals(tree.get("ownerKind"))
            && !Objects.equals(TreeRevocations.operatorOf(tree, first(ctx, ASSIGNMENT)), scope.value())) {
            throw notFound(input);
        }
        Map<String, EntityInstance> revocations = new LinkedHashMap<>();
        for (EntityInstance revocation : list(ctx, REVOKED)) {
            revocations.put(String.valueOf((Object) revocation.get("litId")), revocation);
        }
        List<TreeLights.Lit> lits = new ArrayList<>();
        for (EntityInstance lit : list(ctx, LITS)) {
            lits.add(new TreeLights.Lit(String.valueOf(lit.id()), String.valueOf((Object) lit.get("treeId")),
                String.valueOf((Object) lit.get("nodeId"))));
        }
        Set<TreeLights.NodeRef> litNow = TreeLights.litNow(lits, revocations.keySet());
        Map<String, Object> names = new LinkedHashMap<>();
        Map<String, Integer> order = new LinkedHashMap<>();
        for (EntityInstance def : list(ctx, DEFS)) {
            String nodeId = String.valueOf((Object) def.get("nodeId"));
            names.put(nodeId, def.get("name"));
            Object seq = def.get("seq");
            order.put(nodeId, seq instanceof Number n ? n.intValue() : Integer.MAX_VALUE);
        }
        String treeId = String.valueOf(tree.id());
        List<NodeView> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EntityInstance node : list(ctx, NODES)) {
            String nodeId = String.valueOf((Object) node.get("nodeId"));
            if (!seen.add(nodeId)) {
                continue;
            }
            List<String> beforeAnswer = TreeProcesses.nodeIds(node.get("beforeAnswer"));
            TreeLights.State state = TreeLights.state(new TreeLights.NodeRef(treeId, nodeId), beforeAnswer, litNow);
            List<LitView> records = new ArrayList<>();
            for (EntityInstance lit : list(ctx, LITS)) {
                if (treeId.equals(String.valueOf((Object) lit.get("treeId")))
                    && nodeId.equals(String.valueOf((Object) lit.get("nodeId")))) {
                    EntityInstance revocation = revocations.get(String.valueOf(lit.id()));
                    records.add(new LitView(String.valueOf(lit.id()), lit.get("litAt"),
                        revocation == null ? null : revocation.get("revokedAt"),
                        revocation == null ? null : String.valueOf((Object) revocation.get("reason"))));
                }
            }
            nodes.add(new NodeView(nodeId, names.get(nodeId), state.name(),
                TreeLights.unmet(beforeAnswer, litNow), List.copyOf(records)));
        }
        nodes.sort(Comparator.comparing(n -> order.getOrDefault(n.nodeId(), Integer.MAX_VALUE)));
        return new Output(treeId, String.valueOf((Object) tree.get("ownerKind")),
            tree.get("ownerId") == null ? null : String.valueOf((Object) tree.get("ownerId")), List.copyOf(nodes));
    }

    private static EntityNotFoundException notFound(Input input) {
        return new EntityNotFoundException(QbTrees.TREE + " " + input.treeId() + " not found");
    }

    private static EntityQuery subject(String userId) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("subjectKind", QbTrees.USER),
            new QueryPredicate.In("subjectId", List.of(userId))))).build();
    }

    private static EntityQuery byId(String field, Object id) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, id == null ? List.of()
            : List.of(String.valueOf(id)))).limit(1).build();
    }

    private static Input input(ProcessContext ctx) {
        return ctx.get(INPUT, Input.class);
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null ? List.of() : found;
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.get(0);
    }
}
