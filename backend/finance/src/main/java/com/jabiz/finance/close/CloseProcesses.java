package com.jabiz.finance.close;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.calc.CloseChecks;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.fx.FxEntities;
import com.jabiz.finance.fx.FxRevaluationProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.PeriodBalances;
import com.jabiz.finance.gl.PeriodLocks;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.report.ReportProcesses;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The period close (FIN-PC-004, PC-005, CT-005; docs/finance/00-design.md section 7.3; ROADMAP F8a):
 * <ul>
 *   <li>{@code FIN_CLOSE_TEMPLATE_SAVE}: an item of the checklist's template, a manual task with the permission of
 *       who does it, or an automatic check ({@link CloseChecks}).</li>
 *   <li>{@code FIN_CLOSE_START}: a period's tasks from the active template items; run again, only the items added
 *       since. Each manual task becomes a task of the platform for the holders of its permission.</li>
 *   <li>{@code FIN_CLOSE_CHECK}: runs the automatic checks and records each result, its time and its evidence on
 *       the period's tasks.</li>
 *   <li>{@code FIN_CLOSE_TASK_COMPLETE}: a manual task done by a holder of its permission, with a note and an
 *       evidence file.</li>
 *   <li>{@code FIN_PERIOD_CLOSE}: closes the general ledger and every subledger of the period when every required
 *       check passes again and every required manual task is done, else names each that does not; issues the trial
 *       balance as known at that moment and writes the close artifact.</li>
 * </ul>
 */
public final class CloseProcesses {

    public static final String TEMPLATE_SAVE = "FIN_CLOSE_TEMPLATE_SAVE";
    public static final String START = "FIN_CLOSE_START";
    public static final String CHECK = "FIN_CLOSE_CHECK";
    public static final String TASK_COMPLETE = "FIN_CLOSE_TASK_COMPLETE";
    public static final String TASK_ASSIGN = "FIN_CLOSE_TASK_ASSIGN";
    public static final String CLOSE = "FIN_PERIOD_CLOSE";

    public static final String TEMPLATE_INVALID = "FIN_CLOSE_TEMPLATE_INVALID";
    public static final String NOT_STARTED = "FIN_CLOSE_NOT_STARTED";
    public static final String TASK_NOT_FOUND = "FIN_CLOSE_TASK_NOT_FOUND";
    public static final String TASK_NOT_MANUAL = "FIN_CLOSE_TASK_NOT_MANUAL";
    public static final String TASK_DONE = "FIN_CLOSE_TASK_DONE";
    public static final String NOT_OWNER = "FIN_CLOSE_NOT_OWNER";
    public static final String CHECKS_FAILED = "FIN_CLOSE_CHECKS_FAILED";
    public static final String NOT_REGULAR = "FIN_CLOSE_NOT_REGULAR";

    /** The trial balance a close issues and keeps (FIN-PC-005). */
    public static final String TRIAL_BALANCE = "finance.gl.trial_balance";

    /** Where a period's close stands: progress, tasks (open first), subledgers, reconciliations (FIN-PC-009). */
    public static final String OVERVIEW = "finance.close.overview";

    /** The platform task of a manual close task; its title is the message {@code task.fin.close}. */
    public static final String TASK_TYPE = "fin.close";

    /**
     * @param checkCode       for an automatic item: what it checks, one of {@link CloseChecks#ALL}
     * @param ownerPermission for a manual item: whose task it is
     * @param dueDays         days after the period's last day the task is due
     */
    public record TemplateInput(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,29}") String taskCode,
        @NotBlank @Size(max = 200) String name, @NotBlank String kind, @Size(max = 30) String checkCode,
        @Size(max = 100) String ownerPermission,
        @NotNull @Min(0) @Max(365) Integer dueDays, Boolean required, @Min(0) @Max(9999) Integer sortOrder,
        Boolean active) {}

    public record TemplateOutput(String templateId, String taskCode, boolean created) {}

    public record PeriodInput(@NotBlank String periodKey) {}

    public record TaskOutput(String taskId, String taskCode, String name, String kind, String checkCode,
        String ownerPermission, LocalDate dueDate, boolean required, String status, String result, String evidence,
        Instant checkedAt, String completedBy, Instant completedAt) {}

    /**
     * @param created the tasks this run made
     * @param ready   whether every required task is done or passed
     */
    public record ChecklistOutput(String periodKey, List<TaskOutput> tasks, int created, boolean ready) {}

    /** @param evidenceFileId an uploaded file of {@value CloseEntities#EVIDENCE_FILES} */
    public record CompleteInput(@NotNull UUID taskId, @Size(max = 1000) String note, UUID evidenceFileId) {}

    public record AssignInput(String taskId, String periodKey, String taskCode, String name, String ownerPermission,
        Instant dueTime) {}

    public record AssignOutput(String taskId) {}

    public record CloseOutput(String periodKey, String status, String artifactId, int seq, String reportRunId,
        String trialBalanceHash, String contentHash, List<TaskOutput> checklist) {}

    /**
     * The sample checklist {@code FIN_SETUP} makes when there is none: every automatic check and two manual tasks,
     * ten items (FIN-PC-009's example); the controller changes it with {@code FIN_CLOSE_TEMPLATE_SAVE}.
     */
    public static final List<TemplateInput> SAMPLE = List.of(
        auto(CloseChecks.ENTRIES_POSTED, "Entries and documents posted and approved", 3, 10),
        auto(CloseChecks.RECURRING_RUN, "Recurring entries and invoices made", 1, 20),
        auto(CloseChecks.AUTO_REVERSALS, "Automatic reversals posted", 1, 30),
        auto(CloseChecks.DEPRECIATION_RUN, "Depreciation run", 2, 40),
        auto(CloseChecks.REVALUATION_RUN, "Foreign currency revaluation run", 2, 50),
        auto(CloseChecks.BANK_RECONCILED, "Bank accounts reconciled and signed off", 5, 60),
        auto(CloseChecks.SUBLEDGERS, "Subledgers agree with their control accounts", 5, 70),
        auto(CloseChecks.CLEARING_ZERO, "Clearing and suspense accounts at zero", 5, 80),
        new TemplateInput("ACCRUALS", "Accruals and prepaid expenses reviewed", CloseEntities.MANUAL, null,
            FinancePermissions.JOURNAL_PREPARE, 3, true, 90, true),
        new TemplateInput("REVIEW", "Trial balance reviewed", CloseEntities.MANUAL, null,
            FinancePermissions.PERIOD_CLOSE, 6, true, 100, true));

    private static TemplateInput auto(String check, String name, int dueDays, int sortOrder) {
        return new TemplateInput(check, name, CloseEntities.AUTO, check, null, dueDays, true, sortOrder, true);
    }

    /** An item of the template as its row holds it. */
    public static Map<String, Object> templateRow(TemplateInput item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskCode", item.taskCode());
        row.put("name", item.name());
        row.put("kind", item.kind());
        row.put("checkCode", item.checkCode());
        row.put("ownerPermission", item.ownerPermission());
        row.put("dueDays", BigDecimal.valueOf(item.dueDays()));
        row.put("required", !Boolean.FALSE.equals(item.required()));
        row.put("sortOrder", BigDecimal.valueOf(item.sortOrder() == null ? 0 : item.sortOrder()));
        row.put("active", !Boolean.FALSE.equals(item.active()));
        return row;
    }

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String TEMPLATES = "templates";
    static final String PERIODS = "periods";
    static final String TASKS = "tasks";
    static final String ASSIGN = "assign";
    static final String ASSIGNED = "assigned";
    static final String EXCEPTIONS = "exceptions";
    static final String TB = "trialBalance";
    static final String AR_ROWS = "arAging";
    static final String AP_ROWS = "apAging";
    static final String FA_ROWS = "faRegister";
    static final String FX_ITEMS = "fxItems";
    static final String FX_RUNS = "fxRuns";
    static final String CONTROL_ACCOUNTS = "controlAccounts";
    static final String RESULTS = "results";
    static final String ARTIFACTS = "artifacts";
    static final String ISSUE = "issue";
    static final String ISSUED = "issued";
    static final String SUBLEDGER_TOTALS = "subledgerTotals";

    /** The most template items, and so tasks of a period. */
    static final int MAX_ITEMS = 200;

    public static final ProcessDefinition<TemplateInput, TemplateOutput, ProcessContext> TEMPLATE_PROCESS =
        ProcessDefinition.define(TEMPLATE_SAVE, 1, TemplateInput.class, TemplateOutput.class, ProcessContext.class,
            pb -> pb
                .description("Adds or changes an item of the close checklist's template.")
                .permissions(FinancePermissions.PERIOD_CLOSE)
                .contextFactory(CloseProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, TemplateOutput.class))
                .step("Load the template", QueryEntities.of(CloseEntities.TEMPLATE_DATASET,
                    ctx -> EntityQuery.builder().limit(MAX_ITEMS + 1).build(), TEMPLATES))
                .compute("Save the item", (metadata, ctx) -> saveTemplate(ctx)));

    /** {@code FIN_CLOSE_START}: due dates are the end of their day where the company is. */
    public static ProcessDefinition<PeriodInput, ChecklistOutput, ProcessContext> startProcess(BookingTime booking) {
        return ProcessDefinition.define(START, 1, PeriodInput.class, ChecklistOutput.class, ProcessContext.class,
            pb -> pb
                .description("Starts a period's close: its checklist from the template, manual tasks assigned.")
                .permissions(FinancePermissions.CLOSE_TASK)
                .contextFactory(CloseProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, ChecklistOutput.class))
                .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> byPeriod(ctx.get(INPUT, PeriodInput.class).periodKey()), PERIODS))
                .step("Load the template", QueryEntities.of(CloseEntities.TEMPLATE_DATASET,
                    ctx -> EntityQuery.builder().limit(MAX_ITEMS + 1).build(), TEMPLATES))
                .step("Load the period's tasks", QueryEntities.of(CloseEntities.TASK_DATASET,
                    ctx -> byPeriod(ctx.get(INPUT, PeriodInput.class).periodKey(), MAX_ITEMS + 1), TASKS))
                .compute("Make the tasks", (metadata, ctx) -> start(ctx, booking))
                .step("Save", SaveChanges.now())
                .step("Assign the manual tasks", CallProcess.forEach(TASK_ASSIGN, 1,
                    ctx -> ctx.contains(ASSIGN) ? (List<?>) ctx.get(ASSIGN) : List.of(), ASSIGNED)));
    }

    public static final ProcessDefinition<AssignInput, AssignOutput, ProcessContext> ASSIGN_PROCESS =
        ProcessDefinition.define(TASK_ASSIGN, 1, AssignInput.class, AssignOutput.class, ProcessContext.class,
            pb -> pb
                .description("Assigns a manual close task to the holders of its permission.")
                .permissions(FinancePermissions.CLOSE_INTERNAL)
                .internal()
                .contextFactory(CloseProcesses::withInput)
                .outputMapper(ctx -> new AssignOutput(ctx.get(INPUT, AssignInput.class).taskId()))
                .step("Create the task", CreateTask.of(ctx -> {
                    AssignInput input = ctx.get(INPUT, AssignInput.class);
                    return TaskSpec.forPermission(TASK_TYPE, "task.fin.close", Map.of("periodKey",
                            input.periodKey(), "name", input.name()), input.ownerPermission())
                        .about(CloseEntities.TASK, input.taskId())
                        .due(input.dueTime())
                        .source(sourceKey(input.periodKey(), input.taskCode()));
                })));

    public static final ProcessDefinition<CompleteInput, TaskOutput, ProcessContext> COMPLETE_PROCESS =
        ProcessDefinition.define(TASK_COMPLETE, 1, CompleteInput.class, TaskOutput.class, ProcessContext.class,
            pb -> pb
                .description("Marks a manual close task done, with a note and an evidence file.")
                .permissions(FinancePermissions.CLOSE_TASK)
                .actsOn(CloseEntities.TASK, "taskId", a -> a.whenField("status", CloseEntities.OPEN))
                .contextFactory(CloseProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, TaskOutput.class))
                .step("Load the task", QueryEntities.of(CloseEntities.TASK_DATASET,
                    ctx -> byId(ctx.get(INPUT, CompleteInput.class).taskId()), TASKS))
                .step("Load its period", QueryEntities.of(GlEntities.PERIOD_DATASET, ctx -> byPeriod(
                    list(ctx, TASKS).isEmpty() ? "" : list(ctx, TASKS).getFirst().get("periodKey")), PERIODS))
                .compute("Complete it", (metadata, ctx) -> complete(ctx))
                // A refused completion rolls back; the key is then one of no task.
                .step("Close its platform task", CloseTasks.done(ctx -> list(ctx, TASKS).isEmpty()
                    || ctx.hasViolations() ? "fin.close:none" : sourceKey(list(ctx, TASKS).getFirst().get("periodKey"),
                    list(ctx, TASKS).getFirst().get("taskCode")))));

    public static final ProcessDefinition<PeriodInput, ChecklistOutput, ProcessContext> CHECK_PROCESS =
        ProcessDefinition.define(CHECK, 1, PeriodInput.class, ChecklistOutput.class, ProcessContext.class,
            pb -> checks(pb
                .description("Runs a period's automatic close checks and records their results.")
                .permissions(FinancePermissions.CLOSE_TASK)
                .contextFactory(CloseProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, ChecklistOutput.class)))
                .compute("Record the results", (metadata, ctx) -> check(ctx)));

    public static final ProcessDefinition<PeriodInput, CloseOutput, ProcessContext> CLOSE_PROCESS =
        ProcessDefinition.define(CLOSE, 1, PeriodInput.class, CloseOutput.class, ProcessContext.class,
            pb -> checks(pb
                .description("Closes a period once its checklist passes, and keeps the close artifact.")
                .permissions(FinancePermissions.PERIOD_CLOSE)
                .contextFactory(CloseProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, CloseOutput.class))
                // Before anything is read: the postings that read the period open have committed by then.
                .step("Take the year's lock", PeriodLocks.year(ctx -> ctx.get(INPUT, PeriodInput.class).periodKey()))
                .step("Take the period's lock", PeriodLocks.exclusive(
                    ctx -> ctx.get(INPUT, PeriodInput.class).periodKey())))
                .step("Load the template", QueryEntities.of(CloseEntities.TEMPLATE_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("active", true)).limit(1).build(),
                    TEMPLATES))
                .step("Load the period's artifacts", QueryEntities.of(CloseEntities.ARTIFACT_DATASET,
                    ctx -> byPeriod(ctx.get(INPUT, PeriodInput.class).periodKey(), 1000), ARTIFACTS))
                .compute("Check the checklist", (metadata, ctx) -> close(ctx))
                .step("Issue the trial balance", CallProcess.when(ctx -> ctx.contains(ISSUE), ReportProcesses.ISSUE,
                    1, ctx -> ctx.get(ISSUE), ISSUED))
                .compute("Keep the artifact", (metadata, ctx) -> artifact(ctx))
                .step("Keep the period's balances", PeriodBalances.keep(CloseProcesses::periodKey,
                    ctx -> ctx.contains(OUTPUT))));

    /** What the automatic checks read: the period, its tasks and the reports at its end. */
    private static <I, O> ProcessDefinitionBuilder<I, O, ProcessContext> checks(
        ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        return pb
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> byPeriod(ctx.get(INPUT, PeriodInput.class).periodKey()), PERIODS))
            .step("Load the period's tasks", QueryEntities.of(CloseEntities.TASK_DATASET,
                ctx -> byPeriod(ctx.get(INPUT, PeriodInput.class).periodKey(), MAX_ITEMS + 1), TASKS))
            .step("Load the exceptions", RunTemplate.of(CloseChecks.EXCEPTIONS_TEMPLATE,
                ctx -> Map.of("periodKey", periodKey(ctx)), EXCEPTIONS))
            // As known now: the same rows the artifact keeps and a later run as known then gives again.
            .step("Load the trial balance", RunTemplate.of(TRIAL_BALANCE, CloseProcesses::trialBalanceParams, TB))
            .step("Load the receivables aging", RunTemplate.of("finance.ar.aging",
                ctx -> Map.of("agingDate", end(ctx)), AR_ROWS))
            .step("Load the payables aging", RunTemplate.of("finance.ap.aging",
                ctx -> Map.of("agingDate", end(ctx)), AP_ROWS))
            .step("Load the asset register", RunTemplate.of("finance.fa.register",
                ctx -> Map.of("asOf", end(ctx)), FA_ROWS))
            .step("Load the foreign currency items", RunTemplate.of(FxRevaluationProcesses.ITEMS_TEMPLATE,
                ctx -> Map.of("revaluationDate", end(ctx), "rateType", FxEntities.CLOSING, "toleranceDays",
                    BigDecimal.valueOf(FxEntities.DEFAULT_TOLERANCE_DAYS)), FX_ITEMS))
            .step("Load the revaluation run", QueryEntities.of(FxEntities.RUN_DATASET,
                ctx -> byPeriod(periodKey(ctx)), FX_RUNS))
            .step("Load the control accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("controlClass",
                    List.of("AR", "AP", "FA_COST", "FA_ACCUM"))).limit(500).build(), CONTROL_ACCOUNTS));
    }

    // ---- computations ---------------------------------------------------------------------------------------------

    static void saveTemplate(ProcessContext ctx) {
        TemplateInput input = ctx.get(INPUT, TemplateInput.class);
        String kind = input.kind().trim().toUpperCase(Locale.ROOT);
        String checkCode = blank(input.checkCode()) ? null : input.checkCode().trim().toUpperCase(Locale.ROOT);
        String owner = blank(input.ownerPermission()) ? null : input.ownerPermission().trim();
        if (!CloseEntities.KIND_VALUES.contains(kind)) {
            invalid(ctx, "kind", "kind must be one of " + CloseEntities.KIND_VALUES);
            return;
        }
        if (CloseEntities.AUTO.equals(kind) && (checkCode == null || !CloseChecks.ALL.contains(checkCode))) {
            invalid(ctx, "checkCode", "An automatic item checks one of " + CloseChecks.ALL);
            return;
        }
        if (CloseEntities.MANUAL.equals(kind) && (checkCode != null || owner == null)) {
            invalid(ctx, "ownerPermission", "A manual item names the permission of who does it and checks nothing");
            return;
        }
        List<EntityInstance> templates = list(ctx, TEMPLATES);
        EntityInstance existing = templates.stream().filter(t -> input.taskCode().equals(t.get("taskCode")))
            .findFirst().orElse(null);
        // One item per check: two would only repeat it.
        if (checkCode != null && templates.stream().anyMatch(t -> checkCode.equals(t.get("checkCode"))
            && !input.taskCode().equals(t.get("taskCode")))) {
            invalid(ctx, "checkCode", "Another item checks " + checkCode + " already");
            return;
        }
        if (existing == null && templates.size() >= MAX_ITEMS) {
            invalid(ctx, "taskCode", "The template has " + MAX_ITEMS + " items already");
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", input.name().trim());
        values.put("kind", kind);
        values.put("checkCode", checkCode);
        values.put("ownerPermission", CloseEntities.AUTO.equals(kind) ? null : owner);
        values.put("dueDays", BigDecimal.valueOf(input.dueDays()));
        values.put("required", !Boolean.FALSE.equals(input.required()));
        values.put("sortOrder", BigDecimal.valueOf(input.sortOrder() == null ? 0 : input.sortOrder()));
        values.put("active", !Boolean.FALSE.equals(input.active()));
        if (existing == null) {
            values.put("taskCode", input.taskCode());
            Object id = ctx.changes().insert(CloseEntities.TEMPLATE, values);
            ctx.put(OUTPUT, new TemplateOutput(String.valueOf(id), input.taskCode(), true));
        } else {
            ctx.changes().update(CloseEntities.TEMPLATE, existing.id(), existing.version(), values);
            ctx.put(OUTPUT, new TemplateOutput(String.valueOf(existing.id()), input.taskCode(), false));
        }
    }

    static void start(ProcessContext ctx, BookingTime booking) {
        EntityInstance period = closable(ctx);
        if (period == null) {
            return;
        }
        String periodKey = period.get("periodKey");
        LocalDate end = period.get("endDate");
        Set<String> made = new java.util.HashSet<>();
        list(ctx, TASKS).forEach(t -> made.add(t.get("taskCode")));
        List<TaskOutput> tasks = new ArrayList<>(list(ctx, TASKS).stream().map(CloseProcesses::output).toList());
        List<AssignInput> assign = new ArrayList<>();
        int created = 0;
        for (EntityInstance item : list(ctx, TEMPLATES)) {
            if (!Boolean.TRUE.equals(item.get("active")) || made.contains(item.<String>get("taskCode"))) {
                continue;
            }
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("periodKey", periodKey);
            for (String field : List.of("taskCode", "name", "kind", "checkCode", "ownerPermission", "required",
                "sortOrder")) {
                task.put(field, item.get(field));
            }
            LocalDate due = end.plusDays(item.<BigDecimal>get("dueDays").longValue());
            task.put("dueDate", due);
            task.put("status", CloseEntities.OPEN);
            Object id = ctx.changes().insert(CloseEntities.TASK, task);
            created++;
            tasks.add(new TaskOutput(String.valueOf(id), item.get("taskCode"), item.get("name"), item.get("kind"),
                item.get("checkCode"), item.get("ownerPermission"), due, Boolean.TRUE.equals(item.get("required")),
                CloseEntities.OPEN, null, null, null, null, null));
            if (CloseEntities.MANUAL.equals(item.get("kind"))) {
                assign.add(new AssignInput(String.valueOf(id), periodKey, item.get("taskCode"), item.get("name"),
                    item.get("ownerPermission"), booking.endOf(due)));
            }
        }
        ctx.put(ASSIGN, List.copyOf(assign));
        ctx.put(OUTPUT, new ChecklistOutput(periodKey, sorted(tasks), created, ready(tasks)));
    }

    static void complete(ProcessContext ctx) {
        CompleteInput input = ctx.get(INPUT, CompleteInput.class);
        List<EntityInstance> found = list(ctx, TASKS);
        if (found.isEmpty()) {
            ctx.reject(new Violation("taskId", TASK_NOT_FOUND, "There is no close task " + input.taskId(), Map.of()));
            return;
        }
        EntityInstance task = found.getFirst();
        if (!CloseEntities.MANUAL.equals(task.get("kind"))) {
            ctx.reject(new Violation("taskId", TASK_NOT_MANUAL, "Task " + task.get("taskCode") + " is an automatic "
                + "check: it passes when the books do", Map.of("taskCode", (Object) task.get("taskCode"))));
            return;
        }
        if (!CloseEntities.OPEN.equals(task.get("status"))) {
            ctx.reject(new Violation("taskId", TASK_DONE, "Task " + task.get("taskCode") + " is done already",
                Map.of("taskCode", (Object) task.get("taskCode"))));
            return;
        }
        EntityInstance period = list(ctx, PERIODS).isEmpty() ? null : list(ctx, PERIODS).getFirst();
        if (period != null && PeriodPolicy.Status.CLOSED.name().equals(period.get("status"))) {
            closed(ctx, period.get("periodKey"));
            return;
        }
        String owner = task.get("ownerPermission");
        if (owner != null && !ctx.request().hasPermission(owner)) {
            ctx.reject(new Violation("taskId", NOT_OWNER, "Task " + task.get("taskCode") + " is done by a holder of "
                + owner, Map.of("permission", owner)));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", CloseEntities.DONE);
        values.put("completedBy", ctx.request().actorId());
        values.put("completedAt", ctx.opTime());
        values.put("note", blank(input.note()) ? null : input.note().trim());
        values.put("evidenceFileId", input.evidenceFileId());
        ctx.changes().update(CloseEntities.TASK, task.id(), task.version(), values);
        Map<String, Object> after = new LinkedHashMap<>(task.attributes());
        after.putAll(values);
        ctx.put(OUTPUT, output(String.valueOf(task.id()), after));
    }

    static void check(ProcessContext ctx) {
        EntityInstance period = closable(ctx);
        if (period == null) {
            return;
        }
        if (list(ctx, TASKS).isEmpty()) {
            notStarted(ctx, period.get("periodKey"));
            return;
        }
        List<TaskOutput> tasks = record(ctx, results(ctx, period));
        ctx.put(OUTPUT, new ChecklistOutput(period.get("periodKey"), sorted(tasks), 0, ready(tasks)));
    }

    static void close(ProcessContext ctx) {
        EntityInstance period = closable(ctx);
        if (period == null) {
            return;
        }
        String periodKey = period.get("periodKey");
        if (list(ctx, TASKS).isEmpty() && !list(ctx, TEMPLATES).isEmpty()) {
            notStarted(ctx, periodKey);
            return;
        }
        Map<String, CloseChecks.Result> results = results(ctx, period);
        List<TaskOutput> tasks = record(ctx, results);
        for (TaskOutput task : tasks) {
            if (!task.required() || CloseEntities.DONE.equals(task.status())
                || CloseEntities.PASSED.equals(task.status())) {
                continue;
            }
            String why = CloseEntities.MANUAL.equals(task.kind()) ? "is not done" : "failed: " + task.result();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("taskCode", task.taskCode());
            params.put("name", task.name());
            params.put("reason", why);
            ctx.reject(new Violation("periodKey", CHECKS_FAILED, task.taskCode() + " (" + task.name() + ") " + why,
                params));
        }
        if (ctx.hasViolations()) {
            return;
        }
        LocalDate end = period.get("endDate");
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", PeriodPolicy.Status.CLOSED.name());
        PeriodProcesses.SUBLEDGER_FIELDS.values().forEach(field -> state.put(field, "CLOSED"));
        ctx.changes().update(GlEntities.PERIOD, period.id(), period.version(), state);
        ctx.put(RESULTS, List.copyOf(sorted(tasks)));
        ctx.put(ISSUE, new ReportProcesses.IssueInput(TRIAL_BALANCE, trialBalanceParams(ctx), null, null, null));
    }

    @SuppressWarnings("unchecked")
    static void artifact(ProcessContext ctx) {
        if (!ctx.contains(RESULTS)) {
            return;
        }
        EntityInstance period = list(ctx, PERIODS).getFirst();
        List<TaskOutput> checklist = (List<TaskOutput>) ctx.get(RESULTS);
        ReportProcesses.IssueOutput issued = ctx.contains(ISSUED)
            ? ctx.get(ISSUED, ReportProcesses.IssueOutput.class) : null;
        List<List<Object>> items = new ArrayList<>();
        for (TaskOutput t : checklist) {
            String result = CloseEntities.MANUAL.equals(t.kind())
                ? (t.completedBy() == null ? null : "Done by " + t.completedBy() + " at " + t.completedAt())
                : t.result();
            items.add(java.util.Arrays.asList(CloseEntities.CHECKLIST, t.taskCode(), t.name(), t.status(), result));
        }
        Written written = writeArtifact(ctx, period, accounts(ctx),
            (List<CloseChecks.Subledger>) ctx.get(SUBLEDGER_TOTALS), items, issued, list(ctx, ARTIFACTS));
        ctx.put(OUTPUT, new CloseOutput(period.get("periodKey"), PeriodPolicy.Status.CLOSED.name(),
            written.artifactId(), written.seq(), issued == null ? null : issued.runId(), written.trialBalanceHash(),
            written.contentHash(), checklist));
    }

    /** An artifact as written: its identity, its number among the period's closes and its hashes. */
    public record Written(String artifactId, int seq, String trialBalanceHash, String contentHash) {}

    /**
     * Writes a close artifact of a period (FIN-PC-005): the trial balance's accounts, the subledgers beside their
     * control accounts, and items (each {@code section, code, name, status, result}), closed by the actor now and as
     * known now; it supersedes the period's latest artifact among {@code previous}. Shared by the period close and
     * the year close (period 13), which then keep the period's balances counted to the close
     * ({@link PeriodBalances#keep}).
     */
    public static Written writeArtifact(ProcessContext ctx, EntityInstance period, List<CloseChecks.Account> accounts,
        List<CloseChecks.Subledger> subledgers, List<List<Object>> items, ReportProcesses.IssueOutput issued,
        List<EntityInstance> previous) {
        String periodKey = period.get("periodKey");
        EntityInstance latest = previous.stream().max(Comparator.comparing(a -> a.<BigDecimal>get("seq")))
            .orElse(null);
        int seq = latest == null ? 1 : latest.<BigDecimal>get("seq").intValue() + 1;
        BigDecimal debit = accounts.stream().map(CloseChecks.Account::debit).reduce(BigDecimal.ZERO,
            BigDecimal::add).setScale(2);
        BigDecimal credit = accounts.stream().map(CloseChecks.Account::credit).reduce(BigDecimal.ZERO,
            BigDecimal::add).setScale(2);
        List<List<Object>> lines = new ArrayList<>();
        int n = 0;
        for (CloseChecks.Account a : accounts) {
            lines.add(java.util.Arrays.asList(CloseEntities.TRIAL_BALANCE, BigDecimal.valueOf(++n), a.code(),
                a.name(), a.debit(), a.credit(), null, null, null, null));
        }
        for (CloseChecks.Subledger s : subledgers == null ? List.<CloseChecks.Subledger>of() : subledgers) {
            lines.add(java.util.Arrays.asList(CloseEntities.SUBLEDGER, BigDecimal.valueOf(++n), s.code(), s.name(),
                null, null, s.subledger(), s.ledger(),
                s.difference().signum() == 0 ? CloseEntities.PASSED : CloseEntities.FAILED, null));
        }
        for (List<Object> item : items) {
            lines.add(java.util.Arrays.asList(item.get(0), BigDecimal.valueOf(++n), item.get(1), item.get(2), null,
                null, null, null, item.get(3), item.get(4)));
        }
        String closedBy = ctx.request().actorId();
        Instant at = ctx.opTime();
        String tbHash = CloseChecks.trialBalanceHash(accounts);
        // Hashed as stored: numbers as numbers, so the rows of the artifact give its hash again.
        String contentHash = CloseChecks.contentHash(java.util.Arrays.asList(periodKey, BigDecimal.valueOf(seq),
            period.get("endDate"), closedBy, at, at, debit, credit, tbHash, issued == null ? null : issued.runId()),
            lines);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("periodKey", periodKey);
        values.put("seq", BigDecimal.valueOf(seq));
        values.put("periodEnd", period.get("endDate"));
        values.put("closedBy", closedBy);
        values.put("closedAt", at);
        values.put("knownAt", at);
        values.put("totalDebit", debit);
        values.put("totalCredit", credit);
        values.put("trialBalanceHash", tbHash);
        values.put("contentHash", contentHash);
        values.put("reportRunId", issued == null ? null : issued.runId());
        values.put("supersedesId", latest == null ? null : latest.id());
        Object artifactId = ctx.changes().insert(CloseEntities.ARTIFACT, values);
        String[] names = {"section", "seq", "code", "name", "debit", "credit", "amount", "ledgerAmount", "status",
            "result"};
        for (List<Object> line : lines) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("artifactId", artifactId);
            for (int i = 0; i < names.length; i++) {
                row.put(names[i], line.get(i));
            }
            ctx.changes().insert(CloseEntities.ARTIFACT_LINE, row);
        }
        return new Written(String.valueOf(artifactId), seq, tbHash, contentHash);
    }

    // ---- the checks -----------------------------------------------------------------------------------------------

    /** Every check's result, by its code. */
    static Map<String, CloseChecks.Result> results(ProcessContext ctx, EntityInstance period) {
        String periodKey = period.get("periodKey");
        LocalDate end = period.get("endDate");
        List<CloseChecks.Finding> findings = rows(ctx, EXCEPTIONS).stream()
            .map(r -> new CloseChecks.Finding((String) r.get("checkCode"), (String) r.get("reference"),
                (String) r.get("description")))
            .toList();
        Map<String, CloseChecks.Result> results = new LinkedHashMap<>();
        for (String code : CloseChecks.ALL) {
            if (!CloseChecks.SUBLEDGERS.equals(code) && !CloseChecks.REVALUATION_RUN.equals(code)) {
                results.put(code, CloseChecks.fromExceptions(code, findings, periodKey));
            }
        }
        Map<String, String> classes = new LinkedHashMap<>();
        list(ctx, CONTROL_ACCOUNTS).forEach(a -> classes.put(a.get("accountCode"), a.get("controlClass")));
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(ctx, TB)) {
            if (!Boolean.TRUE.equals(row.get("summary"))) {
                balances.put((String) row.get("accountCode"), money(row.get("balance")));
            }
        }
        List<CloseChecks.Subledger> subledgers = CloseChecks.compare(sum(ctx, AR_ROWS, "openAmountUsd"),
            sum(ctx, AP_ROWS, "openAmountUsd"), sum(ctx, FA_ROWS, "cost"), sum(ctx, FA_ROWS, "accumulated"),
            classes, balances);
        ctx.put(SUBLEDGER_TOTALS, subledgers);
        results.put(CloseChecks.SUBLEDGERS, CloseChecks.subledgers(subledgers, end));
        EntityInstance run = list(ctx, FX_RUNS).isEmpty() ? null : list(ctx, FX_RUNS).getFirst();
        results.put(CloseChecks.REVALUATION_RUN, CloseChecks.revaluation(rows(ctx, FX_ITEMS).size(),
            run == null ? null : run.get("runNo"), periodKey, end));
        return results;
    }

    /** Each automatic task takes its check's result now; the tasks as they then are. */
    static List<TaskOutput> record(ProcessContext ctx, Map<String, CloseChecks.Result> results) {
        List<TaskOutput> tasks = new ArrayList<>();
        for (EntityInstance task : list(ctx, TASKS)) {
            if (!CloseEntities.AUTO.equals(task.get("kind"))) {
                tasks.add(output(task));
                continue;
            }
            CloseChecks.Result result = results.get(task.<String>get("checkCode"));
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", result != null && result.passed() ? CloseEntities.PASSED : CloseEntities.FAILED);
            values.put("result", result == null ? "Unknown check " + task.get("checkCode") : result.result());
            values.put("evidence", result == null ? null : result.evidence());
            values.put("checkedAt", ctx.opTime());
            ctx.changes().update(CloseEntities.TASK, task.id(), task.version(), values);
            Map<String, Object> after = new LinkedHashMap<>(task.attributes());
            after.putAll(values);
            tasks.add(output(String.valueOf(task.id()), after));
        }
        return tasks;
    }

    static List<CloseChecks.Account> accounts(ProcessContext ctx) {
        return accounts(rows(ctx, TB));
    }

    /** The trial balance's accounts with a balance, in the order of their codes, as an artifact keeps them. */
    public static List<CloseChecks.Account> accounts(List<Map<String, Object>> trialBalanceRows) {
        List<CloseChecks.Account> accounts = new ArrayList<>();
        for (Map<String, Object> row : trialBalanceRows) {
            BigDecimal debit = money(row.get("debit"));
            BigDecimal credit = money(row.get("credit"));
            if (!Boolean.TRUE.equals(row.get("summary")) && (debit.signum() != 0 || credit.signum() != 0)) {
                accounts.add(new CloseChecks.Account(String.valueOf(row.get("accountCode")),
                    String.valueOf(row.get("accountName")), debit, credit));
            }
        }
        accounts.sort(Comparator.comparing(CloseChecks.Account::code));
        return List.copyOf(accounts);
    }

    /**
     * The hash of a trial balance's rows as a close keeps them: the trial balance run as known at an artifact's
     * {@code knownAt} gives its {@code trialBalanceHash} again (FIN-PC-005 acceptance 2).
     */
    public static String trialBalanceHash(List<Map<String, Object>> trialBalanceRows) {
        return CloseChecks.trialBalanceHash(accounts(trialBalanceRows));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The period, when it exists, holds no opening and is not closed; else refused. */
    private static EntityInstance closable(ProcessContext ctx) {
        String periodKey = periodKey(ctx);
        if (list(ctx, PERIODS).isEmpty()) {
            ctx.reject(new Violation("periodKey", PeriodProcesses.PERIOD_NOT_FOUND, "There is no period " + periodKey,
                Map.of("periodKey", periodKey)));
            return null;
        }
        EntityInstance period = list(ctx, PERIODS).getFirst();
        if (Boolean.TRUE.equals(period.get("opening"))) {
            ctx.reject(new Violation("periodKey", PeriodProcesses.OPENING_PERIOD, "Period " + periodKey
                + " holds the opening of the books: it changes only through the migration",
                Map.of("periodKey", periodKey)));
            return null;
        }
        if (PeriodPolicy.Status.CLOSED.name().equals(period.get("status"))) {
            closed(ctx, periodKey);
            return null;
        }
        if (Boolean.TRUE.equals(period.get("adjustment"))) {
            // The adjustment period closes with the year (FIN-PC-008, phase F8c).
            ctx.reject(new Violation("periodKey", NOT_REGULAR, "Period " + periodKey + " is the adjustment period: "
                + "it closes with the year", Map.of("periodKey", periodKey)));
            return null;
        }
        return period;
    }

    private static void closed(ProcessContext ctx, String periodKey) {
        ctx.reject(new Violation("periodKey", PeriodPolicy.PERIOD_CLOSED, "Period " + periodKey + " is closed",
            Map.of("periodKey", periodKey)));
    }

    private static void notStarted(ProcessContext ctx, String periodKey) {
        ctx.reject(new Violation("periodKey", NOT_STARTED, "The close of " + periodKey + " has not started: its "
            + "checklist is made by " + START, Map.of("periodKey", periodKey)));
    }

    private static void invalid(ProcessContext ctx, String field, String message) {
        ctx.reject(new Violation(field, TEMPLATE_INVALID, message, Map.of()));
    }

    /** Period 13 left out; as known now, the close's time (the template takes its point in time as a parameter). */
    private static Map<String, Object> trialBalanceParams(ProcessContext ctx) {
        return Map.of("through", end(ctx), "adjustments", false, "knownAt", ctx.opTime());
    }

    private static String periodKey(ProcessContext ctx) {
        return ctx.get(INPUT, PeriodInput.class).periodKey().trim();
    }

    /** The period's last day; a day of no period when there is none, so the reports find nothing. */
    private static LocalDate end(ProcessContext ctx) {
        List<EntityInstance> periods = list(ctx, PERIODS);
        return periods.isEmpty() ? LocalDate.EPOCH : periods.getFirst().get("endDate");
    }

    static String sourceKey(String periodKey, String taskCode) {
        return "fin.close:" + periodKey + ":" + taskCode;
    }

    static EntityQuery byPeriod(String periodKey) {
        return byPeriod(periodKey, 1);
    }

    static EntityQuery byPeriod(String periodKey, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("periodKey", periodKey == null ? ""
            : periodKey.trim())).limit(limit).build();
    }

    private static EntityQuery byId(UUID id) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("taskId", id)).limit(1).build();
    }

    private static boolean ready(List<TaskOutput> tasks) {
        return tasks.stream().allMatch(t -> !t.required() || CloseEntities.DONE.equals(t.status())
            || CloseEntities.PASSED.equals(t.status()));
    }

    private static List<TaskOutput> sorted(List<TaskOutput> tasks) {
        return tasks.stream().sorted(Comparator.comparing((TaskOutput t) -> t.taskCode())).toList();
    }

    private static TaskOutput output(EntityInstance task) {
        return output(String.valueOf(task.id()), task.attributes());
    }

    private static TaskOutput output(String id, Map<String, Object> t) {
        return new TaskOutput(id, (String) t.get("taskCode"), (String) t.get("name"), (String) t.get("kind"),
            (String) t.get("checkCode"), (String) t.get("ownerPermission"), (LocalDate) t.get("dueDate"),
            Boolean.TRUE.equals(t.get("required")), (String) t.get("status"), (String) t.get("result"),
            (String) t.get("evidence"), (Instant) t.get("checkedAt"), (String) t.get("completedBy"),
            (Instant) t.get("completedAt"));
    }

    private static BigDecimal sum(ProcessContext ctx, String key, String field) {
        return rows(ctx, key).stream().map(r -> money(r.get(field))).reduce(BigDecimal.ZERO.setScale(2),
            BigDecimal::add);
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(String.valueOf(value)).setScale(2);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private static ProcessContext withInput(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private CloseProcesses() {}
}
