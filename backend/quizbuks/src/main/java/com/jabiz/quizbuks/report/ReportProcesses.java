package com.jabiz.quizbuks.report;

import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.entity.Violation;
import com.jabiz.security.MfaRequirement;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reporting a publication (Q49a; docs/quizbuks/plans/Q49a-contest-report.md; Apple App Review Guideline 1.2):
 * <ul>
 *   <li>{@value #SUBMIT}: a taker reports a quiz they can see, with a reason and an optional note; one open report of
 *       theirs per quiz and {@value QbReports#DAILY_LIMIT} a day. The first open report of a quiz asks its reviewers:
 *       a task for the operator's members when an operator looks after the sponsor (scoped to the operator, decision
 *       D64), for headquarters' content administrators otherwise; headquarters' list shows every report.</li>
 *   <li>{@value #RESOLVE} / {@value #OPERATOR_RESOLVE}: headquarters, or the sponsor's operator, closes the open
 *       reports of a quiz as dismissed or acted on (pausing or ending the quiz is the existing
 *       {@code QB_PUBLICATION_ADMIN_*} / {@code QB_OPERATOR_PUBLICATION_*}); the tasks close with them.</li>
 * </ul>
 */
public final class ReportProcesses {

    public static final String SUBMIT = "QB_REPORT_SUBMIT";
    public static final String RESOLVE = "QB_REPORT_RESOLVE";
    public static final String OPERATOR_RESOLVE = "QB_OPERATOR_REPORT_RESOLVE";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String CARD = "card";
    static final String MINE = "mine";
    static final String RECENT = "recent";
    static final String OPEN_REPORTS = "openReports";
    static final String SPONSOR_OPERATOR = "sponsorOperator";
    static final String FIRST_OPEN = "firstOpen";

    /**
     * @param publicationId the quiz reported
     * @param reason        one of {@link QbReports#REASONS}
     * @param note          what the taker adds, optional
     */
    public record SubmitInput(@NotNull UUID publicationId, @NotBlank String reason,
        @Size(max = QbReports.NOTE_LENGTH) String note) {}

    /** @param reportId the report written */
    public record SubmitOutput(String reportId) {}

    /**
     * @param publicationId the quiz whose open reports are closed
     * @param outcome       {@link QbReports#DISMISSED} or {@link QbReports#ACTIONED}
     * @param resolution    what the reviewer noted, optional
     */
    public record ResolveInput(@NotNull UUID publicationId, @NotBlank String outcome,
        @Size(max = QbReports.RESOLUTION_LENGTH) String resolution) {}

    /** @param resolved how many open reports were closed */
    public record ResolveOutput(int resolved) {}

    private ReportProcesses() {}

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SubmitInput, SubmitOutput, ProcessContext> submit() {
        return ProcessDefinition.define(SUBMIT, 1, SubmitInput.class, SubmitOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Reports a publication the caller can see: a reason and an optional note for its "
                        + "reviewers.")
                    .permissions(QbPermissions.PLAY)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, SubmitOutput.class));
                CallerChecks.refuseBanned(pb);
                pb.step("Take the publication's turn", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbReports.lockName(input(ctx).publicationId())))
                    .step("Load the publication's card", QueryEntities.<ProcessContext>of(
                        QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.Eq("publicationId", input(ctx).publicationId()))
                            .limit(1).build(), CARD))
                    .step("Load the caller's reports of a day", QueryEntities.<ProcessContext>of(
                        QbReports.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("reporterId", ctx.request().actorId()),
                                new QueryPredicate.Gte("createdAt", ctx.opTime().minus(Duration.ofDays(1))))))
                            .limit(QbReports.DAILY_LIMIT + 1).build(), RECENT))
                    .step("Load the caller's open report of it", QueryEntities.<ProcessContext>of(
                        QbReports.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("reporterId", ctx.request().actorId()),
                                new QueryPredicate.Eq("publicationId", input(ctx).publicationId()),
                                new QueryPredicate.Eq("status", QbReports.OPEN))))
                            .limit(1).build(), MINE))
                    .step("Load an open report of it", QueryEntities.<ProcessContext>of(
                        QbReports.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("publicationId", input(ctx).publicationId()),
                                new QueryPredicate.Eq("status", QbReports.OPEN))))
                            .limit(1).build(), OPEN_REPORTS))
                    .step("Load the sponsor's operator", QueryEntities.<ProcessContext>of(
                        QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> {
                            EntityInstance card = first(ctx, CARD);
                            return EntityQuery.builder().where(new QueryPredicate.Eq("userId",
                                card == null ? "" : String.valueOf((Object) card.get("publisherId")))).limit(1).build();
                        }, SPONSOR_OPERATOR))
                    .compute("Record the report", (metadata, ctx) -> submit(ctx))
                    // The sponsor's operator is asked when one looks after them, headquarters otherwise: an operator's
                    // session closes only tasks of its scope (decision D64); headquarters has every report in its list
                    // and closes the operator's task as well.
                    .step("Ask headquarters", CreateTask.<ProcessContext>when(
                        ctx -> Boolean.TRUE.equals(ctx.get(FIRST_OPEN)) && first(ctx, SPONSOR_OPERATOR) == null,
                        ctx -> task(ctx, QbPermissions.ADMIN_CONTENT_WRITE).link("/qb/reports")))
                    .step("Ask the sponsor's operator", CreateTask.<ProcessContext>when(
                        ctx -> Boolean.TRUE.equals(ctx.get(FIRST_OPEN)) && first(ctx, SPONSOR_OPERATOR) != null,
                        ctx -> task(ctx, QbPermissions.OPERATOR_USE).link("/qb/reports").scope(QbOperators.SCOPE,
                            String.valueOf((Object) first(ctx, SPONSOR_OPERATOR).get("operatorId")))));
            });
    }

    static void submit(ProcessContext ctx) {
        SubmitInput input = input(ctx);
        EntityInstance card = first(ctx, CARD);
        // A channel's publication (a shop's storefront quiz) is no App taker's: as good as none.
        if (card == null || !PublisherKind.sponsor(card.get("publisherKind"))) {
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + input.publicationId() + " not found");
        }
        if (!QbReports.REASONS.contains(input.reason())) {
            ctx.reject(new Violation("reason", ReportCodes.REASON, "Unknown reason " + input.reason(), Map.of()));
            return;
        }
        if (first(ctx, MINE) != null) {
            ctx.reject(new Violation("publicationId", ReportCodes.ALREADY_REPORTED, "The caller's report of this "
                + "publication is still open", Map.of()));
            return;
        }
        if (list(ctx, RECENT).size() >= QbReports.DAILY_LIMIT) {
            ctx.reject(new Violation("publicationId", ReportCodes.DAILY_LIMIT, "The caller made "
                + QbReports.DAILY_LIMIT + " reports in a day", Map.of("limit", QbReports.DAILY_LIMIT)));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("publicationId", input.publicationId());
        values.put(PublisherKind.KIND_FIELD, card.get("publisherKind"));
        values.put(PublisherKind.ID_FIELD, card.get("publisherId"));
        values.put("reporterId", ctx.request().actorId());
        values.put("reason", input.reason());
        values.put("note", input.note() == null || input.note().isBlank() ? null : input.note().strip());
        values.put("status", QbReports.OPEN);
        values.put("createdAt", ctx.opTime());
        Object id = ctx.changes().insert(QbReports.REPORT, values);
        ctx.put(FIRST_OPEN, first(ctx, OPEN_REPORTS) == null);
        ctx.put(OUTPUT, new SubmitOutput(String.valueOf(id)));
    }

    private static TaskSpec task(ProcessContext ctx, String permission) {
        EntityInstance card = first(ctx, CARD);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("publication", String.valueOf((Object) card.get("name")));
        return TaskSpec.forPermission(QbReports.TASK_TYPE, "task.qb.report", params, permission)
            .about(QbPublications.PUBLICATION, input(ctx).publicationId().toString())
            .source(QbReports.taskSource(input(ctx).publicationId()));
    }

    // ---- resolve ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ResolveInput, ResolveOutput, ProcessContext> resolve() {
        return resolve(RESOLVE, false);
    }

    public static ProcessDefinition<ResolveInput, ResolveOutput, ProcessContext> operatorResolve() {
        return resolve(OPERATOR_RESOLVE, true);
    }

    private static ProcessDefinition<ResolveInput, ResolveOutput, ProcessContext> resolve(String name,
        boolean operator) {
        return ProcessDefinition.define(name, 1, ResolveInput.class, ResolveOutput.class, ProcessContext.class,
            pb -> {
                pb.description(operator ? "Closes the open reports of a publication of a sponsor the operator looks "
                        + "after: dismissed or acted on." : "Closes the open reports of a publication: dismissed or "
                        + "acted on.")
                    .permissions(operator ? QbPermissions.OPERATOR_USE : QbPermissions.ADMIN_CONTENT_WRITE)
                    .requiresMfa(MfaRequirement.ALWAYS)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, ResolveOutput.class));
                if (operator) {
                    OperatorChecks.member(pb);
                }
                pb.step("Take the publication's turn", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbReports.lockName(resolveInput(ctx).publicationId())))
                    .step("Load its open reports", QueryEntities.<ProcessContext>of(QbReports.DEFAULT_DATASET,
                        ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("publicationId", resolveInput(ctx).publicationId()),
                            new QueryPredicate.Eq("status", QbReports.OPEN)))).limit(Integer.MAX_VALUE).build(),
                        OPEN_REPORTS));
                if (operator) {
                    // Q59c-2: only a publication of a sponsor the caller's operator looks after (the dataset's scope
                    // hides the others' reports already; this refuses with the operators' own answer).
                    OperatorChecks.ownSponsor(pb, ctx -> {
                        EntityInstance report = first(ctx, OPEN_REPORTS);
                        return report == null ? null : report.get(PublisherKind.ID_FIELD);
                    });
                }
                pb.compute("Close them", (metadata, ctx) -> resolve(ctx))
                    .step("Close the reviewers' tasks", CloseTasks.<ProcessContext>done(
                        ctx -> QbReports.taskSource(resolveInput(ctx).publicationId())));
            });
    }

    static void resolve(ProcessContext ctx) {
        ResolveInput input = resolveInput(ctx);
        if (!QbReports.OUTCOMES.contains(input.outcome())) {
            ctx.reject(new Violation("outcome", ReportCodes.OUTCOME, "Unknown outcome " + input.outcome(), Map.of()));
            return;
        }
        List<EntityInstance> open = list(ctx, OPEN_REPORTS);
        if (open.isEmpty()) {
            throw new EntityNotFoundException(QbReports.REPORT + " of " + input.publicationId() + " not found");
        }
        Instant now = ctx.opTime();
        String resolution = input.resolution() == null || input.resolution().isBlank() ? null
            : input.resolution().strip();
        for (EntityInstance report : open) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", input.outcome());
            values.put("resolvedAt", now);
            values.put("resolvedBy", ctx.request().actorId());
            values.put("resolution", resolution);
            ctx.changes().update(QbReports.REPORT, report.id(), report.version(), values);
        }
        ctx.put(OUTPUT, new ResolveOutput(open.size()));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static SubmitInput input(ProcessContext ctx) {
        return ctx.get(INPUT, SubmitInput.class);
    }

    private static ResolveInput resolveInput(ProcessContext ctx) {
        return ctx.get(INPUT, ResolveInput.class);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key) : null;
        return value == null ? List.of() : (List<EntityInstance>) value;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }
}
