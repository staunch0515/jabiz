package com.jabiz.finance.close;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The governed reopening of a closed period (FIN-PC-006; ROADMAP F8b):
 * <ul>
 *   <li>{@code FIN_PERIOD_REOPEN_REQUEST}: asks, with a reason, for the latest closed period to open again; a
 *       controller other than the requester decides (the platform's approval, subject {@value #SUBJECT}). A period
 *       with a later closed period is not reopened: the later one first.</li>
 *   <li>{@code FIN_PERIOD_REOPEN_WITHDRAW}: the requester takes a request back before it is decided.</li>
 *   <li>{@code FIN_PERIOD_REOPEN_APPROVAL_RESULT}: the decision, from the platform's approval events only. Approved,
 *       the period and its subledgers are open again, unless a later period closed meanwhile (the request lapses);
 *       rejected, it stays closed. Closed again, its new artifact supersedes the one before.</li>
 * </ul>
 */
public final class ReopenProcesses {

    public static final String REQUEST = "FIN_PERIOD_REOPEN_REQUEST";
    public static final String WITHDRAW = "FIN_PERIOD_REOPEN_WITHDRAW";
    public static final String APPROVAL_RESULT = "FIN_PERIOD_REOPEN_APPROVAL_RESULT";
    public static final String SUBJECT = "fin.period.reopen";

    public static final String NOT_CLOSED = "FIN_PERIOD_REOPEN_NOT_CLOSED";
    public static final String LATER_CLOSED = "FIN_PERIOD_REOPEN_LATER_CLOSED";
    public static final String PENDING_ALREADY = "FIN_PERIOD_REOPEN_PENDING";
    public static final String NO_RULE = "FIN_PERIOD_REOPEN_NO_RULE";
    public static final String NOT_FOUND = "FIN_PERIOD_REOPEN_NOT_FOUND";
    public static final String NOT_PENDING = "FIN_PERIOD_REOPEN_NOT_PENDING";
    public static final String NOT_REQUESTER = "FIN_PERIOD_REOPEN_NOT_REQUESTER";

    public record ReopenId(@jakarta.validation.constraints.NotNull UUID reopenId) {}

    public record RequestInput(@NotBlank String periodKey, @NotBlank @Size(max = 1000) String reason) {}

    /** @param status {@code PENDING}, {@code APPROVED} or {@code REJECTED}, the period's state after it */
    public record ReopenOutput(String reopenId, String periodKey, String status, String periodStatus,
        String approvalRequestId, String decidedBy) {}

    /** The platform's approval decision, as its events carry it. */
    public record ApprovalResultInput(String subject, String entityId, String status, String contentHash,
        String requestId) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String PERIODS = "periods";
    static final String LATER = "later";
    static final String PENDING = "pending";
    static final String ARTIFACTS = "artifacts";
    static final String REOPENS = "reopens";
    static final String REOPEN_ID = "reopenId";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String REQUESTS = "requests";
    static final String DECISIONS = "decisions";

    public static final ProcessDefinition<RequestInput, ReopenOutput, ProcessContext> REQUEST_PROCESS =
        ProcessDefinition.define(REQUEST, 1, RequestInput.class, ReopenOutput.class, ProcessContext.class, pb -> pb
            .description("Asks for a closed period to open again; another controller decides.")
            .permissions(FinancePermissions.PERIOD_REOPEN_REQUEST)
            .contextFactory(ReopenProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ReopenOutput.class))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> CloseProcesses.byPeriod(ctx.get(INPUT, RequestInput.class).periodKey()), PERIODS))
            .step("Load a later closed period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ReopenProcesses::laterClosed, LATER))
            .step("Load the requests waiting", QueryEntities.of(CloseEntities.REOPEN_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("periodKey", ctx.get(INPUT, RequestInput.class).periodKey().trim()),
                    new QueryPredicate.Eq("status", CloseEntities.PENDING)))).limit(1).build(), PENDING))
            .step("Load the period's artifacts", QueryEntities.of(CloseEntities.ARTIFACT_DATASET,
                ctx -> CloseProcesses.byPeriod(ctx.get(INPUT, RequestInput.class).periodKey(), 1000), ARTIFACTS))
            .compute("Ask", (metadata, ctx) -> request(ctx))
            .step("Save", SaveChanges.now())
            .step("Ask a controller", RequireApproval.when(ctx -> ctx.contains(CASE), SUBJECT,
                ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL))
            .compute("Record it", (metadata, ctx) -> recordRequest(ctx)));

    public static final ProcessDefinition<ApprovalResultInput, ReopenOutput, ProcessContext> APPROVAL_RESULT_PROCESS =
        ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, ReopenOutput.class,
            ProcessContext.class, pb -> pb
                .description("Opens a period again once its reopening is approved, or records the rejection.")
                .permissions(FinancePermissions.PERIOD_REOPEN_RESULT)
                .internal()
                .contextFactory(ReopenProcesses::withInput)
                // Nothing to do (another subject's decision, or one no longer standing): an empty answer.
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, ReopenOutput.class)
                    : new ReopenOutput(ctx.get(INPUT, ApprovalResultInput.class).entityId(), null, null, null,
                        ctx.get(INPUT, ApprovalResultInput.class).requestId(), null))
                .step("Load the request", QueryEntities.of(CloseEntities.REOPEN_DATASET, ctx -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    return EntityQuery.builder().where(new QueryPredicate.In("reopenId",
                        SUBJECT.equals(input.subject()) && uuid(input.entityId()) != null
                            ? List.of(uuid(input.entityId())) : List.of())).limit(1).build();
                }, REOPENS))
                .step("Load its period", QueryEntities.of(GlEntities.PERIOD_DATASET, ctx -> CloseProcesses.byPeriod(
                    first(ctx, REOPENS) == null ? "" : first(ctx, REOPENS).get("periodKey")), PERIODS))
                .step("Load a later closed period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ReopenProcesses::laterClosed, LATER))
                .step("Load the approval request", QueryEntities.of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> byRequestId(ctx.get(INPUT, ApprovalResultInput.class).requestId()), REQUESTS))
                .step("Load its decisions", QueryEntities.of(ApprovalEntities.DECISION_DATASET,
                    ctx -> byRequestId(ctx.get(INPUT, ApprovalResultInput.class).requestId()), DECISIONS))
                .compute("Decide", (metadata, ctx) -> decide(ctx)));

    public static final ProcessDefinition<ReopenId, ReopenOutput, ProcessContext> WITHDRAW_PROCESS =
        ProcessDefinition.define(WITHDRAW, 1, ReopenId.class, ReopenOutput.class, ProcessContext.class, pb -> pb
            .description("Takes back a reopening not yet decided.")
            .permissions(FinancePermissions.PERIOD_REOPEN_REQUEST)
            .actsOn(CloseEntities.REOPEN, "reopenId", a -> a.whenField("status", CloseEntities.PENDING))
            .contextFactory(ReopenProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ReopenOutput.class))
            .step("Load the request", QueryEntities.of(CloseEntities.REOPEN_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("reopenId", ctx.get(INPUT, ReopenId.class).reopenId())).limit(1)
                .build(), REOPENS))
            .compute("Withdraw it", (metadata, ctx) -> withdraw(ctx))
            .step("Withdraw the approval request", WithdrawApproval.of(SUBJECT,
                ctx -> ctx.get(INPUT, ReopenId.class).reopenId())));

    static void withdraw(ProcessContext ctx) {
        EntityInstance reopen = first(ctx, REOPENS);
        if (reopen == null) {
            ctx.reject(new Violation("reopenId", NOT_FOUND, "There is no such reopening", Map.of()));
            return;
        }
        if (!CloseEntities.PENDING.equals(reopen.get("status"))) {
            ctx.reject(new Violation("reopenId", NOT_PENDING, "The reopening is " + reopen.get("status"),
                Map.of("status", (Object) reopen.get("status"))));
            return;
        }
        if (!Objects.equals(ctx.request().actorId(), reopen.get("requestedBy"))) {
            ctx.reject(new Violation("reopenId", NOT_REQUESTER, "A reopening is withdrawn by who asked for it",
                Map.of()));
            return;
        }
        ctx.changes().update(CloseEntities.REOPEN, reopen.id(), reopen.version(), Map.of("status",
            CloseEntities.WITHDRAWN, "decidedBy", ctx.request().actorId(), "decidedAt", ctx.opTime()));
        ctx.put(OUTPUT, new ReopenOutput(String.valueOf(reopen.id()), reopen.get("periodKey"), CloseEntities.WITHDRAWN,
            PeriodPolicy.Status.CLOSED.name(), null, ctx.request().actorId()));
    }

    /** Regular periods after the period loaded that are closed: one is enough. */
    private static EntityQuery laterClosed(ProcessContext ctx) {
        EntityInstance period = first(ctx, PERIODS);
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Gt("startDate", period == null ? LocalDate.MAX : period.get("startDate")),
            new QueryPredicate.Eq("status", PeriodPolicy.Status.CLOSED.name()),
            new QueryPredicate.Eq("adjustment", false)))).limit(1).build();
    }

    static void request(ProcessContext ctx) {
        RequestInput input = ctx.get(INPUT, RequestInput.class);
        String periodKey = input.periodKey().trim();
        EntityInstance period = first(ctx, PERIODS);
        if (period == null) {
            ctx.reject(new Violation("periodKey", PeriodProcesses.PERIOD_NOT_FOUND, "There is no period " + periodKey,
                Map.of("periodKey", periodKey)));
            return;
        }
        if (Boolean.TRUE.equals(period.get("opening"))) {
            ctx.reject(new Violation("periodKey", PeriodProcesses.OPENING_PERIOD, "Period " + periodKey
                + " holds the opening of the books: it changes only through the migration",
                Map.of("periodKey", periodKey)));
            return;
        }
        if (!PeriodPolicy.Status.CLOSED.name().equals(period.get("status"))) {
            ctx.reject(new Violation("periodKey", NOT_CLOSED, "Period " + periodKey + " is not closed",
                Map.of("periodKey", periodKey)));
            return;
        }
        if (first(ctx, LATER) != null) {
            String later = first(ctx, LATER).get("periodKey");
            ctx.reject(new Violation("periodKey", LATER_CLOSED, "Period " + later + " after it is closed: it is "
                + "reopened first", Map.of("periodKey", periodKey, "later", later)));
            return;
        }
        if (first(ctx, PENDING) != null) {
            ctx.reject(new Violation("periodKey", PENDING_ALREADY, "A reopening of " + periodKey + " waits for its "
                + "approver already", Map.of("periodKey", periodKey)));
            return;
        }
        EntityInstance artifact = CloseProcesses.list(ctx, ARTIFACTS).stream()
            .max(Comparator.comparing(a -> a.<BigDecimal>get("seq"))).orElse(null);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("periodKey", periodKey);
        values.put("reason", input.reason().trim());
        values.put("artifactId", artifact == null ? null : artifact.id());
        values.put("requestedBy", ctx.request().actorId());
        values.put("requestedAt", ctx.opTime());
        values.put("status", CloseEntities.PENDING);
        Map<String, Object> content = content(periodKey, input.reason().trim(), artifact == null ? null
            : artifact.id(), ctx.opTime());
        values.put("contentHash", ContentHash.of(content));
        Object id = ctx.changes().insert(CloseEntities.REOPEN, values);
        ctx.put(REOPEN_ID, id);
        ctx.put(CASE, ApprovalCase.of(id, Map.of("periodKey", periodKey), content)
            .preparedBy(ctx.request().actorId()));
    }

    static void recordRequest(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        Object id = ctx.get(REOPEN_ID);
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval.status() != ApprovalOutcome.Status.PENDING) {
            // Never opened again on the requester's word alone (FIN-PC-006).
            ctx.reject(new Violation("periodKey", NO_RULE, "No approval rule of reopenings applies: a controller sets "
                + "one before any period is reopened", Map.of()));
            return;
        }
        RequestInput input = ctx.get(INPUT, RequestInput.class);
        ctx.put(OUTPUT, new ReopenOutput(String.valueOf(id), input.periodKey().trim(), CloseEntities.PENDING,
            PeriodPolicy.Status.CLOSED.name(), approval.requestId(), null));
    }

    static void decide(ProcessContext ctx) {
        ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
        EntityInstance reopen = first(ctx, REOPENS);
        EntityInstance request = first(ctx, REQUESTS);
        EntityInstance period = first(ctx, PERIODS);
        if (reopen == null || request == null || period == null
            || !CloseEntities.PENDING.equals(reopen.get("status"))
            || !SUBJECT.equals(request.get("subject"))
            || !String.valueOf(reopen.id()).equals(request.get("entityId"))
            || !Objects.equals(reopen.get("contentHash"), request.get("contentHash"))
            || !Objects.equals(input.contentHash(), reopen.get("contentHash"))
            || !Objects.equals(request.get("status"), input.status())) {
            return;
        }
        boolean approved = ApprovalEntities.APPROVED.equals(request.get("status"));
        if (!approved && !ApprovalEntities.REJECTED.equals(request.get("status"))) {
            return;
        }
        String decider = CloseProcesses.list(ctx, DECISIONS).stream()
            .max(Comparator.comparing(d -> new BigDecimal(String.valueOf((Object) d.get("levelNo")))))
            .map(d -> String.valueOf((Object) d.get("approverId"))).orElse(null);
        // A later period closed while the request waited: opening this one now would change the later one's
        // opening balances behind its artifact, so the approval lapses (the later one is reopened first).
        boolean lapsed = approved && first(ctx, LATER) != null;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", lapsed ? CloseEntities.LAPSED : approved ? CloseEntities.APPROVED
            : CloseEntities.REJECTED);
        values.put("approvalRequestId", input.requestId());
        values.put("decidedBy", decider);
        values.put("decidedAt", ctx.opTime());
        ctx.changes().update(CloseEntities.REOPEN, reopen.id(), reopen.version(), values);
        String periodStatus = period.get("status");
        if (approved && !lapsed && PeriodPolicy.Status.CLOSED.name().equals(periodStatus)) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("status", PeriodPolicy.Status.OPEN.name());
            PeriodProcesses.SUBLEDGER_FIELDS.values().forEach(field -> state.put(field, "OPEN"));
            ctx.changes().update(GlEntities.PERIOD, period.id(), period.version(), state);
            periodStatus = PeriodPolicy.Status.OPEN.name();
        }
        ctx.put(OUTPUT, new ReopenOutput(String.valueOf(reopen.id()), reopen.get("periodKey"),
            (String) values.get("status"), periodStatus, input.requestId(), decider));
    }

    /** What the approver approves: the period, the reason and the close it undoes. */
    static Map<String, Object> content(String periodKey, String reason, Object artifactId, Instant requestedAt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("periodKey", periodKey);
        content.put("reason", reason);
        content.put("artifactId", artifactId == null ? null : String.valueOf(artifactId));
        content.put("requestedAt", requestedAt.toString());
        return content;
    }

    private static EntityQuery byRequestId(String requestId) {
        UUID id = uuid(requestId);
        return EntityQuery.builder().where(new QueryPredicate.In("requestId", id == null ? List.of() : List.of(id)))
            .limit(20).build();
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        Object value = ctx.get(key);
        if (!(value instanceof List<?> found) || found.isEmpty()) {
            return null;
        }
        return (EntityInstance) found.getFirst();
    }

    private static ProcessContext withInput(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private ReopenProcesses() {}
}
