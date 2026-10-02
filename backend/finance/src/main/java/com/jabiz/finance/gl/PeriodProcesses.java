package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.FiscalCalendar;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.jabiz.finance.gl.AccountProcesses.list;

/**
 * The fiscal calendar and period states (FIN-PC-001, FIN-PC-003; docs/finance/00-design.md section 7).
 * <ul>
 *   <li>{@code FIN_FISCAL_YEAR_CREATE}: a fiscal year and its twelve periods, and the adjustment period 13 when
 *       asked; every period starts open. Years do not overlap: the overlap is refused up front, and two requests
 *       racing each other are kept apart by the unique start day of the regular periods.</li>
 *   <li>{@code FIN_PERIOD_SET_STATE}: the general ledger's state of a period, open or soft-closed. A period closes
 *       only through its checklist ({@code FIN_PERIOD_CLOSE}, FIN-PC-005) and opens again only through a governed
 *       reopening (FIN-PC-006, phase F8b).</li>
 *   <li>{@code FIN_PERIOD_SET_SUBLEDGER_STATE}: a subledger's state of a period, open or closed; a subledger may close
 *       before the general ledger, and stays closed with it.</li>
 * </ul>
 * Each change is kept in the period's history.
 */
public final class PeriodProcesses {

    public static final String FISCAL_YEAR_CREATE = "FIN_FISCAL_YEAR_CREATE";
    public static final String SET_STATE = "FIN_PERIOD_SET_STATE";
    public static final String SET_SUBLEDGER_STATE = "FIN_PERIOD_SET_SUBLEDGER_STATE";

    public static final String FISCAL_YEAR_OVERLAP = "FIN_FISCAL_YEAR_OVERLAP";
    public static final String FISCAL_YEAR_INVALID = "FIN_FISCAL_YEAR_INVALID";
    public static final String PERIOD_NOT_FOUND = "FIN_PERIOD_NOT_FOUND";
    public static final String INVALID_STATE = "FIN_PERIOD_INVALID_STATE";
    public static final String OPENING_PERIOD = "FIN_PERIOD_OPENING";
    public static final String BEFORE_OPENING = "FIN_FISCAL_YEAR_BEFORE_OPENING";
    public static final String CLOSE_REQUIRED = "FIN_PERIOD_CLOSE_REQUIRED";
    public static final String REOPEN_REQUIRED = "FIN_PERIOD_REOPEN_REQUIRED";

    /** Subledgers with a state of their own, and the period field holding it. */
    public static final Map<String, String> SUBLEDGER_FIELDS =
        Map.of("AR", "arStatus", "AP", "apStatus", "BANK", "bankStatus", "FA", "faStatus");

    /**
     * @param fiscalYear       the calendar year the fiscal year ends in
     * @param startDate        its first day, the first of a month; January 1 of {@code fiscalYear} when absent
     * @param adjustmentPeriod whether it has the adjustment period 13
     */
    public record FiscalYearInput(@NotNull @Min(2000) @Max(2999) Integer fiscalYear, LocalDate startDate,
        Boolean adjustmentPeriod) {}

    public record FiscalYearOutput(String fiscalYearId, int fiscalYear, LocalDate startDate, LocalDate endDate,
        List<String> periods) {}

    /** @param status {@code OPEN}, {@code SOFT_CLOSED} or {@code CLOSED} */
    public record StateInput(@NotBlank String periodKey, @NotBlank String status) {}

    /**
     * @param subledger {@code AR}, {@code AP}, {@code BANK} or {@code FA}
     * @param status    {@code OPEN} or {@code CLOSED}
     */
    public record SubledgerStateInput(@NotBlank String periodKey, @NotBlank String subledger,
        @NotBlank String status) {}

    /** @param changed false when the period was in that state already */
    public record PeriodOutput(String periodKey, String status, String arStatus, String apStatus, String bankStatus,
        String faStatus, boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String YEARS = "years";
    static final String PERIODS = "periods";
    static final String OPENINGS = "openings";

    public static final ProcessDefinition<FiscalYearInput, FiscalYearOutput, ProcessContext> FISCAL_YEAR_PROCESS =
        ProcessDefinition.define(FISCAL_YEAR_CREATE, 1, FiscalYearInput.class, FiscalYearOutput.class,
            ProcessContext.class, pb -> pb
                .description("Creates a fiscal year with its periods, all open.")
                .permissions(FinancePermissions.PERIOD_MAINTAIN)
                .contextFactory(AccountProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, FiscalYearOutput.class))
                .step("Load the years it could overlap", QueryEntities.of(GlEntities.FISCAL_YEAR_DATASET,
                    PeriodProcesses::overlapping, YEARS))
                .step("Load the opening of the books", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> openings(), OPENINGS))
                .compute("Create the year and its periods", (metadata, ctx) -> createYear(ctx)));

    public static final ProcessDefinition<StateInput, PeriodOutput, ProcessContext> STATE_PROCESS =
        ProcessDefinition.define(SET_STATE, 1, StateInput.class, PeriodOutput.class, ProcessContext.class, pb -> pb
            .description("Opens or soft-closes a period of the general ledger.")
            .permissions(FinancePermissions.PERIOD_CLOSE)
            .contextFactory(AccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, PeriodOutput.class))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> byKey(ctx.get(INPUT, StateInput.class).periodKey()), PERIODS))
            .compute("Change its state", (metadata, ctx) -> {
                StateInput input = ctx.get(INPUT, StateInput.class);
                setState(ctx, input.periodKey(), "status", input.status(), GlEntities.PERIOD_STATUS_VALUES);
            }));

    public static final ProcessDefinition<SubledgerStateInput, PeriodOutput, ProcessContext> SUBLEDGER_STATE_PROCESS =
        ProcessDefinition.define(SET_SUBLEDGER_STATE, 1, SubledgerStateInput.class, PeriodOutput.class,
            ProcessContext.class, pb -> pb
                .description("Opens or closes a period for one subledger.")
                .permissions(FinancePermissions.PERIOD_CLOSE)
                .contextFactory(AccountProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, PeriodOutput.class))
                .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> byKey(ctx.get(INPUT, SubledgerStateInput.class).periodKey()), PERIODS))
                .compute("Change the subledger's state", (metadata, ctx) -> {
                    SubledgerStateInput input = ctx.get(INPUT, SubledgerStateInput.class);
                    String field = SUBLEDGER_FIELDS.get(input.subledger().trim().toUpperCase(Locale.ROOT));
                    if (field == null) {
                        ctx.reject(new Violation("subledger", INVALID_STATE, "subledger must be one of "
                            + List.of("AR", "AP", "BANK", "FA"), Map.of("value", input.subledger())));
                        return;
                    }
                    setState(ctx, input.periodKey(), field, input.status(), GlEntities.SUBLEDGER_STATUS_VALUES);
                }));

    // ---- queries -------------------------------------------------------------------------------------------------

    private static EntityQuery overlapping(ProcessContext ctx) {
        FiscalYearInput input = ctx.get(INPUT, FiscalYearInput.class);
        LocalDate start = start(input);
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.Lte("startDate", start.plusYears(1).minusDays(1)),
                new QueryPredicate.Gte("endDate", start))))
            .limit(2)
            .build();
    }

    /** The opening period of the books, if they were opened (at most one). */
    public static EntityQuery openings() {
        return EntityQuery.builder().where(new QueryPredicate.Eq("opening", true)).limit(2).build();
    }

    static EntityQuery byKey(String periodKey) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("periodKey", periodKey.trim())).limit(1).build();
    }

    // ---- computations ---------------------------------------------------------------------------------------------

    static void createYear(ProcessContext ctx) {
        FiscalYearInput input = ctx.get(INPUT, FiscalYearInput.class);
        int year = input.fiscalYear();
        List<FiscalCalendar.Period> periods;
        try {
            periods = FiscalCalendar.periods(year, start(input), Boolean.TRUE.equals(input.adjustmentPeriod()));
        } catch (IllegalArgumentException e) {
            ctx.reject(new Violation("startDate", FISCAL_YEAR_INVALID, e.getMessage(), Map.of("fiscalYear", year)));
            return;
        }
        if (!list(ctx, YEARS).isEmpty()) {
            EntityInstance other = list(ctx, YEARS).getFirst();
            ctx.reject(new Violation("fiscalYear", FISCAL_YEAR_OVERLAP, "Fiscal year " + year + " would overlap "
                + "fiscal year " + other.<BigDecimal>get("fiscalYear").toPlainString(),
                Map.of("fiscalYear", year, "other", other.<BigDecimal>get("fiscalYear"))));
            return;
        }
        LocalDate start = periods.getFirst().start();
        LocalDate end = periods.getLast().end();
        // The books begin where they were opened: a year before that would hold periods the opening entry
        // already summarizes.
        for (EntityInstance opening : list(ctx, OPENINGS)) {
            LocalDate openedOn = opening.get("endDate");
            if (!start.isAfter(openedOn)) {
                ctx.reject(new Violation("fiscalYear", BEFORE_OPENING, "The books were opened on " + openedOn
                    + ": fiscal year " + year + " would start before them", Map.of("fiscalYear", year,
                    "openedOn", openedOn.toString())));
                return;
            }
        }
        Map<String, Object> fiscalYear = new LinkedHashMap<>();
        fiscalYear.put("fiscalYear", BigDecimal.valueOf(year));
        fiscalYear.put("startDate", start);
        fiscalYear.put("endDate", end);
        fiscalYear.put("adjustmentPeriod", Boolean.TRUE.equals(input.adjustmentPeriod()));
        Object yearId = ctx.changes().insert(GlEntities.FISCAL_YEAR, fiscalYear);
        for (FiscalCalendar.Period period : periods) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fiscalYearId", yearId);
            row.put("fiscalYear", BigDecimal.valueOf(year));
            row.put("periodNo", BigDecimal.valueOf(period.number()));
            row.put("periodKey", period.key());
            row.put("adjustment", period.adjustment());
            row.put("opening", false);
            row.put("startDate", period.start());
            row.put("endDate", period.end());
            row.put("status", "OPEN");
            SUBLEDGER_FIELDS.values().forEach(field -> row.put(field, "OPEN"));
            ctx.changes().insert(GlEntities.PERIOD, row);
        }
        ctx.put(OUTPUT, new FiscalYearOutput(String.valueOf(yearId), year, start, end,
            periods.stream().map(FiscalCalendar.Period::key).toList()));
    }

    private static void setState(ProcessContext ctx, String periodKey, String field, String status,
        List<String> allowed) {
        String wanted = status.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(wanted)) {
            ctx.reject(new Violation("status", INVALID_STATE, "status must be one of " + allowed,
                Map.of("value", status)));
            return;
        }
        List<EntityInstance> found = list(ctx, PERIODS);
        if (found.isEmpty()) {
            ctx.reject(new Violation("periodKey", PERIOD_NOT_FOUND, "There is no period " + periodKey.trim(),
                Map.of("periodKey", periodKey.trim())));
            return;
        }
        EntityInstance period = found.getFirst();
        if (Boolean.TRUE.equals(period.get("opening"))) {
            // Closed once the migration is done (FIN_OPENING_CLOSE) and never opened again.
            ctx.reject(new Violation("periodKey", OPENING_PERIOD, "Period " + period.get("periodKey")
                + " holds the opening of the books: it changes only through the migration",
                Map.of("periodKey", period.<String>get("periodKey"))));
            return;
        }
        boolean closed = "CLOSED".equals(period.get("status"));
        if ("status".equals(field) && "CLOSED".equals(wanted) && !closed) {
            ctx.reject(new Violation("status", CLOSE_REQUIRED, "Period " + period.get("periodKey") + " closes through "
                + "its checklist (FIN_PERIOD_CLOSE)", Map.of("periodKey", period.<String>get("periodKey"))));
            return;
        }
        if (closed && !"CLOSED".equals(wanted)) {
            ctx.reject(new Violation("status", REOPEN_REQUIRED, "Period " + period.get("periodKey") + " is closed: it "
                + "opens again only through a reopening", Map.of("periodKey", period.<String>get("periodKey"))));
            return;
        }
        boolean change = !wanted.equals(period.get(field));
        Map<String, Object> state = new LinkedHashMap<>();
        for (String name : List.of("status", "arStatus", "apStatus", "bankStatus", "faStatus")) {
            state.put(name, period.get(name));
        }
        if (change) {
            state.put(field, wanted);
            ctx.changes().update(GlEntities.PERIOD, period.id(), period.version(), Map.of(field, wanted));
        }
        ctx.put(OUTPUT, new PeriodOutput(period.get("periodKey"), (String) state.get("status"),
            (String) state.get("arStatus"), (String) state.get("apStatus"), (String) state.get("bankStatus"),
            (String) state.get("faStatus"), change));
    }

    private static LocalDate start(FiscalYearInput input) {
        return input.startDate() != null ? input.startDate() : LocalDate.of(input.fiscalYear(), 1, 1);
    }

    private PeriodProcesses() {}
}
