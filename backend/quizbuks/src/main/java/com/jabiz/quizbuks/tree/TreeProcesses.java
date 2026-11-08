package com.jabiz.quizbuks.tree;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.ReviewCodes;
import com.jabiz.quizbuks.tree.TreeRules.Graph;
import com.jabiz.quizbuks.tree.TreeRules.Limits;
import com.jabiz.quizbuks.tree.TreeRules.Problem;
import com.jabiz.quizbuks.tree.TreeRules.Subject;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.RandomBytesStep;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Knowledge graphs' definitions, versions and review (F-S2a, docs/quizbuks/plans/impl/F-S2.md §4):
 * <ul>
 *   <li>{@value #CREATE}: a Sponsor's own graph (lit by Takers, at most {@code qb.tree.sponsor.max-trees}), or a
 *       platform's template (lit by Takers or by Sponsors), with its first draft;</li>
 *   <li>{@value #COPY}: an operator's copy of a template lit by Sponsors, keeping the template's node ids (one copy
 *       per template and operator);</li>
 *   <li>{@value #SAVE}: the whole draft (its name and every node), giving every problem the rules find; a version in
 *       review goes back to its draft (the review is withdrawn);</li>
 *   <li>{@value #ADOPT}: an operator's copy takes, node by node, the template's version in effect;</li>
 *   <li>{@value #SUBMIT} / {@value #WITHDRAW}: the draft goes to review (a Sponsor's to their operator, an operator's
 *       copy to another member of it, a template to another administrator) and comes back;</li>
 *   <li>{@value #APPROVAL_RESULT}: run as the system for the approval events: puts the version in effect or sends it
 *       back;</li>
 *   <li>{@value #RETIRE}: the graph is shown to nobody new and none of its nodes is lit any more.</li>
 * </ul>
 * Who may edit a graph is checked here, by its owner (any other caller finds no graph: 404). Locks: an operator's
 * membership (shared), then {@code qb.tree-owner:<owner>} (creating), then {@code qb.tree:<graph>}, all before any
 * publication's.
 */
public final class TreeProcesses {

    public static final String CREATE = "QB_TREE_CREATE";
    public static final String COPY = "QB_TREE_COPY";
    public static final String SAVE = "QB_TREE_DRAFT_SAVE";
    public static final String ADOPT = "QB_TREE_ADOPT";
    public static final String SUBMIT = "QB_TREE_SUBMIT";
    public static final String WITHDRAW = "QB_TREE_WITHDRAW";
    public static final String APPROVAL_RESULT = "QB_TREE_APPROVAL_RESULT";
    public static final String RETIRE = "QB_TREE_RETIRE";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * @param ownerKind   {@code SPONSOR} (the caller's own graph) or {@code PLATFORM} (a template)
     * @param subjectKind who lights a template's nodes ({@code TAKER} or {@code SPONSOR}); a Sponsor's graph is lit
     *                    by Takers whatever is given
     * @param name        the graph's name by language
     */
    public record CreateInput(@NotBlank String ownerKind, String subjectKind, @NotNull Map<String, String> name,
        Map<String, String> description) {}

    /** @param templateTreeId the platform's template to copy (lit by Sponsors) */
    public record CopyInput(@NotBlank String templateTreeId) {}

    /**
     * A node of a draft.
     *
     * @param nodeId    the node's id; none for a new one, which is named by {@code key} in the save's conditions
     * @param key       a new node's name within this save (any text; replaced by the id the save gives it)
     * @param condition the condition ({@link TreeCondition#toMap()}), naming nodes by id or, new ones, by key
     */
    public record NodeInput(String nodeId, String key, @NotNull Map<String, String> name,
        Map<String, String> description, @NotNull Map<String, Object> condition) {}

    /** @param nodes the draft's every node, in order (given is complete: a node left out is no longer in it) */
    public record SaveInput(@NotBlank String treeId, Long baseRevision, @NotNull Map<String, String> name,
        Map<String, String> description, @NotNull @Size(max = 1000) List<NodeInput> nodes) {}

    /** @param nodeIds the template's nodes to take: as the template has them in effect, or gone when it has not */
    public record AdoptInput(@NotBlank String treeId, Long baseRevision, @NotNull List<String> nodeIds) {}

    public record TreeInput(@NotBlank String treeId, Long baseRevision) {}

    /** A problem the rules find in a draft, on a node (none for the graph). */
    public record ProblemView(String nodeId, String code, Map<String, Object> params) {}

    /**
     * @param nodeIds  the draft's node ids by the save's keys of new nodes
     * @param problems what keeps the draft from being submitted
     * @param approval the approval outcome when the process asked for one
     */
    public record TreeOutput(String treeId, String versionId, long versionNo, String status, long revision,
        Map<String, String> nodeIds, List<ProblemView> problems, String approval, String approvalRequestId) {}

    /** Input of {@value #APPROVAL_RESULT}, from an approval event of any subject. */
    public record ApprovalResultInput(@NotBlank String subject, @NotBlank String entityId, @NotBlank String status,
        String requestId, String preparerId, String contentHash, String reason) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String TREE_ID = "treeId";
    static final String TREE = "tree";
    static final String VERSIONS = "versions";
    static final String NODES = "nodes";
    static final String DEFS = "defs";
    static final String OWNED = "owned";
    static final String TEMPLATE = "template";
    static final String TEMPLATE_DEFS = "templateDefs";
    static final String GRAPHS = "graphs";
    static final String GRAPH_DEFS = "graphDefs";
    static final String ASSIGNMENT = "assignment";
    static final String PARAMS = "params";
    static final String RANDOM = "random";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String WITHDRAW_REVIEW = "withdrawReview";
    static final String REQUEST = "request";
    static final String DECISIONS = "decisions";
    static final String FIRST = "first";
    /** More of the graph's fields a save writes with its revision. */
    static final String TOUCH = "touch";

    private TreeProcesses() {}

    // ---- create ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<CreateInput, TreeOutput, ProcessContext> create() {
        return ProcessDefinition.define(CREATE, 1, CreateInput.class, TreeOutput.class, ProcessContext.class, pb -> {
            pb.description("Creates a knowledge graph with its first draft: the Sponsor's own, or a platform's "
                    + "template.")
                .permissions(QbPermissions.TREE_EDIT)
                .requiresVerifiedEmail()
                .contextFactory(TreeProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, TreeOutput.class));
            CallerChecks.refuseBanned(pb);
            pb.step("Lock the owner's graphs", HoldLock.<ProcessContext>exclusive(ctx -> {
                    CreateInput input = ctx.get(INPUT, CreateInput.class);
                    return QbTrees.ownerLockName(input.ownerKind(), ownerOf(ctx, input));
                }))
                .step("Load the owner's graphs", QueryEntities.<ProcessContext>of(
                    QbTrees.defaultDataset(QbTrees.TREE), ctx -> {
                        CreateInput input = ctx.get(INPUT, CreateInput.class);
                        Object owner = ownerOf(ctx, input);
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("ownerKind", List.of(input.ownerKind())),
                            new QueryPredicate.In("ownerId", owner == null ? List.of() : List.of(owner)),
                            new QueryPredicate.In("status", List.of(QbTrees.ACTIVE)))))
                            .limit(1000).build();
                    }, OWNED))
                .step("Load the limit", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.TREE_SPONSOR_MAX_TREES))
                .compute("Create the graph", (metadata, ctx) -> created(ctx));
        });
    }

    private static Object ownerOf(ProcessContext ctx, CreateInput input) {
        return QbTrees.SPONSOR.equals(input.ownerKind()) ? ctx.request().actorId() : null;
    }

    static void created(ProcessContext ctx) {
        CreateInput input = ctx.get(INPUT, CreateInput.class);
        String subject;
        if (QbTrees.SPONSOR.equals(input.ownerKind())) {
            subject = QbTrees.TAKER;
            int max = QbParams.count(ctx.get(PARAMS, ParamValues.class), QbParams.TREE_SPONSOR_MAX_TREES);
            if (list(ctx, OWNED).size() >= max) {
                throw rule(null, TreeCodes.LIMIT, "The Sponsor has " + max + " graphs of their own already",
                    Map.of("max", max));
            }
        } else if (QbTrees.PLATFORM.equals(input.ownerKind())) {
            requirePlatform(ctx);
            subject = input.subjectKind();
            if (!QbTrees.SUBJECTS.contains(subject)) {
                throw rule("subjectKind", TreeCodes.STATE, "A template is lit by Takers or by Sponsors",
                    Map.of("subjectKind", String.valueOf(subject)));
            }
        } else {
            throw rule("ownerKind", TreeCodes.STATE, "A graph is a Sponsor's or the platform's",
                Map.of("ownerKind", input.ownerKind()));
        }
        requireName(input.name());
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("ownerKind", input.ownerKind());
        tree.put("ownerId", ownerOf(ctx, input) == null ? null : String.valueOf(ownerOf(ctx, input)));
        tree.put("subjectKind", subject);
        tree.put("status", QbTrees.ACTIVE);
        tree.put("createdAt", ctx.opTime());
        tree.put("createdBy", ctx.request().actorId());
        tree.put("revision", BigDecimal.ONE);
        Object treeId = ctx.changes().insert(QbTrees.TREE, tree);
        Object versionId = insertVersion(ctx, treeId, 1, input.name(), input.description());
        ctx.put(OUTPUT, new TreeOutput(String.valueOf(treeId), String.valueOf(versionId), 1, QbTrees.DRAFT, 1,
            Map.of(), List.of(), null, null));
    }

    private static Object insertVersion(ProcessContext ctx, Object treeId, long versionNo, Map<String, String> name,
        Map<String, String> description) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("treeId", String.valueOf(treeId));
        version.put("versionNo", BigDecimal.valueOf(versionNo));
        version.put("name", name);
        version.put("description", description == null || description.isEmpty() ? null : description);
        version.put("status", QbTrees.DRAFT);
        version.put("editedAt", ctx.opTime());
        return ctx.changes().insert(QbTrees.VERSION, version);
    }

    // ---- copy ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<CopyInput, TreeOutput, ProcessContext> copy() {
        return ProcessDefinition.define(COPY, 1, CopyInput.class, TreeOutput.class, ProcessContext.class, pb -> {
            pb.description("Copies a platform's template lit by Sponsors as the caller's operator's own graph, keeping "
                    + "its node ids; the copy is a draft until another member approves it.")
                .permissions(QbPermissions.TREE_EDIT)
                .requiresVerifiedEmail()
                .contextFactory(TreeProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, TreeOutput.class));
            OperatorChecks.member(pb);
            pb.step("Lock the operator's graphs", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbTrees.ownerLockName(QbTrees.OPERATOR, OperatorChecks.operatorId(ctx))))
                .step("Load the template and the operator's copies", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> byIds("treeId",
                        ctx.get(INPUT, CopyInput.class).templateTreeId()), TEMPLATE),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("ownerKind", List.of(QbTrees.OPERATOR)),
                            new QueryPredicate.In("ownerId", List.of(OperatorChecks.operatorId(ctx))),
                            new QueryPredicate.In("templateTreeId",
                                List.of(ctx.get(INPUT, CopyInput.class).templateTreeId())))))
                        .limit(1).build(), OWNED)))
                .step("Load the template's version in effect", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.VERSION), ctx -> byIds("versionId",
                        templateVersionId(ctx)), VERSIONS),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE_DEF), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("versionId", ids(templateVersionId(ctx))))
                        .limit(Integer.MAX_VALUE).build(), TEMPLATE_DEFS)))
                .compute("Copy the template", (metadata, ctx) -> copied(ctx));
        });
    }

    private static Object templateVersionId(ProcessContext ctx) {
        EntityInstance template = first(ctx, TEMPLATE);
        return template == null ? null : template.get("currentVersionId");
    }

    static void copied(ProcessContext ctx) {
        EntityInstance template = first(ctx, TEMPLATE);
        EntityInstance version = first(ctx, VERSIONS);
        if (template == null || !QbTrees.PLATFORM.equals(template.get("ownerKind"))
            || !QbTrees.SPONSOR.equals(template.get("subjectKind")) || !QbTrees.ACTIVE.equals(template.get("status"))
            || version == null) {
            throw rule("templateTreeId", TreeCodes.NOT_A_TEMPLATE, "Only a platform's template in effect whose nodes "
                + "Sponsors light is copied", Map.of());
        }
        if (first(ctx, OWNED) != null) {
            throw rule("templateTreeId", TreeCodes.COPY_EXISTS, "The operator has a copy of this template",
                Map.of("treeId", String.valueOf(first(ctx, OWNED).id())));
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("ownerKind", QbTrees.OPERATOR);
        tree.put("ownerId", OperatorChecks.operatorId(ctx));
        tree.put("subjectKind", QbTrees.SPONSOR);
        tree.put("templateTreeId", String.valueOf(template.id()));
        tree.put("templateVersionNo", version.get("versionNo"));
        tree.put("status", QbTrees.ACTIVE);
        tree.put("createdAt", ctx.opTime());
        tree.put("createdBy", ctx.request().actorId());
        tree.put("revision", BigDecimal.ONE);
        Object treeId = ctx.changes().insert(QbTrees.TREE, tree);
        Object versionId = insertVersion(ctx, treeId, 1, i18n(version.get("name")), i18n(version.get("description")));
        for (EntityInstance def : list(ctx, TEMPLATE_DEFS)) {
            insertDef(ctx, versionId, def.get("nodeId"), i18n(def.get("name")), i18n(def.get("description")),
                String.valueOf((Object) def.get("condition")), AccountEditing.number(def.get("seq")));
        }
        ctx.put(OUTPUT, new TreeOutput(String.valueOf(treeId), String.valueOf(versionId), 1, QbTrees.DRAFT, 1,
            Map.of(), List.of(), null, null));
    }

    private static void insertDef(ProcessContext ctx, Object versionId, Object nodeId, Map<String, String> name,
        Map<String, String> description, String condition, long seq) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("versionId", String.valueOf(versionId));
        def.put("nodeId", String.valueOf(nodeId));
        def.put("name", name);
        def.put("description", description == null || description.isEmpty() ? null : description);
        def.put("condition", condition);
        def.put("seq", BigDecimal.valueOf(seq));
        ctx.changes().insert(QbTrees.NODE_DEF, def);
    }

    // ---- the graph an edit acts on ---------------------------------------------------------------------------------

    /**
     * The steps every edit of a graph starts with: an operator's membership (held shared, when the caller signed in
     * at the operators' entry), the graph's lock, the graph with its open and in-effect versions, then whether the
     * caller may edit it (otherwise 404), then those versions' node definitions.
     */
    private static <I, O> void loadGraph(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        OperatorChecks.memberWhenScoped(pb);
        pb.step("Lock the graph", HoldLock.<ProcessContext>exclusive(ctx -> QbTrees.lockName(treeId(ctx))))
            .step("Load the graph and its versions", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> byIds("treeId", treeId(ctx)), TREE),
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.VERSION), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.In("treeId", ids(treeId(ctx))),
                        new QueryPredicate.In("status", List.of(QbTrees.DRAFT, QbTrees.REVIEW, QbTrees.REJECTED,
                            QbTrees.IN_EFFECT)))))
                    .limit(4).build(), VERSIONS)))
            .compute("Check that the graph is the caller's", (metadata, ctx) -> requireEditor(ctx))
            .step("Load the versions' nodes", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE_DEF), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("versionId", list(ctx, VERSIONS).stream().map(EntityInstance::id)
                        .map(String::valueOf).map(Object.class::cast).toList()))
                    .limit(Integer.MAX_VALUE).build(), DEFS),
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("treeId", ids(treeId(ctx)))).limit(Integer.MAX_VALUE).build(),
                    NODES)));
    }

    /** Whether the caller may edit the graph: its Sponsor, a member of its operator, or the platform's editor. */
    static void requireEditor(ProcessContext ctx) {
        EntityInstance tree = first(ctx, TREE);
        if (tree == null || !mayEdit(ctx, tree)) {
            throw new EntityNotFoundException(QbTrees.TREE + " " + treeId(ctx) + " not found");
        }
    }

    private static boolean mayEdit(ProcessContext ctx, EntityInstance tree) {
        String owner = tree.get("ownerId") == null ? null : String.valueOf((Object) tree.get("ownerId"));
        return switch (String.valueOf((Object) tree.get("ownerKind"))) {
            case QbTrees.SPONSOR -> owner.equals(ctx.request().actorId());
            case QbTrees.OPERATOR -> ctx.contains(OperatorChecks.OPERATOR_ID)
                && owner.equals(OperatorChecks.operatorId(ctx));
            case QbTrees.PLATFORM -> ctx.request().permissions().contains(QbPermissions.TREE_PLATFORM_WRITE);
            default -> false;
        };
    }

    private static void requirePlatform(ProcessContext ctx) {
        if (!ctx.request().permissions().contains(QbPermissions.TREE_PLATFORM_WRITE)) {
            throw new com.jabiz.runtime.PermissionDeniedException(QbPermissions.TREE_PLATFORM_WRITE, "The platform's templates are written by "
                + "holders of " + QbPermissions.TREE_PLATFORM_WRITE);
        }
    }

    private static EntityInstance version(ProcessContext ctx, String status) {
        return list(ctx, VERSIONS).stream().filter(v -> status.equals(v.get("status"))).findFirst().orElse(null);
    }

    /**
     * The version being worked on, if any: the draft, the one in review, or the one sent back (which stays the draft
     * to change and submit again; at most one of them exists).
     */
    private static EntityInstance open(ProcessContext ctx) {
        EntityInstance draft = version(ctx, QbTrees.DRAFT);
        if (draft == null) {
            draft = version(ctx, QbTrees.REVIEW);
        }
        return draft != null ? draft : version(ctx, QbTrees.REJECTED);
    }

    /** The version a submission sends: the draft, or the one sent back. */
    private static EntityInstance submittable(ProcessContext ctx) {
        EntityInstance draft = version(ctx, QbTrees.DRAFT);
        return draft != null ? draft : version(ctx, QbTrees.REJECTED);
    }

    private static List<EntityInstance> defsOf(ProcessContext ctx, EntityInstance version) {
        if (version == null) {
            return List.of();
        }
        String id = String.valueOf(version.id());
        return list(ctx, DEFS).stream().filter(def -> id.equals(String.valueOf((Object) def.get("versionId"))))
            .sorted(Comparator.comparingLong(def -> AccountEditing.number(def.get("seq")))).toList();
    }

    private static void current(ProcessContext ctx, Long baseRevision) {
        AccountEditing.current(ctx, first(ctx, TREE), baseRevision, TreeCodes.CHANGED);
        if (ctx.hasViolations()) {
            throw new BusinessRuleViolationException(ctx.violations());
        }
    }

    private static void requireActive(ProcessContext ctx) {
        if (!QbTrees.ACTIVE.equals(first(ctx, TREE).get("status"))) {
            throw rule(null, TreeCodes.STATE, "The graph is retired", Map.of("status", QbTrees.RETIRED));
        }
    }

    /** Raises the graph's revision (every change to it or its draft) and returns the new one. */
    private static long touch(ProcessContext ctx, Map<String, Object> more) {
        EntityInstance tree = first(ctx, TREE);
        long revision = AccountEditing.number(tree.get("revision")) + 1;
        Map<String, Object> values = new LinkedHashMap<>(more);
        values.put("revision", BigDecimal.valueOf(revision));
        ctx.changes().update(QbTrees.TREE, tree.id(), tree.version(), values);
        return revision;
    }

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, TreeOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, TreeOutput.class, ProcessContext.class, pb -> {
            pb.description("Saves a knowledge graph's draft (its name and every node) and gives every problem that "
                    + "keeps it from being submitted; a version in review goes back to its draft.")
                .permissions(QbPermissions.TREE_EDIT)
                .requiresVerifiedEmail()
                .contextFactory(TreeProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, TreeOutput.class));
            CallerChecks.refuseBanned(pb);
            pb.step("Draw the new nodes' ids", RandomBytesStep.class, new RandomBytesStep.Metadata(RANDOM, 32));
            loadGraph(pb);
            loadRules(pb);
            pb.compute("Write the draft", (metadata, ctx) -> saved(ctx))
                .step("Withdraw the review", WithdrawApproval.<ProcessContext>when(
                    ctx -> ctx.contains(WITHDRAW_REVIEW), TreeApprovals.SUBJECT,
                    TreeProcesses::reviewedVersionId));
        });
    }

    /**
     * The graphs in effect a draft may name nodes of (a Sponsor's draft, the platform's templates lit by Takers; a
     * template, the platform's other templates lit by the same; an operator's copy, the operator's other copies),
     * with their nodes' definitions, and the limits. Read in the process (not through a template a caller could run
     * with other parameters): what an operator reads of other graphs is only what its own draft may name.
     */
    private static <I, O> void loadRules(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the graphs in effect the draft may use", QueryEntities.<ProcessContext>of(
                QbTrees.defaultDataset(QbTrees.TREE), ctx -> {
                    EntityInstance tree = first(ctx, TREE);
                    List<QueryPredicate> where = new ArrayList<>();
                    where.add(new QueryPredicate.In("status", List.of(QbTrees.ACTIVE)));
                    switch (String.valueOf((Object) tree.get("ownerKind"))) {
                        case QbTrees.SPONSOR -> {
                            where.add(new QueryPredicate.In("ownerKind", List.of(QbTrees.PLATFORM)));
                            where.add(new QueryPredicate.In("subjectKind", List.of(QbTrees.TAKER)));
                        }
                        case QbTrees.PLATFORM -> {
                            where.add(new QueryPredicate.In("ownerKind", List.of(QbTrees.PLATFORM)));
                            where.add(new QueryPredicate.In("subjectKind", List.of((Object) tree.get("subjectKind"))));
                        }
                        default -> {
                            where.add(new QueryPredicate.In("ownerKind", List.of(QbTrees.OPERATOR)));
                            where.add(new QueryPredicate.In("ownerId", List.of((Object) tree.get("ownerId"))));
                        }
                    }
                    return EntityQuery.builder().where(new QueryPredicate.And(where)).limit(Integer.MAX_VALUE)
                        .build();
                }, GRAPHS))
            .step("Load their nodes", QueryEntities.<ProcessContext>of(QbTrees.defaultDataset(QbTrees.NODE_DEF),
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("versionId", otherGraphs(ctx).stream()
                        .map(graph -> graph.get("currentVersionId")).filter(java.util.Objects::nonNull)
                        .map(String::valueOf).map(Object.class::cast).toList()))
                    .limit(Integer.MAX_VALUE).build(), GRAPH_DEFS))
            .step("Load the limits", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.TREE_MAX_NODES, QbParams.TREE_MAX_CONDITIONS, QbParams.TREE_MAX_DEPTH));
    }

    /** The other graphs {@link #loadRules} found (never the draft's own: its draft replaces it). */
    private static List<EntityInstance> otherGraphs(ProcessContext ctx) {
        String own = String.valueOf(first(ctx, TREE).id());
        return list(ctx, GRAPHS).stream().filter(graph -> !own.equals(String.valueOf(graph.id()))).toList();
    }

    static void saved(ProcessContext ctx) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        current(ctx, input.baseRevision());
        requireActive(ctx);
        requireName(input.name());
        EntityInstance tree = first(ctx, TREE);
        // Ids for the new nodes, from one draw: the ids nobody chooses (a lit node is named by its id).
        Map<String, String> newIds = new LinkedHashMap<>();
        Set<String> known = new LinkedHashSet<>();
        list(ctx, NODES).forEach(node -> known.add(String.valueOf((Object) node.get("nodeId"))));
        defsOf(ctx, open(ctx)).forEach(def -> known.add(String.valueOf((Object) def.get("nodeId"))));
        byte[] seed = ctx.get(RANDOM, byte[].class);
        int counter = 0;
        List<String> order = new ArrayList<>();
        for (NodeInput node : input.nodes()) {
            String id;
            if (node.nodeId() != null && !node.nodeId().isBlank()) {
                id = node.nodeId();
                if (!known.contains(id)) {
                    throw rule("nodes", TreeCodes.CONDITION_INVALID, "Node " + id + " is not the graph's",
                        Map.of("node", id));
                }
            } else {
                if (node.key() == null || node.key().isBlank() || newIds.containsKey(node.key())) {
                    throw rule("nodes", TreeCodes.CONDITION_INVALID, "A new node is named by a key of its own in the "
                        + "save", Map.of());
                }
                id = derivedId(seed, counter++);
                newIds.put(node.key(), id);
            }
            if (order.contains(id)) {
                throw rule("nodes", TreeCodes.CONDITION_INVALID, "Node " + id + " is given twice", Map.of("node", id));
            }
            order.add(id);
        }
        Map<String, TreeCondition> conditions = new LinkedHashMap<>();
        for (int i = 0; i < input.nodes().size(); i++) {
            NodeInput node = input.nodes().get(i);
            requireName(node.name());
            TreeCondition condition;
            try {
                condition = TreeCondition.parse(renamed(node.condition(), newIds));
            } catch (IllegalArgumentException e) {
                throw rule("nodes[" + i + "].condition", TreeCodes.CONDITION_INVALID, e.getMessage(), Map.of());
            }
            conditions.put(order.get(i), condition);
        }
        List<Problem> problems = TreeRules.check(graph(tree, conditions), graphs(ctx), limits(ctx));

        // The draft: the open one (a version in review goes back to its draft), or a new one after the version in
        // effect.
        EntityInstance open = open(ctx);
        Object versionId;
        long versionNo;
        if (open == null) {
            EntityInstance active = version(ctx, QbTrees.IN_EFFECT);
            versionNo = active == null ? 1 : AccountEditing.number(active.get("versionNo")) + 1;
            versionId = insertVersion(ctx, tree.id(), versionNo, input.name(), input.description());
        } else {
            versionId = open.id();
            versionNo = AccountEditing.number(open.get("versionNo"));
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", input.name());
            values.put("description", input.description() == null || input.description().isEmpty() ? null
                : input.description());
            values.put("editedAt", ctx.opTime());
            if (QbTrees.REJECTED.equals(open.get("status"))) {
                values.put("status", QbTrees.DRAFT);
            } else if (QbTrees.REVIEW.equals(open.get("status"))) {
                values.put("status", QbTrees.DRAFT);
                values.put("approvalRequestId", null);
                values.put("contentHash", null);
                ctx.put(WITHDRAW_REVIEW, String.valueOf(open.id()));
            }
            ctx.changes().update(QbTrees.VERSION, open.id(), open.version(), values);
            for (EntityInstance def : defsOf(ctx, open)) {
                ctx.changes().delete(QbTrees.NODE_DEF, def.id(), def.version());
            }
        }
        for (int i = 0; i < input.nodes().size(); i++) {
            NodeInput node = input.nodes().get(i);
            insertDef(ctx, versionId, order.get(i), node.name(), node.description(),
                json(conditions.get(order.get(i)).toMap()), i);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> more = ctx.contains(TOUCH) ? (Map<String, Object>) ctx.get(TOUCH) : Map.of();
        long revision = touch(ctx, more);
        ctx.put(OUTPUT, new TreeOutput(String.valueOf(tree.id()), String.valueOf(versionId), versionNo,
            QbTrees.DRAFT, revision, Map.copyOf(newIds), views(problems), null, null));
    }

    /** The node id the {@code index}-th new node of a save gets: a UUID from the save's draw. */
    static String derivedId(byte[] seed, int index) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seed);
            digest.update(ByteBuffer.allocate(4).putInt(index).array());
            ByteBuffer bytes = ByteBuffer.wrap(digest.digest());
            long high = (bytes.getLong() & ~0xF000L) | 0x4000L;
            long low = (bytes.getLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
            return new UUID(high, low).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The condition with the save's keys of new nodes replaced by their ids. */
    @SuppressWarnings("unchecked")
    private static Object renamed(Object value, Map<String, String> newIds) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), "node".equals(key) && item != null
                && newIds.containsKey(String.valueOf(item)) ? newIds.get(String.valueOf(item)) : renamed(item, newIds)));
            return copy;
        }
        if (value instanceof List<?> items) {
            return items.stream().map(item -> renamed(item, newIds)).toList();
        }
        return value;
    }

    private static Graph graph(EntityInstance tree, Map<String, TreeCondition> nodes) {
        return new Graph(String.valueOf(tree.id()), !QbTrees.SPONSOR.equals(tree.get("ownerKind")),
            Subject.valueOf(String.valueOf((Object) tree.get("subjectKind"))), nodes);
    }

    /** The other graphs in effect {@link #loadRules} read, as the rules see them. */
    private static List<Graph> graphs(ProcessContext ctx) {
        Map<String, List<EntityInstance>> defs = new HashMap<>();
        for (EntityInstance def : list(ctx, GRAPH_DEFS)) {
            defs.computeIfAbsent(String.valueOf((Object) def.get("versionId")), id -> new ArrayList<>()).add(def);
        }
        List<Graph> graphs = new ArrayList<>();
        for (EntityInstance graph : otherGraphs(ctx)) {
            List<EntityInstance> nodes = defs.getOrDefault(String.valueOf((Object) graph.get("currentVersionId")),
                List.of());
            if (!nodes.isEmpty()) {
                graphs.add(graph(graph, conditionsOf(nodes)));
            }
        }
        return graphs;
    }

    private static Limits limits(ProcessContext ctx) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        return new Limits(QbParams.count(params, QbParams.TREE_MAX_NODES),
            QbParams.count(params, QbParams.TREE_MAX_CONDITIONS), QbParams.count(params, QbParams.TREE_MAX_DEPTH));
    }

    private static List<ProblemView> views(List<Problem> problems) {
        return problems.stream().map(p -> new ProblemView(p.nodeId(), p.code(), p.params())).toList();
    }

    /** The conditions of a version as stored. */
    private static Map<String, TreeCondition> conditionsOf(List<EntityInstance> defs) {
        Map<String, TreeCondition> conditions = new LinkedHashMap<>();
        for (EntityInstance def : defs) {
            conditions.put(String.valueOf((Object) def.get("nodeId")),
                TreeCondition.parse(parse(String.valueOf((Object) def.get("condition")))));
        }
        return conditions;
    }

    // ---- adopt -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AdoptInput, TreeOutput, ProcessContext> adopt() {
        return ProcessDefinition.define(ADOPT, 1, AdoptInput.class, TreeOutput.class, ProcessContext.class, pb -> {
            pb.description("Takes into an operator's copy, node by node, its template's version in effect: a node "
                    + "the template has as the template has it, one it no longer has out of the copy's draft.")
                .permissions(QbPermissions.TREE_EDIT)
                .requiresVerifiedEmail()
                .contextFactory(TreeProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, TreeOutput.class));
            pb.step("Draw", RandomBytesStep.class, new RandomBytesStep.Metadata(RANDOM, 32));
            loadGraph(pb);
            pb.step("Load the template", QueryEntities.<ProcessContext>of(QbTrees.defaultDataset(QbTrees.TREE),
                    ctx -> byIds("treeId", first(ctx, TREE).get("templateTreeId")), TEMPLATE))
                .step("Load the template's version in effect", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.VERSION), ctx -> byIds("versionId",
                        templateVersionId(ctx)), FIRST),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE_DEF), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("versionId", ids(templateVersionId(ctx))))
                        .limit(Integer.MAX_VALUE).build(), TEMPLATE_DEFS)));
            loadRules(pb);
            pb.compute("Take the template's nodes", (metadata, ctx) -> adopted(ctx))
                .step("Withdraw the review", WithdrawApproval.<ProcessContext>when(
                    ctx -> ctx.contains(WITHDRAW_REVIEW), TreeApprovals.SUBJECT, TreeProcesses::reviewedVersionId));
        });
    }

    static void adopted(ProcessContext ctx) {
        AdoptInput input = ctx.get(INPUT, AdoptInput.class);
        current(ctx, input.baseRevision());
        requireActive(ctx);
        EntityInstance tree = first(ctx, TREE);
        EntityInstance templateVersion = first(ctx, FIRST);
        if (!QbTrees.OPERATOR.equals(tree.get("ownerKind")) || templateVersion == null) {
            throw rule(null, TreeCodes.NOT_A_TEMPLATE, "Only an operator's copy takes its template's nodes",
                Map.of());
        }
        Map<String, EntityInstance> template = new LinkedHashMap<>();
        list(ctx, TEMPLATE_DEFS).forEach(def -> template.put(String.valueOf((Object) def.get("nodeId")), def));
        EntityInstance base = open(ctx) != null ? open(ctx) : version(ctx, QbTrees.IN_EFFECT);
        Map<String, Map<String, Object>> draft = new LinkedHashMap<>();
        for (EntityInstance def : defsOf(ctx, base)) {
            draft.put(String.valueOf((Object) def.get("nodeId")), fields(def));
        }
        for (String nodeId : input.nodeIds()) {
            EntityInstance taken = template.get(nodeId);
            if (taken == null) {
                draft.remove(nodeId);
            } else {
                draft.put(nodeId, fields(taken));
            }
        }
        // Written as a save of the whole draft would be.
        List<NodeInput> nodes = new ArrayList<>();
        draft.forEach((nodeId, def) -> nodes.add(new NodeInput(nodeId, null, i18n(def.get("name")),
            i18n(def.get("description")), parse(String.valueOf(def.get("condition"))))));
        ctx.put(INPUT, new SaveInput(input.treeId(), input.baseRevision(), i18n(base.get("name")),
            i18n(base.get("description")), nodes));
        // The copy has now taken the template up to its version in effect (what it took or left is in its versions),
        // written with the save's own change to the graph.
        ctx.put(TOUCH, Map.of("templateVersionNo", templateVersion.get("versionNo")));
        saved(ctx);
    }

    private static Map<String, Object> fields(EntityInstance def) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", def.get("name"));
        fields.put("description", def.get("description"));
        fields.put("condition", def.get("condition"));
        return fields;
    }

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<TreeInput, TreeOutput, ProcessContext> submit() {
        return ProcessDefinition.define(SUBMIT, 1, TreeInput.class, TreeOutput.class, ProcessContext.class, pb -> {
            pb.description("Submits a knowledge graph's draft for review: a Sponsor's to their operator, an "
                    + "operator's copy to another member of it, a template to another administrator.")
                .permissions(QbPermissions.TREE_EDIT)
                .requiresVerifiedEmail()
                .contextFactory(TreeProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, TreeOutput.class));
            CallerChecks.refuseBanned(pb);
            loadGraph(pb);
            loadRules(pb);
            pb.step("Load the Sponsor's operator", QueryEntities.<ProcessContext>of(
                    QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> {
                        EntityInstance tree = first(ctx, TREE);
                        return EntityQuery.builder().where(new QueryPredicate.In("userId",
                            QbTrees.SPONSOR.equals(tree.get("ownerKind")) ? List.of(String.valueOf(
                                (Object) tree.get("ownerId"))) : List.of())).limit(1).build();
                    }, ASSIGNMENT))
                .compute("Check the draft", (metadata, ctx) -> checkSubmit(ctx))
                .step("Ask for a review", RequireApproval.<ProcessContext>when(ctx -> ctx.contains(CASE),
                    TreeApprovals.SUBJECT, ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL))
                .compute("Submit", (metadata, ctx) -> submitted(ctx));
        });
    }

    static void checkSubmit(ProcessContext ctx) {
        current(ctx, ctx.get(INPUT, TreeInput.class).baseRevision());
        requireActive(ctx);
        EntityInstance draft = submittable(ctx);
        if (draft == null) {
            throw rule(null, TreeCodes.STATE, "The graph has no draft to submit", Map.of());
        }
        EntityInstance tree = first(ctx, TREE);
        List<EntityInstance> defs = defsOf(ctx, draft);
        List<Problem> problems = TreeRules.check(graph(tree, conditionsOf(defs)), graphs(ctx), limits(ctx));
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems.stream().map(p -> new Violation(
                p.nodeId() == null ? null : "nodes." + p.nodeId(), p.code(), "The draft has problems", p.params()))
                .toList());
        }
        ApprovalCase review = ApprovalCase.of(String.valueOf(draft.id()),
            TreeApprovals.facts(String.valueOf((Object) tree.get("ownerKind"))), TreeApprovals.content(draft, defs))
            .reference(TreeApprovals.reference(draft));
        String ownerKind = String.valueOf((Object) tree.get("ownerKind"));
        if (QbTrees.SPONSOR.equals(ownerKind)) {
            EntityInstance assignment = first(ctx, ASSIGNMENT);
            if (assignment == null) {
                throw new IllegalStateException("The Sponsor has no operator");
            }
            review = review.scope(QbOperators.SCOPE, String.valueOf((Object) assignment.get("operatorId")));
        } else if (QbTrees.OPERATOR.equals(ownerKind)) {
            review = review.scope(QbOperators.SCOPE, String.valueOf((Object) tree.get("ownerId")));
        }
        ctx.put(CASE, review);
    }

    static void submitted(ProcessContext ctx) {
        EntityInstance draft = submittable(ctx);
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("submittedAt", ctx.opTime());
        values.put("submittedBy", ctx.request().actorId());
        values.put("contentHash", TreeApprovals.hash(TreeApprovals.content(draft, defsOf(ctx, draft))));
        switch (approval.status()) {
            case PENDING -> {
                values.put("status", QbTrees.REVIEW);
                values.put("approvalRequestId", approval.requestId());
            }
            case NOT_REQUIRED -> throw rule(null, ReviewCodes.REVIEW_NOT_CONFIGURED, "Knowledge graphs need a "
                + "review, but no review rule is in effect", Map.of("subject", TreeApprovals.SUBJECT));
            case APPROVED -> {
                ctx.changes().update(QbTrees.VERSION, draft.id(), draft.version(), values);
                activate(ctx, draft, approval.requestId());
                return;
            }
            default -> throw new IllegalStateException("Unknown approval outcome " + approval.status());
        }
        ctx.changes().update(QbTrees.VERSION, draft.id(), draft.version(), values);
        long revision = touch(ctx, Map.of());
        ctx.put(OUTPUT, output(ctx, draft, QbTrees.REVIEW, revision, approval.status().name(),
            approval.requestId()));
    }

    private static TreeOutput output(ProcessContext ctx, EntityInstance version, String status, long revision,
        String approval, String requestId) {
        return new TreeOutput(String.valueOf(first(ctx, TREE).id()), String.valueOf(version.id()),
            AccountEditing.number(version.get("versionNo")), status, revision, Map.of(), List.of(), approval,
            requestId);
    }

    /**
     * Puts a version in effect: the one before is superseded, and the nodes' rows say which are in it, what each
     * needs lit before answering and whether publications may be bound to it.
     */
    static void activate(ProcessContext ctx, EntityInstance version, String requestId) {
        EntityInstance tree = first(ctx, TREE);
        EntityInstance before = version(ctx, QbTrees.IN_EFFECT);
        if (before != null && !before.id().equals(version.id())) {
            ctx.changes().update(QbTrees.VERSION, before.id(), before.version(), Map.of("status",
                QbTrees.SUPERSEDED));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbTrees.IN_EFFECT);
        values.put("decidedAt", ctx.opTime());
        values.put("approvalRequestId", requestId);
        ctx.changes().update(QbTrees.VERSION, version.id(), version.version(), values);
        Map<String, TreeCondition> conditions = conditionsOf(defsOf(ctx, version));
        Map<String, EntityInstance> rows = new HashMap<>();
        list(ctx, NODES).forEach(node -> rows.put(String.valueOf((Object) node.get("nodeId")), node));
        long versionNo = AccountEditing.number(version.get("versionNo"));
        for (Map.Entry<String, TreeCondition> node : conditions.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("active", true);
            List<String> beforeAnswer = TreeRules.beforeAnswer(node.getValue());
            row.put("beforeAnswer", beforeAnswer.isEmpty() ? null : json(beforeAnswer));
            row.put("bindable", TreeRules.asksForAPass(node.getValue()));
            EntityInstance existing = rows.remove(node.getKey());
            if (existing == null) {
                row.put("treeId", String.valueOf(tree.id()));
                row.put("nodeId", node.getKey());
                row.put("createdInVersion", BigDecimal.valueOf(versionNo));
                ctx.changes().insert(QbTrees.NODE, row);
            } else {
                ctx.changes().update(QbTrees.NODE, existing.id(), existing.version(), row);
            }
        }
        // Nodes the version no longer has keep their rows (lit records name them), out of effect.
        for (EntityInstance gone : rows.values()) {
            if (Boolean.TRUE.equals(gone.get("active"))) {
                ctx.changes().update(QbTrees.NODE, gone.id(), gone.version(), Map.of("active", false,
                    "bindable", false));
            }
        }
        long revision = touch(ctx, Map.of("currentVersionId", String.valueOf(version.id())));
        ctx.put(OUTPUT, output(ctx, version, QbTrees.IN_EFFECT, revision, ApprovalOutcome.Status.APPROVED.name(),
            requestId));
    }

    // ---- withdraw --------------------------------------------------------------------------------------------------

    public static ProcessDefinition<TreeInput, TreeOutput, ProcessContext> withdraw() {
        return ProcessDefinition.define(WITHDRAW, 1, TreeInput.class, TreeOutput.class, ProcessContext.class, pb -> {
            pb.description("Takes a knowledge graph's version back from review to its draft.")
                .permissions(QbPermissions.TREE_EDIT)
                .requiresVerifiedEmail()
                .contextFactory(TreeProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, TreeOutput.class));
            loadGraph(pb);
            pb.compute("Take the version back", (metadata, ctx) -> {
                    current(ctx, ctx.get(INPUT, TreeInput.class).baseRevision());
                    EntityInstance review = version(ctx, QbTrees.REVIEW);
                    if (review == null) {
                        throw rule(null, TreeCodes.STATE, "No version of the graph is in review", Map.of());
                    }
                    ctx.changes().update(QbTrees.VERSION, review.id(), review.version(), Map.of("status",
                        QbTrees.DRAFT, "editedAt", ctx.opTime()));
                    ctx.put(WITHDRAW_REVIEW, String.valueOf(review.id()));
                    ctx.put(OUTPUT, output(ctx, review, QbTrees.DRAFT, touch(ctx, Map.of()), null, null));
                })
                .step("Withdraw the review", WithdrawApproval.<ProcessContext>when(
                    ctx -> ctx.contains(WITHDRAW_REVIEW), TreeApprovals.SUBJECT, TreeProcesses::reviewedVersionId));
        });
    }

    private static Object reviewedVersionId(ProcessContext ctx) {
        return ctx.get(WITHDRAW_REVIEW);
    }

    // ---- retire ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<TreeInput, TreeOutput, ProcessContext> retire() {
        return ProcessDefinition.define(RETIRE, 1, TreeInput.class, TreeOutput.class, ProcessContext.class, pb -> {
            pb.description("Retires a knowledge graph: it is shown to nobody new and none of its nodes is lit any "
                    + "more; a version in review is withdrawn.")
                .permissions(QbPermissions.TREE_EDIT)
                .requiresVerifiedEmail()
                .contextFactory(TreeProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, TreeOutput.class));
            loadGraph(pb);
            pb.compute("Retire the graph", (metadata, ctx) -> {
                    current(ctx, ctx.get(INPUT, TreeInput.class).baseRevision());
                    requireActive(ctx);
                    EntityInstance review = version(ctx, QbTrees.REVIEW);
                    if (review != null) {
                        ctx.changes().update(QbTrees.VERSION, review.id(), review.version(), Map.of("status",
                            QbTrees.DRAFT, "editedAt", ctx.opTime()));
                        ctx.put(WITHDRAW_REVIEW, String.valueOf(review.id()));
                    }
                    long revision = touch(ctx, Map.of("status", QbTrees.RETIRED, "retiredAt", ctx.opTime()));
                    EntityInstance active = version(ctx, QbTrees.IN_EFFECT);
                    EntityInstance shown = active != null ? active : open(ctx);
                    ctx.put(OUTPUT, new TreeOutput(String.valueOf(first(ctx, TREE).id()),
                        shown == null ? null : String.valueOf(shown.id()),
                        shown == null ? 0 : AccountEditing.number(shown.get("versionNo")), QbTrees.RETIRED, revision,
                        Map.of(), List.of(), null, null));
                })
                .step("Withdraw the review", WithdrawApproval.<ProcessContext>when(
                    ctx -> ctx.contains(WITHDRAW_REVIEW), TreeApprovals.SUBJECT, TreeProcesses::reviewedVersionId));
        });
    }

    // ---- approval result -------------------------------------------------------------------------------------------

    /**
     * Continues after a review is decided (run as the system for both approval events). Only a version's own request
     * counts: the event must name the request the version, still in review, waits for, and the stored request must
     * be bound to the version as stored. An approval puts it in effect; a rejection sends it back with the reviewer's
     * reason. Anything else changes nothing.
     */
    public static ProcessDefinition<ApprovalResultInput, TreeOutput, ProcessContext> approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, TreeOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Puts a knowledge graph's version in effect or sends it back after its review is "
                        + "decided.")
                    .permissions(QbPermissions.TREE_SYSTEM)
                    .internal()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, TreeOutput.class)
                        : new TreeOutput(null, null, 0, null, 0, Map.of(), List.of(), null, null));
                // Events of other subjects name no version: they find nothing and lock nothing.
                pb.step("Find the version", QueryEntities.<ProcessContext>of(QbTrees.defaultDataset(QbTrees.VERSION),
                        ctx -> byIds("versionId", TreeApprovals.SUBJECT.equals(result(ctx).subject())
                            ? result(ctx).entityId() : null), FIRST))
                    .compute("Know the graph", (metadata, ctx) -> {
                        EntityInstance version = first(ctx, FIRST);
                        if (version != null) {
                            ctx.put(TREE_ID, String.valueOf((Object) version.get("treeId")));
                        }
                    })
                    .step("Lock the graph", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbTrees.lockName(ctx.contains(TREE_ID) ? ctx.get(TREE_ID) : null)))
                    .step("Load the graph and its versions", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> byIds("treeId",
                            ctx.contains(TREE_ID) ? ctx.get(TREE_ID) : null), TREE),
                        QueryEntities.query(QbTrees.defaultDataset(QbTrees.VERSION), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(new QueryPredicate.In("treeId",
                                    ids(ctx.contains(TREE_ID) ? ctx.get(TREE_ID) : null)),
                                new QueryPredicate.In("status", List.of(QbTrees.DRAFT, QbTrees.REVIEW,
                                    QbTrees.REJECTED, QbTrees.IN_EFFECT)))))
                            .limit(4).build(), VERSIONS)))
                    .step("Load the versions' nodes", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE_DEF), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("versionId", list(ctx, VERSIONS).stream()
                                .map(EntityInstance::id).map(String::valueOf).map(Object.class::cast).toList()))
                            .limit(Integer.MAX_VALUE).build(), DEFS),
                        QueryEntities.query(QbTrees.defaultDataset(QbTrees.NODE), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("treeId", ids(ctx.contains(TREE_ID) ? ctx.get(TREE_ID)
                                : null))).limit(Integer.MAX_VALUE).build(), NODES),
                        QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("requestId", ownRequest(ctx)
                                ? AccountEditing.uuids(result(ctx).requestId()) : new ArrayList<>()))
                            .limit(1).build(), REQUEST),
                        QueryEntities.query(ApprovalEntities.DECISION_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("requestId", ownRequest(ctx)
                                ? AccountEditing.uuids(result(ctx).requestId()) : new ArrayList<>()))
                            .limit(Integer.MAX_VALUE).build(), DECISIONS)))
                    .step("Check the approval", RequireApproval.<ProcessContext>when(
                        ctx -> boundRequest(ctx) && ApprovalEntities.APPROVED.equals(result(ctx).status()),
                        TreeApprovals.SUBJECT, ctx -> storedCase(ctx).preparedBy(result(ctx).preparerId()),
                        APPROVAL))
                    .compute("Put in effect or send back", (metadata, ctx) -> approvalResult(ctx));
            });
    }

    private static ApprovalResultInput result(ProcessContext ctx) {
        return ctx.get(INPUT, ApprovalResultInput.class);
    }

    private static EntityInstance reviewed(ProcessContext ctx) {
        EntityInstance found = first(ctx, FIRST);
        EntityInstance review = version(ctx, QbTrees.REVIEW);
        return found != null && review != null && review.id().equals(found.id()) ? review : null;
    }

    /** Whether the event is about the request the version, still in review, waits for. */
    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance review = reviewed(ctx);
        return review != null && result(ctx).requestId() != null
            && result(ctx).requestId().equals(review.get("approvalRequestId"));
    }

    private static boolean storedRequest(ProcessContext ctx) {
        EntityInstance request = first(ctx, REQUEST);
        return ownRequest(ctx) && request != null && TreeApprovals.SUBJECT.equals(request.get("subject"))
            && String.valueOf(reviewed(ctx).id()).equals(request.get("entityId"));
    }

    /** Whether the stored request is bound to the version as stored. */
    private static boolean boundRequest(ProcessContext ctx) {
        return storedRequest(ctx) && TreeApprovals.hash(storedCase(ctx).content())
            .equals(first(ctx, REQUEST).get("contentHash"));
    }

    private static ApprovalCase storedCase(ProcessContext ctx) {
        EntityInstance review = reviewed(ctx);
        return ApprovalCase.of(String.valueOf(review.id()),
            TreeApprovals.facts(String.valueOf((Object) first(ctx, TREE).get("ownerKind"))),
            TreeApprovals.content(review, defsOf(ctx, review)));
    }

    static void approvalResult(ProcessContext ctx) {
        EntityInstance review = reviewed(ctx);
        if (review == null || !boundRequest(ctx)) {
            return;
        }
        if (ApprovalEntities.APPROVED.equals(result(ctx).status())) {
            ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
            if (approval != null && approval.status() == ApprovalOutcome.Status.APPROVED) {
                activate(ctx, review, result(ctx).requestId());
            }
            return;
        }
        EntityInstance request = first(ctx, REQUEST);
        if (ApprovalEntities.REJECTED.equals(result(ctx).status())
            && ApprovalEntities.REJECTED.equals(request.get("status"))) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", QbTrees.REJECTED);
            values.put("decidedAt", ctx.opTime());
            values.put("rejectReason", rejectReason(ctx));
            ctx.changes().update(QbTrees.VERSION, review.id(), review.version(), values);
            ctx.put(OUTPUT, output(ctx, review, QbTrees.REJECTED, touch(ctx, Map.of()), null, null));
        }
    }

    /** The reviewer's reason, from their decision (not from the event). */
    private static String rejectReason(ProcessContext ctx) {
        return list(ctx, DECISIONS).stream().filter(d -> ApprovalEntities.REJECTED.equals(d.get("decision"))
            || "REJECT".equals(d.get("decision"))).map(d -> (String) d.get("reason")).filter(r -> r != null)
            .findFirst().orElse(null);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        if (input instanceof SaveInput save) {
            ctx.put(TREE_ID, save.treeId());
        } else if (input instanceof TreeInput tree) {
            ctx.put(TREE_ID, tree.treeId());
        } else if (input instanceof AdoptInput adopt) {
            ctx.put(TREE_ID, adopt.treeId());
        }
        return ctx;
    }

    static Object treeId(ProcessContext ctx) {
        return ctx.contains(TREE_ID) ? ctx.get(TREE_ID) : null;
    }

    private static void requireName(Map<String, String> name) {
        if (name == null || name.values().stream().allMatch(text -> text == null || text.isBlank())) {
            throw rule("name", TreeCodes.NAME_REQUIRED, "A name in at least one language is required", Map.of());
        }
    }

    private static BusinessRuleViolationException rule(String field, String code, String message,
        Map<String, Object> params) {
        return new BusinessRuleViolationException(new Violation(field, code, message, params));
    }

    static EntityQuery byIds(String field, Object id) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, ids(id))).limit(1).build();
    }

    static List<Object> ids(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            ids.add(String.valueOf(id));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> i18n(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, String> copy = new LinkedHashMap<>();
            map.forEach((key, text) -> copy.put(String.valueOf(key), text == null ? null : String.valueOf(text)));
            return copy;
        }
        return JSON.readValue(String.valueOf(value), new TypeReference<Map<String, String>>() {});
    }

    static Map<String, Object> parse(String json) {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    static String json(Object value) {
        return new String(JSON.writeValueAsBytes(value), StandardCharsets.UTF_8);
    }
}
