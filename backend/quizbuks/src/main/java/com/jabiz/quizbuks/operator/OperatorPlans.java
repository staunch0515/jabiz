package com.jabiz.quizbuks.operator;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.TransferProcesses;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The operators' plans (docs/quizbuks/plans/Q48e-operator-isolation.md, Q48e-2): five plans of a monthly fixed fee and
 * the platform's share of the commission (controlled parameters {@code qb.operator.plan.<n>.fee|share}); each operator
 * is on one. Headquarters proposes a change of plan from a later month, and another administrator approves it
 * ({@value #APPROVAL_SUBJECT}); a month is settled on the last approved plan in effect by then, plan 1 without any.
 */
public final class OperatorPlans {

    public static final String PLAN = "QbOperatorPlan";
    public static final String SET = "QB_OPERATOR_PLAN_SET";
    public static final String APPROVAL_RESULT = "QB_OPERATOR_PLAN_APPROVAL_RESULT";
    public static final String APPROVAL_SUBJECT = "qb.operator-plan";
    public static final String REVIEW_RULE = "qb.operator-plan.review";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:operator-plan-status";
    public static final String PLAN_DICTIONARY = "urn:jabiz:dict:quizbuks:operator-plan";

    /** The plans, by their number; a new operator is on the first. */
    public static final List<String> PLANS = List.of("1", "2", "3", "4", "5");
    public static final String DEFAULT_PLAN = "1";

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final List<String> STATUSES = List.of(PENDING, APPROVED, REJECTED);

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    public static final EntityDefinition PLAN_ENTITY = EntityDefinition.define(PLAN, eb -> {
        eb.physicalTable("qb_operator_plan");
        eb.primaryKey("planChangeId");
        eb.field("planChangeId", f -> f.physicalColumn("plan_change_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:operator-plan"));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true)
            .asReference(QbOperators.OPERATOR));
        eb.field("plan", f -> f.physicalColumn("plan").immutable(true).required(true)
            .asCode(PLAN_DICTIONARY, PLANS.toArray(String[]::new)));
        eb.field("effectiveMonth", f -> f.physicalColumn("effective_month").immutable(true).required(true)
            .asText(7));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).asText(500));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("proposedBy", f -> f.physicalColumn("proposed_by").immutable(true).required(true).asText(128));
        eb.field("proposedAt", f -> f.physicalColumn("proposed_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").asText(36));
        eb.field("decidedAt", f -> f.physicalColumn("decided_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("operatorId", "plan", "effectiveMonth", "status", "proposedAt", "decidedAt")
            .filters("operatorId", "status")
            .sorts("effectiveMonth", "proposedAt")
            .defaultSort("proposedAt", false));
    });

    /**
     * @param effectiveMonth {@code yyyy-MM} (Japan's), after the month of the proposal: a month already begun is
     *                       settled on the plan it began with
     */
    public record SetInput(@NotBlank @Size(max = 36) String operatorId,
        @NotBlank @Pattern(regexp = "[1-5]") String plan,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String effectiveMonth,
        @NotBlank @Size(max = 500) String reason) {}

    public record PlanOutput(String planChangeId, String status, String approvalRequestId) {}

    static final String INPUT = "input";
    static final String OPERATOR = "operator";
    static final String CHANGES = "changes";
    static final String FOUND = "found";
    static final String ROW = "row";
    static final String NEW_ID = "newId";
    static final String RULES = "rules";
    static final String APPROVAL = "approval";
    static final String REQUEST = "request";
    static final String STATUS_AFTER = "statusAfter";

    private OperatorPlans() {}

    // ---- which plan --------------------------------------------------------------------------------------------------

    /**
     * The plan a month is settled on: the approved change with the latest effective month not after it; the first
     * plan without one.
     */
    public static String planOf(List<EntityInstance> changes, String month) {
        return changes.stream()
            .filter(change -> APPROVED.equals(change.get("status")))
            .filter(change -> String.valueOf((Object) change.get("effectiveMonth")).compareTo(month) <= 0)
            .max(Comparator.comparing(change -> String.valueOf((Object) change.get("effectiveMonth"))))
            .map(change -> String.valueOf((Object) change.get("plan")))
            .orElse(DEFAULT_PLAN);
    }

    /** All the changes of an operator's plan (a handful a year). */
    public static EntityQuery changesOf(Object operatorId) {
        return EntityQuery.builder().where(new QueryPredicate.In("operatorId", operatorId == null ? List.of()
            : List.of(String.valueOf(operatorId)))).limit(1_000).build();
    }

    // ---- proposing a change ------------------------------------------------------------------------------------------

    public static ProcessDefinition<SetInput, PlanOutput, ProcessContext> set(ZoneId zone) {
        return ProcessDefinition.define(SET, 1, SetInput.class, PlanOutput.class, ProcessContext.class, pb -> pb
            .description("Headquarters proposes an operator's plan from a later month; another administrator "
                + "approves it.")
            .permissions(QbPermissions.OPERATORS_WRITE)
            .requiresVerifiedEmail()
            .actsOn(QbOperators.OPERATOR, "operatorId")
            .contextFactory(OperatorPlans::context)
            .outputMapper(OperatorPlans::output)
            .step("Lock the operator", HoldLock.<ProcessContext>exclusive(
                ctx -> QbOperators.operatorLockName(setInput(ctx).operatorId())))
            .step("Load the operator, its plan's changes and the review rule", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("operatorId", List.of(setInput(ctx).operatorId()))).limit(1)
                    .build(), OPERATOR),
                QueryEntities.query(QbOperators.defaultDataset(PLAN), ctx -> changesOf(setInput(ctx).operatorId()),
                    CHANGES),
                QueryEntities.query(ApprovalEntities.RULE_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("subject", APPROVAL_SUBJECT),
                        new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES)))
            .compute("Record the proposal", (metadata, ctx) -> propose(ctx, zone))
            // Written first, so that the approval binds what is stored (read back, as its result reads it).
            .step("Save the proposal", SaveChanges.now())
            .step("Read it back", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(PLAN),
                ctx -> byId(ctx.contains(NEW_ID) ? (String) ctx.get(NEW_ID) : null), ROW))
            .step("Ask for another administrator's approval", RequireApproval.<ProcessContext>of(APPROVAL_SUBJECT,
                ctx -> approvalCase(first(ctx, ROW)), APPROVAL))
            .compute("Wait for the approval", (metadata, ctx) -> waitForApproval(ctx)));
    }

    static void propose(ProcessContext ctx, ZoneId zone) {
        SetInput input = setInput(ctx);
        if (first(ctx, OPERATOR) == null) {
            throw new EntityNotFoundException(QbOperators.OPERATOR + " " + input.operatorId() + " not found");
        }
        List<Violation> problems = new ArrayList<>();
        String now = MONTH.format(YearMonth.from(ctx.opTime().atZone(zone)));
        if (input.effectiveMonth().compareTo(now) <= 0) {
            problems.add(new Violation("effectiveMonth", OperatorCodes.PLAN_MONTH, "A plan changes from a later "
                + "month: the one begun is settled on the plan it began with", Map.of("month", now)));
        }
        if (all(ctx, CHANGES).stream().anyMatch(change -> PENDING.equals(change.get("status")))) {
            problems.add(new Violation(null, OperatorCodes.PLAN_PENDING, "A change of the operator's plan is "
                + "waiting for its approval", Map.of()));
        }
        if (first(ctx, RULES) == null) {
            problems.add(noReview());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operatorId", input.operatorId());
        values.put("plan", input.plan());
        values.put("effectiveMonth", input.effectiveMonth());
        values.put("reason", input.reason().strip());
        values.put("status", PENDING);
        values.put("proposedBy", ctx.request().actorId());
        values.put("proposedAt", ctx.opTime());
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(PLAN, values)));
    }

    private static Violation noReview() {
        return new Violation(null, OperatorCodes.PLAN_NO_REVIEW, "No approval rule asks for a second person on the "
            + "operators' plans", Map.of());
    }

    static void waitForApproval(ProcessContext ctx) {
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval == null || approval.status() != ApprovalOutcome.Status.PENDING) {
            // A rule whose condition no longer applies: never changed by one person. Nothing is kept.
            throw new BusinessRuleViolationException(List.of(noReview()));
        }
        EntityInstance row = first(ctx, ROW);
        ctx.changes().update(PLAN, row.id(), row.version(), Map.of("approvalRequestId", approval.requestId()));
    }

    /**
     * The case: the rule's fact and the content approved (whose plan, which plan, from when, why), from the stored
     * record. The proposer is the preparer: the platform refuses their approval.
     */
    static ApprovalCase approvalCase(EntityInstance change) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("planChangeId", String.valueOf(change.id()));
        content.put("operatorId", change.get("operatorId"));
        content.put("plan", change.get("plan"));
        content.put("effectiveMonth", change.get("effectiveMonth"));
        content.put("reason", change.get("reason"));
        return ApprovalCase.of(String.valueOf(change.id()), Map.of("operatorPlan", true), content)
            .preparedBy(change.get("proposedBy"))
            .reference(change.get("effectiveMonth") + " " + change.get("operatorId"));
    }

    // ---- the approval --------------------------------------------------------------------------------------------------

    public static ProcessDefinition<TransferProcesses.ApprovalResultInput, PlanOutput, ProcessContext> approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, TransferProcesses.ApprovalResultInput.class,
            PlanOutput.class, ProcessContext.class, pb -> pb
                .description("Puts an operator's change of plan in effect once another administrator approved it, "
                    + "or records that they rejected it.")
                .permissions(QbPermissions.OPERATOR_SETTLEMENT_SYSTEM)
                .internal()
                .contextFactory(OperatorPlans::context)
                .outputMapper(OperatorPlans::output)
                .step("Find the change", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(PLAN),
                    ctx -> byId(APPROVAL_SUBJECT.equals(resultInput(ctx).subject()) ? resultInput(ctx).entityId()
                        : null), FOUND))
                .step("Lock the operator", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null
                    ? null : QbOperators.operatorLockName(first(ctx, FOUND).get("operatorId"))))
                .step("Read it again, and the request", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbOperators.defaultDataset(PLAN), ctx -> byId(first(ctx, FOUND) == null
                        ? null : String.valueOf(first(ctx, FOUND).id())), ROW),
                    QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", resultInput(ctx).requestId() == null
                            ? new ArrayList<>() : new ArrayList<>(List.of(resultInput(ctx).requestId()))))
                        .limit(1).build(), REQUEST)))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(resultInput(ctx).status()),
                    APPROVAL_SUBJECT, ctx -> approvalCase(first(ctx, ROW)).preparedBy(resultInput(ctx).preparerId()),
                    APPROVAL))
                .compute("Approve or reject", (metadata, ctx) -> decided(ctx)));
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance change = first(ctx, ROW);
        TransferProcesses.ApprovalResultInput input = resultInput(ctx);
        return change != null && PENDING.equals(change.get("status")) && input.requestId() != null
            && input.requestId().equals(change.get("approvalRequestId"));
    }

    static void decided(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance change = first(ctx, ROW);
        TransferProcesses.ApprovalResultInput input = resultInput(ctx);
        String status = null;
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval != null && approval.status() == ApprovalOutcome.Status.APPROVED
                && input.requestId().equals(approval.requestId())) {
                status = APPROVED;
            }
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, change)) {
            status = REJECTED;
        }
        if (status != null) {
            ctx.put(STATUS_AFTER, status);
            ctx.changes().update(PLAN, change.id(), change.version(), Map.of("status", status,
                "decidedAt", ctx.opTime()));
        }
    }

    /** Whether the stored request (not the event) says that the change, as proposed, was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance change) {
        EntityInstance request = first(ctx, REQUEST);
        if (request == null) {
            return false;
        }
        String hash = ContentHash.of(approvalCase(change).content());
        return ApprovalEntities.REJECTED.equals(request.get("status"))
            && APPROVAL_SUBJECT.equals(request.get("subject"))
            && String.valueOf(change.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }

    // ---- shared --------------------------------------------------------------------------------------------------------

    /** A plan's fixed fee (yen, at least 0) and the platform's share (0 to 1), from the parameters; null when unset. */
    public record Terms(String plan, BigDecimal fixedFee, BigDecimal platformShare) {}

    /** The parameters' keys of the plans' terms. */
    public static List<String> paramKeys() {
        List<String> keys = new ArrayList<>();
        for (String plan : PLANS) {
            keys.add(QbParams.operatorPlanFee(plan));
            keys.add(QbParams.operatorPlanShare(plan));
        }
        return List.copyOf(keys);
    }

    /** The terms of {@code plan} from the parameters read ({@code key → value}); null when one is unset or invalid. */
    public static Terms terms(String plan, Map<String, Object> params) {
        BigDecimal fee = decimal(params.get(QbParams.operatorPlanFee(plan)));
        BigDecimal share = decimal(params.get(QbParams.operatorPlanShare(plan)));
        if (fee == null || fee.signum() < 0 || share == null || share.signum() < 0
            || share.compareTo(BigDecimal.ONE) > 0) {
            return null;
        }
        return new Terms(plan, fee.setScale(0, java.math.RoundingMode.DOWN), share);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static EntityQuery byId(String planChangeId) {
        List<Object> ids = new ArrayList<>();
        if (planChangeId != null) {
            ids.add(planChangeId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("planChangeId", ids)).limit(1).build();
    }

    private static ProcessContext context(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static SetInput setInput(ProcessContext ctx) {
        return ctx.get(INPUT, SetInput.class);
    }

    private static TransferProcesses.ApprovalResultInput resultInput(ProcessContext ctx) {
        return ctx.get(INPUT, TransferProcesses.ApprovalResultInput.class);
    }

    private static PlanOutput output(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        if (row == null) {
            return new PlanOutput(null, null, null);
        }
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER) : row.get("status");
        ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
        String requestId = approval != null && approval.status() == ApprovalOutcome.Status.PENDING
            ? approval.requestId() : row.get("approvalRequestId");
        return new PlanOutput(String.valueOf(row.id()), status, requestId);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> all(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        return found instanceof List<?> list ? (List<EntityInstance>) list : List.of();
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = all(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
