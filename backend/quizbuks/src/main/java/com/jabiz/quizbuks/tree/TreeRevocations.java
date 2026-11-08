package com.jabiz.quizbuks.tree;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Revoking a lit node (F-S2b2, the spec's correction of lighting): {@value #REVOKE} records a request with its reason
 * (by an operator's member for their Sponsors' graphs and their copies, by the platform for any graph) and asks another
 * member, or another administrator, to approve it (subject {@value #SUBJECT}, rule {@value #RULE} proposed by
 * {@code QB_SETUP}; the request is in the operator's scope, as the graph's review is). Nothing is revoked before the
 * approval: {@value #APPROVAL_RESULT} (run as the system for both approval events) then writes the append-only
 * revocation under the subject's lighting lock and tells the person ({@value #REVOKED_EVENT}); a rejection keeps the
 * lit record and records the reviewer's reason. One request in review per lit record.
 */
public final class TreeRevocations {

    public static final String REVOKE = "QB_TREE_LIT_REVOKE";
    public static final String APPROVAL_RESULT = "QB_TREE_LIT_REVOKE_APPROVAL_RESULT";
    /** The approval subject; its name in each language is the message {@code approval.subject.qb.tree-lit}. */
    public static final String SUBJECT = "qb.tree-lit";
    public static final String RULE = "qb.tree.lit-revoke.review";
    /** A lit record revoked: the person's message. */
    public static final String REVOKED_EVENT = "qb.tree.lit-revoked";

    static final String INPUT = "revoke.input";
    static final String OUTPUT = "revoke.output";
    static final String LIT = "revoke.lit";
    static final String REVOKED = "revoke.revocation";
    static final String OPEN = "revoke.open";
    static final String TREE = "revoke.tree";
    static final String ASSIGNMENT = "revoke.assignment";
    static final String RULES = "revoke.rules";
    static final String NEW_ID = "revoke.newId";
    static final String ROW = "revoke.row";
    static final String APPROVAL = "revoke.approval";
    static final String FOUND = "revoke.found";
    static final String REQUEST = "revoke.request";
    static final String DECIDED = "revoke.decided";

    private TreeRevocations() {}

    /**
     * @param litId  the lit record to revoke
     * @param reason why, as the person will read it
     */
    public record RevokeInput(@NotBlank @Size(max = QbTrees.ID_LENGTH) String litId,
        @NotBlank @Size(max = QbTrees.REASON_LENGTH) String reason) {}

    /** The request and its state: {@code REVIEW} until decided, then {@code REVOKED} or {@code REJECTED}. */
    public record RevokeOutput(String requestId, String litId, String status, String approvalRequestId) {}

    static ApprovalSubject subject() {
        return ApprovalSubject.define(SUBJECT, s -> s.entity(QbTrees.REVOKE_REQUEST).bool("revoke"));
    }

    // ---- the request -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<RevokeInput, RevokeOutput, ProcessContext> revoke() {
        return ProcessDefinition.define(REVOKE, 1, RevokeInput.class, RevokeOutput.class, ProcessContext.class, pb -> {
            pb.description("Asks another member to approve revoking a node a person lit; nothing is revoked before "
                    + "it.")
                .permissions(QbPermissions.TREE_LIT_REVOKE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, RevokeOutput.class));
            CallerChecks.refuseBanned(pb);
            OperatorChecks.memberWhenScoped(pb);
            pb.step("Load the lit record, its revocation and requests", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.LIT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("litId", uuids(input(ctx).litId()))).limit(1).build(), LIT),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOCATION), ctx -> byId("litId",
                        input(ctx).litId()), REVOKED),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOKE_REQUEST), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("litId", List.of(input(ctx).litId())),
                            new QueryPredicate.Eq("status", QbTrees.REVOKE_REVIEW)))).limit(1).build(), OPEN),
                    QueryEntities.query(ApprovalEntities.RULE_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("subject", SUBJECT),
                            new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES)))
                .step("Load the graph", QueryEntities.<ProcessContext>of(QbTrees.defaultDataset(QbTrees.TREE),
                    ctx -> byId("treeId", first(ctx, LIT) == null ? null : first(ctx, LIT).get("treeId")), TREE))
                .step("Load the Sponsor's operator", QueryEntities.<ProcessContext>of(
                    QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> {
                        EntityInstance tree = first(ctx, TREE);
                        return EntityQuery.builder().where(new QueryPredicate.In("userId", tree != null
                            && QbTrees.SPONSOR.equals(tree.get("ownerKind"))
                            ? List.of(String.valueOf((Object) tree.get("ownerId"))) : List.of())).limit(1).build();
                    }, ASSIGNMENT))
                .compute("Check the request", (metadata, ctx) -> check(ctx))
                .compute("Record the request", (metadata, ctx) -> insert(ctx))
                // Written first, so that the approval names it and binds what is stored.
                .step("Save the request", SaveChanges.now())
                .step("Read the request", QueryEntities.<ProcessContext>of(
                    QbTrees.defaultDataset(QbTrees.REVOKE_REQUEST), ctx -> byId("requestId",
                        ctx.get(NEW_ID, String.class)), ROW))
                .step("Ask for another member's approval", RequireApproval.<ProcessContext>of(SUBJECT,
                    ctx -> approvalCase(first(ctx, ROW)), APPROVAL))
                .compute("Wait for the approval", (metadata, ctx) -> waitForApproval(ctx));
        });
    }

    /** The operator whose members handle the graph's lighting: the Sponsor's, or the copy's; none for the platform's. */
    static String operatorOf(EntityInstance tree, EntityInstance assignment) {
        if (QbTrees.SPONSOR.equals(tree.get("ownerKind"))) {
            return assignment == null ? null : String.valueOf((Object) assignment.get("operatorId"));
        }
        if (QbTrees.OPERATOR.equals(tree.get("ownerKind"))) {
            return String.valueOf((Object) tree.get("ownerId"));
        }
        return null;
    }

    static void check(ProcessContext ctx) {
        EntityInstance lit = first(ctx, LIT);
        EntityInstance tree = first(ctx, TREE);
        if (lit == null || tree == null) {
            throw new EntityNotFoundException(QbTrees.LIT + " " + input(ctx).litId() + " not found");
        }
        String operator = operatorOf(tree, first(ctx, ASSIGNMENT));
        com.jabiz.context.EntryScope scope = ctx.request().entryScope();
        if (scope != null && scope.is(QbOperators.SCOPE) && !Objects.equals(operator, scope.value())) {
            // An operator's member handles only their own Sponsors' graphs and copies; the platform's are its own.
            throw new EntityNotFoundException(QbTrees.LIT + " " + input(ctx).litId() + " not found");
        }
        List<Violation> problems = new ArrayList<>();
        if (first(ctx, REVOKED) != null) {
            problems.add(new Violation("litId", TreeCodes.ALREADY_REVOKED, "The lit record is revoked already",
                Map.of()));
        }
        if (first(ctx, OPEN) != null) {
            problems.add(new Violation("litId", TreeCodes.REVOKE_PENDING, "A request to revoke it is in review",
                Map.of()));
        }
        if (Objects.equals(String.valueOf((Object) lit.get("subjectId")), String.valueOf(
            (Object) ctx.request().actorId()))) {
            problems.add(new Violation("litId", TreeCodes.REVOKE_OWN, "One's own lighting is revoked by others",
                Map.of()));
        }
        if (first(ctx, RULES) == null) {
            problems.add(noReview());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    private static Violation noReview() {
        return new Violation(null, com.jabiz.quizbuks.setup.ReviewCodes.REVIEW_NOT_CONFIGURED, "No approval rule asks "
            + "for a second person on revoking lit nodes", Map.of("subject", SUBJECT));
    }

    static void insert(ProcessContext ctx) {
        EntityInstance lit = first(ctx, LIT);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("litId", String.valueOf(lit.id()));
        values.put("subjectKind", lit.get("subjectKind"));
        values.put("subjectId", lit.get("subjectId"));
        values.put("treeId", lit.get("treeId"));
        values.put("nodeId", lit.get("nodeId"));
        values.put("operatorId", operatorOf(first(ctx, TREE), first(ctx, ASSIGNMENT)));
        values.put("reason", input(ctx).reason().strip());
        values.put("status", QbTrees.REVOKE_REVIEW);
        values.put("requestedBy", ctx.request().actorId());
        values.put("requestedAt", ctx.opTime());
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(QbTrees.REVOKE_REQUEST, values)));
    }

    static void waitForApproval(ProcessContext ctx) {
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval == null || approval.status() != ApprovalOutcome.Status.PENDING) {
            // The rule's condition was changed so that it no longer applies: never done by one person.
            throw new BusinessRuleViolationException(List.of(noReview()));
        }
        EntityInstance row = first(ctx, ROW);
        ctx.changes().update(QbTrees.REVOKE_REQUEST, row.id(), row.version(), Map.of("approvalRequestId",
            approval.requestId()));
        ctx.put(OUTPUT, new RevokeOutput(String.valueOf(row.id()), (String) row.get("litId"), QbTrees.REVOKE_REVIEW,
            approval.requestId()));
    }

    /** The case: the content approved, from the stored request; in the operator's scope when it has one. */
    static ApprovalCase approvalCase(EntityInstance row) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("revoke", true);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestId", String.valueOf(row.id()));
        content.put("litId", row.get("litId"));
        content.put("subjectKind", row.get("subjectKind"));
        content.put("subjectId", row.get("subjectId"));
        content.put("treeId", row.get("treeId"));
        content.put("nodeId", row.get("nodeId"));
        content.put("reason", row.get("reason"));
        ApprovalCase review = ApprovalCase.of(String.valueOf(row.id()), facts, content)
            .preparedBy(row.get("requestedBy")).reference((String) row.get("nodeId"));
        Object operator = row.get("operatorId");
        return operator == null ? review : review.scope(QbOperators.SCOPE, String.valueOf(operator));
    }

    // ---- the approval -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<TreeProcesses.ApprovalResultInput, RevokeOutput, ProcessContext> approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, TreeProcesses.ApprovalResultInput.class,
            RevokeOutput.class, ProcessContext.class, pb -> pb
                .description("Revokes the lit node once another member approved the request, or keeps it when they "
                    + "rejected it.")
                .permissions(QbPermissions.TREE_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, RevokeOutput.class)
                    : new RevokeOutput(null, null, null, null))
                // Events of other subjects name no request: they find nothing and lock nothing.
                .step("Find the request", QueryEntities.<ProcessContext>of(
                    QbTrees.defaultDataset(QbTrees.REVOKE_REQUEST), ctx -> byId("requestId",
                        SUBJECT.equals(result(ctx).subject()) ? result(ctx).entityId() : null), FOUND))
                .step("Lock the subject's lighting", HoldLock.<ProcessContext>exclusive(ctx -> {
                    EntityInstance found = first(ctx, FOUND);
                    return found == null ? null : QbTrees.litLockName(found.get("subjectKind"),
                        found.get("subjectId"));
                }))
                .step("Read the request, the revocation and the approval", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOKE_REQUEST), ctx -> byId("requestId",
                        first(ctx, FOUND) == null ? null : String.valueOf(first(ctx, FOUND).id())), ROW),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOCATION), ctx -> byId("litId",
                        first(ctx, FOUND) == null ? null : first(ctx, FOUND).get("litId")), REVOKED),
                    QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", foundRequest(ctx))).limit(1).build(), REQUEST)))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(result(ctx).status()), SUBJECT,
                    ctx -> approvalCase(first(ctx, ROW)).preparedBy(result(ctx).preparerId()), APPROVAL))
                .compute("Decide", (metadata, ctx) -> decided(ctx))
                .step("Tell the person", PublishEvent.<ProcessContext>when(
                    ctx -> QbTrees.REVOKE_DONE.equals(ctx.contains(DECIDED) ? ctx.get(DECIDED) : null),
                    REVOKED_EVENT, TreeRevocations::revokedPayload)));
    }

    private static TreeProcesses.ApprovalResultInput result(ProcessContext ctx) {
        return ctx.get(INPUT, TreeProcesses.ApprovalResultInput.class);
    }

    /** The approval request named by the event, when the lookup found a request in review for it. */
    private static List<Object> foundRequest(ProcessContext ctx) {
        EntityInstance found = first(ctx, FOUND);
        String requestId = result(ctx).requestId();
        return found != null && requestId != null && requestId.equals(found.get("approvalRequestId"))
            ? new ArrayList<>(List.of(requestId)) : new ArrayList<>();
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        String requestId = result(ctx).requestId();
        return row != null && QbTrees.REVOKE_REVIEW.equals(row.get("status")) && requestId != null
            && requestId.equals(row.get("approvalRequestId"));
    }

    static void decided(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance row = first(ctx, ROW);
        TreeProcesses.ApprovalResultInput input = result(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("decidedAt", ctx.opTime());
        String status;
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval == null || approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                return;
            }
            if (first(ctx, REVOKED) == null) {
                Map<String, Object> revocation = new LinkedHashMap<>();
                revocation.put("litId", row.get("litId"));
                revocation.put("subjectKind", row.get("subjectKind"));
                revocation.put("subjectId", row.get("subjectId"));
                revocation.put("reason", row.get("reason"));
                revocation.put("requestedBy", row.get("requestedBy"));
                revocation.put("approvalRequestId", input.requestId());
                revocation.put("revokedAt", ctx.opTime());
                ctx.changes().insert(QbTrees.REVOCATION, revocation);
            }
            status = QbTrees.REVOKE_DONE;
        } else if (ApprovalEntities.REJECTED.equals(input.status())) {
            status = QbTrees.REVOKE_REJECTED;
            values.put("rejectReason", input.reason());
        } else {
            return;
        }
        values.put("status", status);
        ctx.changes().update(QbTrees.REVOKE_REQUEST, row.id(), row.version(), values);
        ctx.put(DECIDED, status);
        ctx.put(OUTPUT, new RevokeOutput(String.valueOf(row.id()), (String) row.get("litId"), status,
            input.requestId()));
    }

    private static Map<String, Object> revokedPayload(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", String.valueOf(row.id()));
        payload.put("litId", row.get("litId"));
        payload.put("subjectKind", row.get("subjectKind"));
        payload.put("userId", row.get("subjectId"));
        payload.put("treeId", row.get("treeId"));
        payload.put("nodeId", row.get("nodeId"));
        return payload;
    }

    static TreeProcesses.ApprovalResultInput resultInput(DomainEvent event) {
        return TreeApprovals.resultInput(event);
    }

    private static RevokeInput input(ProcessContext ctx) {
        return ctx.get(INPUT, RevokeInput.class);
    }

    /** The lit record's key, a UUID; none for text that is not one (nothing is found). */
    private static List<Object> uuids(String id) {
        try {
            return id == null ? List.of() : List.of(java.util.UUID.fromString(id.strip()));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private static EntityQuery byId(String field, Object id) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, id == null ? List.of()
            : List.of(String.valueOf(id)))).limit(1).build();
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null || found.isEmpty() ? null : found.get(0);
    }
}
