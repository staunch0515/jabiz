package com.jabiz.quizbuks.tree;

import com.jabiz.event.DomainEvent;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.AttemptStatus;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lighting knowledge graphs' nodes (F-S2b1, docs/quizbuks/plans/impl/F-S2.md §4): {@value #EVALUATE} judges, outside
 * the answering, whether a subject's nodes hold now and writes a lit record for each that does and is not lit yet.
 * A pass of a publication bound to a node ({@code qb.tree.passed}, published by the completion) evaluates that node;
 * a node lit ({@value #LIT_EVENT}, published here) evaluates the nodes whose condition names it, one level per
 * transaction, at most {@code qb.tree.max-depth} levels down. Lighting is permanent (A2-12): a node lit is never lit
 * again while its record stands; a person who passes again gets the reward as usual and no new record.
 *
 * <p>The subject's lock {@code qb.tree-lit:<kind>:<id>} is taken first and alone (never with a publication's, start
 * or wallet lock); every record is read again under it. In the first launch the subjects are people (Takers): the
 * graphs Sponsors light have no bound publications before F-S4, so a Sponsor's evaluation finds nothing to do.
 */
public final class TreeLighting {

    public static final String EVALUATE = "QB_TREE_EVALUATE";
    /** Nodes just lit, whose dependants are evaluated next. */
    public static final String LIT_EVENT = "qb.tree.lit";
    public static final String PASSED_CONSUMER = "qb.tree-passed";
    public static final String LIT_CONSUMER = "qb.tree-lit";

    static final String INPUT = "lighting.input";
    static final String OUTPUT = "lighting.output";
    static final String LITS = "lighting.lits";
    static final String REVOCATIONS = "lighting.revocations";
    static final String REFS = "lighting.refs";
    static final String CANDIDATE_REFS = "lighting.candidateRefs";
    static final String CANDIDATES = "lighting.candidates";
    static final String NODES = "lighting.nodes";
    static final String CARDS = "lighting.cards";
    static final String PASSES = "lighting.passes";
    static final String PARAMS = "lighting.params";
    static final String LIT_NOW = "lighting.litNow";
    /** F-S4b: the candidates' maps (for a copy's template, whose node row holds the grant). */
    static final String TREES = "lighting.trees";
    /** F-S4b: whether a lit record here holds a grant (the grant is then asked for). */
    static final String GRANTED = "lighting.granted";
    /** F-S5a: whether a lit record here froze a certificate's names (the certificate is then asked for). */
    static final String CERTIFIED = "lighting.certified";
    static final int MAX_ROWS = 5000;

    private TreeLighting() {}

    /**
     * What to evaluate for a subject: the nodes named ({@code dependants} false: a pass of one of them), or the nodes
     * whose condition names them ({@code dependants} true: they were just lit).
     *
     * @param depth     how many levels of lighting led here (1 for a pass)
     * @param attemptId the attempt passed, for a pass
     */
    public record EvaluateInput(String subjectKind, String subjectId, String treeId, List<String> nodeIds,
        boolean dependants, int depth, String attemptId) {
        public EvaluateInput {
            nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        }
    }

    /** The nodes lit by this evaluation (graph and node). */
    public record EvaluateOutput(List<String> litNodeIds) {
        public EvaluateOutput {
            litNodeIds = List.copyOf(litNodeIds);
        }
    }

    /** A node to evaluate. */
    record Candidate(String treeId, String nodeId) {}

    public static ProcessDefinition<EvaluateInput, EvaluateOutput, ProcessContext> evaluate() {
        return ProcessDefinition.define(EVALUATE, 1, EvaluateInput.class, EvaluateOutput.class, ProcessContext.class,
            pb -> pb
                .description("Lights the knowledge graphs' nodes whose condition holds for a person now.")
                .permissions(QbPermissions.TREE_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, EvaluateOutput.class)
                    : new EvaluateOutput(List.of()))
                .step("Lock the subject's lighting", HoldLock.<ProcessContext>exclusive(ctx -> person(ctx) == null
                    ? null : QbTrees.litLockName(subject(ctx), person(ctx))))
                .step("Load the subject's lit nodes and the nodes depending on these", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.LIT), ctx -> mine(ctx), LITS),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOCATION), ctx -> mine(ctx), REVOCATIONS),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE_REF), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("refNodeId", person(ctx) != null && input(ctx).dependants()
                            ? new ArrayList<Object>(litIds(input(ctx).nodeIds())) : List.of()))
                        .limit(MAX_ROWS).build(), REFS)))
                .compute("Know the nodes to evaluate", (metadata, ctx) -> ctx.put(CANDIDATES, candidates(ctx)))
                .step("Load the nodes and the publications bound to them", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("nodeId", candidateNodes(ctx))).limit(MAX_ROWS).build(), NODES),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.In("treeNodeId", candidateNodes(ctx)))
                        .limit(MAX_ROWS).build(), CARDS),
                    // F-S2d: the graphs of the nodes their conditions name.
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE_REF), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("nodeId", candidateNodes(ctx))).limit(MAX_ROWS).build(),
                        CANDIDATE_REFS),
                    // F-S4b: a Sponsor's candidates' maps, for the template whose node grants (the node rows above,
                    // read by node id, hold the template's row too: a copy keeps its template's node ids).
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("treeId", QbTrees.SPONSOR.equals(subject(ctx))
                            ? candidateList(ctx).stream().map(c -> (Object) c.treeId()).distinct().toList()
                            : List.of())).limit(MAX_ROWS).build(), TREES)))
                .step("Load the person's passes of them", QueryEntities.<ProcessContext>of(
                    QbPlay.defaultDataset(QbPlay.ATTEMPT), ctx -> {
                        List<Object> publications = new ArrayList<>();
                        for (EntityInstance card : list(ctx, CARDS)) {
                            if (candidate(ctx, card.get("treeId"), card.get("treeNodeId")) && countsFor(ctx, card)) {
                                publications.add(card.get("publicationId"));
                            }
                        }
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("publicationId", publications),
                            new QueryPredicate.Eq("userId", person(ctx) == null ? "" : person(ctx)),
                            new QueryPredicate.Eq("passed", true)))).limit(MAX_ROWS).build();
                    }, PASSES))
                .step("Load the depth limit", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.TREE_MAX_DEPTH))
                .compute("Light the nodes that hold", (metadata, ctx) -> light(ctx))
                .step("Evaluate the nodes depending on them", PublishEvent.<ProcessContext>when(
                    TreeLighting::goesDeeper, LIT_EVENT, TreeLighting::litPayload))
                // F-S4b: the grants are given outside the lighting, under the Sponsor's wallet lock (fee.LightGrants).
                .step("Ask for the grants", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(GRANTED),
                    com.jabiz.quizbuks.fee.LightGrants.DUE_EVENT, ctx -> Map.of("sponsorId", person(ctx))))
                // F-S5a: the certificates are written outside the lighting too, under their own lock
                // (certification.NodeCertificates); here only a record of what was frozen and an event.
                .step("Ask for the certificates", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(CERTIFIED),
                    com.jabiz.quizbuks.certification.NodeCertificates.DUE_EVENT,
                    ctx -> Map.of("subjectKind", subject(ctx), "subjectId", person(ctx)))));
    }

    /**
     * The subject evaluated: a Taker ({@code USER}) or (F-S4a) a Sponsor answering challenges ({@code SPONSOR}); none for
     * another kind.
     */
    private static String person(ProcessContext ctx) {
        EvaluateInput input = input(ctx);
        return QbTrees.LIT_SUBJECTS.contains(input.subjectKind()) && input.subjectId() != null
            && !input.subjectId().isBlank() && !input.nodeIds().isEmpty() ? input.subjectId() : null;
    }

    /** The kind of the subject evaluated ({@link #person} says whether there is one). */
    private static String subject(ProcessContext ctx) {
        return input(ctx).subjectKind();
    }

    /**
     * F-S4a: whether a card's passes count for the subject: a Sponsor lights by passing challenges (bound to maps
     * Sponsors light), a Taker by passing the others.
     */
    private static boolean countsFor(ProcessContext ctx, EntityInstance card) {
        return Boolean.TRUE.equals(card.get("forSponsors")) == QbTrees.SPONSOR.equals(subject(ctx));
    }

    private static EvaluateInput input(ProcessContext ctx) {
        return ctx.get(INPUT, EvaluateInput.class);
    }

    private static EntityQuery mine(ProcessContext ctx) {
        String person = person(ctx);
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.In("subjectId", person == null ? List.of() : List.of(person)),
            new QueryPredicate.Eq("subjectKind", person == null ? QbTrees.USER : subject(ctx))))).limit(MAX_ROWS)
            .build();
    }

    private static List<Candidate> candidates(ProcessContext ctx) {
        EvaluateInput input = input(ctx);
        Set<Candidate> found = new LinkedHashSet<>();
        if (person(ctx) == null) {
            return List.of();
        }
        if (input.dependants()) {
            for (EntityInstance ref : list(ctx, REFS)) {
                if (namesALitNode(ref, input.nodeIds())) {
                    found.add(new Candidate(String.valueOf((Object) ref.get("treeId")),
                        String.valueOf((Object) ref.get("nodeId"))));
                }
            }
        } else if (input.treeId() != null) {
            input.nodeIds().forEach(node -> found.add(new Candidate(input.treeId(), node)));
        }
        return List.copyOf(found);
    }

    /**
     * The node ids of the nodes just lit ({@code <graph>/<node>}; a bare id from an event written before F-S2d names
     * the node of any graph).
     */
    private static List<String> litIds(List<String> lit) {
        return lit.stream().map(entry -> TreeLights.NodeRef.parse(entry).map(TreeLights.NodeRef::nodeId)
            .orElse(entry)).distinct().toList();
    }

    /** Whether a reference names one of the nodes just lit, its graph and id both. */
    private static boolean namesALitNode(EntityInstance ref, List<String> lit) {
        String node = String.valueOf((Object) ref.get("refNodeId"));
        Object graph = ref.get("refTreeId");
        for (String entry : lit) {
            TreeLights.NodeRef named = TreeLights.NodeRef.parse(entry).orElse(null);
            if (named == null ? entry.equals(node)
                : named.nodeId().equals(node) && graph != null && named.treeId().equals(String.valueOf(graph))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Candidate> candidateList(ProcessContext ctx) {
        return ctx.contains(CANDIDATES) ? (List<Candidate>) ctx.get(CANDIDATES) : List.of();
    }

    private static List<Object> candidateNodes(ProcessContext ctx) {
        return candidateList(ctx).stream().map(Candidate::nodeId).distinct().map(Object.class::cast).toList();
    }

    private static boolean candidate(ProcessContext ctx, Object treeId, Object nodeId) {
        return candidateList(ctx).contains(new Candidate(String.valueOf(treeId), String.valueOf(nodeId)));
    }

    /**
     * Writes a lit record for every candidate in effect whose condition holds now and that is not lit now: its pass
     * is any finished, passed attempt of a publication bound to it (an ended publication's too).
     */
    static void light(ProcessContext ctx) {
        Set<TreeLights.NodeRef> litNow = new LinkedHashSet<>(TreeLights.litNow(list(ctx, LITS).stream()
                .map(row -> new TreeLights.Lit(String.valueOf(row.id()), String.valueOf((Object) row.get("treeId")),
                    String.valueOf((Object) row.get("nodeId"))))
                .toList(),
            list(ctx, REVOCATIONS).stream().map(row -> String.valueOf((Object) row.get("litId"))).toList()));
        Map<Candidate, EntityInstance> nodes = new LinkedHashMap<>();
        for (EntityInstance node : list(ctx, NODES)) {
            Candidate key = new Candidate(String.valueOf((Object) node.get("treeId")),
                String.valueOf((Object) node.get("nodeId")));
            nodes.put(key, node);
        }
        Map<String, Candidate> boundTo = new LinkedHashMap<>();
        for (EntityInstance card : list(ctx, CARDS)) {
            boundTo.put(String.valueOf((Object) card.get("publicationId")), new Candidate(
                String.valueOf((Object) card.get("treeId")), String.valueOf((Object) card.get("treeNodeId"))));
        }
        // F-S2b2: a revoked lighting is not lit again from the passes it rested on: only a pass after the person's
        // last revoked lighting of the node counts (qb.system.tree-recheck-due, the same rule).
        Map<String, String> nodeOfLit = new LinkedHashMap<>();
        for (EntityInstance row : list(ctx, LITS)) {
            nodeOfLit.put(String.valueOf(row.id()), row.get("treeId") + " " + row.get("nodeId"));
        }
        Map<String, java.time.Instant> revokedAt = new LinkedHashMap<>();
        for (EntityInstance row : list(ctx, REVOCATIONS)) {
            String node = nodeOfLit.get(String.valueOf((Object) row.get("litId")));
            java.time.Instant at = instant(row.get("revokedAt"));
            if (node != null && at != null) {
                revokedAt.merge(node, at, (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Map<Candidate, String> passedBy = new LinkedHashMap<>();
        for (EntityInstance attempt : list(ctx, PASSES)) {
            Candidate node = boundTo.get(String.valueOf((Object) attempt.get("publicationId")));
            if (node == null || AttemptStatus.IN_PROGRESS.equals(attempt.get("status"))) {
                continue;
            }
            java.time.Instant revoked = revokedAt.get(node.treeId() + " " + node.nodeId());
            java.time.Instant submitted = instant(attempt.get("submittedAt"));
            if (revoked == null || submitted != null && submitted.isAfter(revoked)) {
                passedBy.putIfAbsent(node, String.valueOf(attempt.id()));
            }
        }
        // F-S2d: each candidate's references with their graphs.
        Map<Candidate, List<TreeLights.NodeRef>> refsOf = new LinkedHashMap<>();
        for (EntityInstance ref : list(ctx, CANDIDATE_REFS)) {
            Object graph = ref.get("refTreeId");
            if (graph != null) {
                refsOf.computeIfAbsent(new Candidate(String.valueOf((Object) ref.get("treeId")),
                    String.valueOf((Object) ref.get("nodeId"))), c -> new ArrayList<>()).add(new TreeLights.NodeRef(
                        String.valueOf(graph), String.valueOf((Object) ref.get("refNodeId"))));
            }
        }
        EvaluateInput input = input(ctx);
        List<String> lit = new ArrayList<>();
        List<String> litKeys = new ArrayList<>();
        for (Candidate candidate : candidateList(ctx)) {
            EntityInstance node = nodes.get(candidate);
            if (node == null || !Boolean.TRUE.equals(node.get("active")) || node.get("condition") == null
                || litNow.contains(new TreeLights.NodeRef(candidate.treeId(), candidate.nodeId()))) {
                continue;
            }
            TreeCondition condition = TreeCondition.parse(TreeProcesses.parse(String.valueOf(
                (Object) node.get("condition"))));
            String attempt = passedBy.get(candidate);
            // The condition names nodes by id; one is lit when the node of the graph it resolved to is (a reference
            // resolved to no single graph never is).
            Set<String> litNamed = new LinkedHashSet<>();
            for (TreeLights.NodeRef ref : refsOf.getOrDefault(candidate, List.of())) {
                if (litNow.contains(ref)) {
                    litNamed.add(ref.nodeId());
                }
            }
            if (!TreeRules.holds(condition, litNamed, attempt != null)) {
                continue;
            }
            Map<String, Object> basis = new LinkedHashMap<>();
            if (attempt != null) {
                basis.put("attemptId", attempt);
            }
            if (input.dependants()) {
                basis.put("nodes", input.nodeIds());
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("subjectKind", subject(ctx));
            values.put("subjectId", person(ctx));
            values.put("treeId", candidate.treeId());
            values.put("nodeId", candidate.nodeId());
            values.put("litAt", ctx.opTime());
            values.put("basis", TreeProcesses.json(basis));
            // F-S4b: a Sponsor's lighting freezes the grant of the template's version in effect.
            java.math.BigDecimal grant = QbTrees.SPONSOR.equals(subject(ctx)) ? grantOf(ctx, nodes, candidate) : null;
            values.put("grantAmount", grant);
            if (grant != null) {
                ctx.put(GRANTED, true);
            }
            // F-S5a: the certificate the node issues now, frozen as lit (a node row of its own graph: a copy has the
            // template's, taken over with its nodes).
            Object certificate = node.get("certificateNames");
            values.put("certificateNames", certificate);
            if (certificate != null) {
                ctx.put(CERTIFIED, true);
            }
            ctx.changes().insert(QbTrees.LIT, values);
            // A node lit here counts for the next candidates of the same level.
            TreeLights.NodeRef done = new TreeLights.NodeRef(candidate.treeId(), candidate.nodeId());
            litNow.add(done);
            lit.add(candidate.nodeId());
            litKeys.add(done.key());
        }
        ctx.put(LIT_NOW, List.copyOf(litKeys));
        ctx.put(OUTPUT, new EvaluateOutput(lit));
    }

    /**
     * F-S4b: the grant lighting {@code candidate} earns: the node row of its template in effect (the map itself when it
     * is a platform template Sponsors light, an operator's copy's template otherwise); none for another map, a node the
     * template no longer has, or no grant.
     */
    private static java.math.BigDecimal grantOf(ProcessContext ctx, Map<Candidate, EntityInstance> nodes,
        Candidate candidate) {
        EntityInstance tree = list(ctx, TREES).stream()
            .filter(row -> candidate.treeId().equals(String.valueOf(row.id()))).findFirst().orElse(null);
        if (tree == null || !QbTrees.SPONSOR.equals(tree.get("subjectKind"))) {
            return null;
        }
        String templateId = QbTrees.PLATFORM.equals(tree.get("ownerKind")) ? String.valueOf(tree.id())
            : QbTrees.OPERATOR.equals(tree.get("ownerKind")) && tree.get("templateTreeId") != null
                ? String.valueOf((Object) tree.get("templateTreeId")) : null;
        EntityInstance row = templateId == null ? null : nodes.get(new Candidate(templateId, candidate.nodeId()));
        if (row == null || !Boolean.TRUE.equals(row.get("active")) || row.get("grantAmount") == null) {
            return null;
        }
        java.math.BigDecimal amount = new java.math.BigDecimal(row.get("grantAmount").toString());
        return amount.signum() > 0 ? amount : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> litHere(ProcessContext ctx) {
        return ctx.contains(LIT_NOW) ? (List<String>) ctx.get(LIT_NOW) : List.of();
    }

    /** Whether the nodes lit here have dependants to evaluate within the depth limit. */
    private static boolean goesDeeper(ProcessContext ctx) {
        return !litHere(ctx).isEmpty() && input(ctx).depth() < QbParams.count(ctx.get(PARAMS, ParamValues.class),
            QbParams.TREE_MAX_DEPTH);
    }

    private static Map<String, Object> litPayload(ProcessContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subjectKind", subject(ctx));
        payload.put("subjectId", person(ctx));
        payload.put("nodeIds", litHere(ctx));
        payload.put("depth", input(ctx).depth() + 1);
        return payload;
    }

    /** A pass of a node (from the completion's event). */
    static EvaluateInput passedInput(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        Object node = payload.get("nodeId");
        // F-S4a: a Sponsor's pass of a challenge names its subject; a Taker's is the user's.
        Object kind = payload.get("subjectKind");
        return new EvaluateInput(kind == null ? QbTrees.USER : String.valueOf(kind),
            text(payload.get(kind == null ? "userId" : "subjectId")), text(payload.get("treeId")),
            node == null ? List.of() : List.of(String.valueOf(node)), false, 1, text(payload.get("attemptId")));
    }

    /** Nodes lit (from {@value #LIT_EVENT}): their dependants. */
    static EvaluateInput litInput(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        List<String> nodes = new ArrayList<>();
        if (payload.get("nodeIds") instanceof List<?> list) {
            list.stream().filter(Objects::nonNull).map(String::valueOf).forEach(nodes::add);
        }
        Object depth = payload.get("depth");
        return new EvaluateInput(text(payload.get("subjectKind")), text(payload.get("subjectId")), null, nodes, true,
            depth == null ? 1 : Integer.parseInt(String.valueOf(depth)), null);
    }

    private static java.time.Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case java.time.Instant i -> i;
            case java.time.OffsetDateTime o -> o.toInstant();
            default -> java.time.Instant.parse(String.valueOf(value));
        };
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null ? List.of() : found;
    }
}
