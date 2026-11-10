package com.jabiz.quizbuks.tree;

import com.jabiz.param.ParamValues;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.EntityInstance;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The platform features a Taker unlocks by lighting a node (F-S2c, A2-11; the specification's "门槛"): referring
 * friends and the two benefit roles, and (F-S2c2) applying to become a Sponsor. Each is a controlled parameter
 * ({@code QbParams.TREE_GATES}) naming one node as {@code <treeId>/<nodeId>}. A feature is open to the caller when the
 * parameter names a node in effect of a platform map Takers light, and the caller has lit that node now
 * ({@link TreeLights}: a lit record without a revocation). Anything else is closed: an absent parameter (the default,
 * "empty = closed"), a malformed one, a node that is not there or no longer in effect, a map of another kind. The
 * daily check reports a parameter that does not name such a node ({@code GATE_INVALID}).
 *
 * <p>Read in one statement, outside every lock (the node's row, its map, and the caller's lit records and revocations
 * of it), after the parameter; never on the answering path.
 */
public final class TreeGates {

    /** The most lit records and revocations of one node a person is read with (one each, really). */
    static final int MAX_ROWS = 100;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private TreeGates() {}

    /** A gate's node, as its parameter names it. */
    public record GateNode(String treeId, String nodeId) {}

    /** What a gate's parameter names, if it is well formed: {@code <treeId>/<nodeId>}, both lower-case UUIDs. */
    public static Optional<GateNode> parse(Object value) {
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        int slash = text.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        // As written, no trimming: the templates read the parameter with the same pattern (qb.sponsor.benefit-access).
        String tree = text.substring(0, slash);
        String node = text.substring(slash + 1);
        return ID.matcher(tree).matches() && ID.matcher(node).matches()
            ? Optional.of(new GateNode(tree, node)) : Optional.empty();
    }

    /**
     * Whether the gate is open to the person: the node's map is a platform map Takers light, the node is in effect, and
     * the person's lit records of it are not all revoked.
     *
     * @param tree        the node's map ({@code ownerKind}, {@code subjectKind}), null when not found
     * @param node        the node's row ({@code active}), null when not found
     * @param litIds      the person's lit records of the node
     * @param revokedLits the lit records revoked
     */
    public static boolean open(Tree tree, Boolean nodeActive, Collection<String> litIds, Collection<String> revokedLits) {
        return names(tree, nodeActive) && litIds.stream().anyMatch(id -> !revokedLits.contains(id));
    }

    /** Whether a gate's parameter names a node that can be lit to open it (the daily check's {@code GATE_INVALID}). */
    public static boolean names(Tree tree, Boolean nodeActive) {
        return tree != null && QbTrees.PLATFORM.equals(tree.ownerKind()) && QbTrees.TAKER.equals(tree.subjectKind())
            && QbTrees.ACTIVE.equals(tree.status()) && Boolean.TRUE.equals(nodeActive);
    }

    /** A node's map as the gate sees it. */
    public record Tree(String ownerKind, String subjectKind, String status) {}

    // ---- F-S4b: Sponsor gates ---------------------------------------------------------------------------------------

    /**
     * F-S4b: whether a Sponsor gate's parameter names a node that can be lit to open it: a node in effect of a
     * platform template Sponsors light, in effect (the daily check's {@code GATE_INVALID} for the Sponsor gates).
     */
    public static boolean namesForSponsors(Tree tree, Boolean nodeActive) {
        return tree != null && QbTrees.PLATFORM.equals(tree.ownerKind()) && QbTrees.SPONSOR.equals(tree.subjectKind())
            && QbTrees.ACTIVE.equals(tree.status()) && Boolean.TRUE.equals(nodeActive);
    }

    /** A Sponsor's lit record of a gate's node: its map, and whether that map is the template or a copy of it. */
    public record SponsorLit(String litId, String treeId, String templateTreeId) {}

    /**
     * F-S4b (Q90 §0-21): whether a Sponsor gate is open to the Sponsor: the parameter names a node of a platform
     * template Sponsors light ({@link #namesForSponsors}), and the Sponsor lit the same node, in the template or in any
     * operator's copy of it (a copy keeps the template's node ids; a lit record in a former operator's copy still
     * counts after a reassignment), with no revocation of that record.
     */
    public static boolean openForSponsors(GateNode gate, Tree tree, Boolean nodeActive, Collection<SponsorLit> lits,
        Collection<String> revokedLits) {
        return gate != null && namesForSponsors(tree, nodeActive) && lits.stream().anyMatch(lit ->
            (gate.treeId().equals(lit.treeId()) || gate.treeId().equals(lit.templateTreeId()))
                && !revokedLits.contains(lit.litId()));
    }

    /**
     * Reads the gate {@code paramKey} for the caller, under keys starting with {@code prefix}, only when
     * {@code wanted} (otherwise the queries are empty and send nothing; the parameter is still read).
     * {@link #isOpen(ProcessContext, String, String)} answers from what was read.
     */
    public static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String paramKey, String prefix,
        Predicate<ProcessContext> wanted) {
        pb.step("Load the gate's node", LoadParams.<ProcessContext>optional(ProcessContext::opTime, prefix + "params",
                paramKey))
            .step("Load the gate's node and the caller's lighting of it", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> {
                    GateNode gate = wanted(ctx, paramKey, prefix, wanted);
                    return EntityQuery.builder().where(new QueryPredicate.In("treeId",
                        gate == null ? List.of() : List.of((Object) gate.treeId()))).limit(1).build();
                }, prefix + "tree"),
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE), ctx -> nodeQuery(
                    wanted(ctx, paramKey, prefix, wanted), 1), prefix + "node"),
                QueryEntities.query(QbTrees.MY_LITS, ctx -> {
                    GateNode gate = wanted(ctx, paramKey, prefix, wanted);
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("subjectKind", gate == null ? List.of() : List.of(QbTrees.USER)),
                        new QueryPredicate.Eq("treeId", gate == null ? "" : gate.treeId()),
                        new QueryPredicate.Eq("nodeId", gate == null ? "" : gate.nodeId()))))
                        .limit(MAX_ROWS).build();
                }, prefix + "lits"),
                QueryEntities.query(QbTrees.MY_REVOCATIONS, ctx -> EntityQuery.builder().where(new QueryPredicate.In(
                    "subjectKind", wanted(ctx, paramKey, prefix, wanted) == null ? List.of() : List.of(QbTrees.USER)))
                    .limit(TreeLighting.MAX_ROWS).build(), prefix + "revocations")));
    }

    /** Whether the gate read by {@link #load} under {@code prefix} is open to the caller. */
    public static boolean isOpen(ProcessContext ctx, String paramKey, String prefix) {
        if (parsed(ctx, paramKey, prefix) == null) {
            return false;
        }
        EntityInstance tree = first(ctx, prefix + "tree");
        EntityInstance node = first(ctx, prefix + "node");
        List<String> lits = new ArrayList<>();
        for (EntityInstance lit : list(ctx, prefix + "lits")) {
            lits.add(String.valueOf(lit.id()));
        }
        List<String> revoked = new ArrayList<>();
        for (EntityInstance revocation : list(ctx, prefix + "revocations")) {
            revoked.add(String.valueOf((Object) revocation.get("litId")));
        }
        return open(tree == null ? null : new Tree(String.valueOf((Object) tree.get("ownerKind")),
                String.valueOf((Object) tree.get("subjectKind")), String.valueOf((Object) tree.get("status"))),
            node == null ? null : (Boolean) node.get("active"), lits, revoked);
    }

    private static EntityQuery nodeQuery(GateNode gate, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.In("treeId", gate == null ? List.of() : List.of((Object) gate.treeId())),
            new QueryPredicate.Eq("nodeId", gate == null ? "" : gate.nodeId())))).limit(limit).build();
    }

    private static GateNode wanted(ProcessContext ctx, String paramKey, String prefix,
        Predicate<ProcessContext> wanted) {
        return wanted.test(ctx) ? parsed(ctx, paramKey, prefix) : null;
    }

    private static GateNode parsed(ProcessContext ctx, String paramKey, String prefix) {
        ParamValues params = ctx.get(prefix + "params", ParamValues.class);
        return params == null ? null : parse(params.find(paramKey, Object.class).orElse(null)).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.get(key, Object.class);
        return value instanceof List<?> rows ? (List<EntityInstance>) rows : List.of();
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = list(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
